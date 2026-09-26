package com.ravango.engine.camera.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.ravango.core.common.log.RgLog
import com.ravango.core.model.VideoCodec
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal data class VideoEncoderConfig(
    val codec: VideoCodec,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    /** Preferred encoder component (hardware first); null = let the platform choose. */
    val encoderName: String?,
    val iFrameIntervalSec: Int = 1,
)

/**
 * Surface-input video encoder (H.264 / HEVC) running MediaCodec in async mode on its own thread.
 * Output samples and the format go straight to [sink] (the segmented muxer).
 */
internal class VideoEncoder private constructor(
    private val codec: MediaCodec,
    val inputSurface: Surface,
    private val thread: HandlerThread,
    val config: VideoEncoderConfig,
) {
    interface Sink {
        fun onVideoFormat(format: MediaFormat)
        fun onVideoSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo)
        fun onVideoError(error: Exception)
    }

    private val eos = CountDownLatch(1)
    @Volatile private var released = false

    fun requestKeyFrame() {
        if (released) return
        runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
            .onFailure { RgLog.w(TAG, "sync frame request failed", it) }
    }

    /** Signals end of stream; the renderer must have stopped drawing into [inputSurface]. */
    fun finish(timeoutMs: Long = 3_000): Boolean {
        if (released) return false
        runCatching { codec.signalEndOfInputStream() }.onFailure { RgLog.w(TAG, "signalEndOfInputStream failed", it); return false }
        val done = eos.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!done) RgLog.w(TAG, "encoder EOS timed out")
        return done
    }

    fun release() {
        if (released) return
        released = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { inputSurface.release() }
        thread.quitSafely()
    }

    private fun onEos() = eos.countDown()

    companion object {
        private const val TAG = "VideoEncoder"

        /** Creates, configures and starts the encoder, degrading optional settings if the codec rejects them. */
        fun create(config: VideoEncoderConfig, sink: Sink): VideoEncoder {
            val thread = HandlerThread("RgVideoEnc").apply { start() }
            val handler = Handler(thread.looper)
            val attempts = listOf(
                Options(profile = true, bitrateMode = true),
                Options(profile = false, bitrateMode = true),
                Options(profile = false, bitrateMode = false),
            )
            var lastError: Exception? = null
            for (options in attempts) {
                var codec: MediaCodec? = null
                try {
                    codec = config.encoderName?.let { runCatching { MediaCodec.createByCodecName(it) }.getOrNull() }
                        ?: MediaCodec.createEncoderByType(config.codec.mimeType)
                    val format = buildFormat(config, codec.codecInfo, options)
                    val holder = arrayOfNulls<VideoEncoder>(1)
                    codec.setCallback(Callback(sink, holder), handler)
                    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    val surface = codec.createInputSurface()
                    codec.start()
                    RgLog.i(TAG, "started ${codec.name} ${config.width}x${config.height}@${config.fps} ${config.bitrate}bps $options")
                    return VideoEncoder(codec, surface, thread, config).also { holder[0] = it }
                } catch (e: Exception) {
                    lastError = e
                    RgLog.w(TAG, "configure failed with $options", e)
                    runCatching { codec?.release() }
                }
            }
            thread.quitSafely()
            throw IllegalStateException("No usable ${config.codec} encoder", lastError)
        }

        private data class Options(val profile: Boolean, val bitrateMode: Boolean)

        private fun buildFormat(config: VideoEncoderConfig, info: MediaCodecInfo, options: Options): MediaFormat {
            val format = MediaFormat.createVideoFormat(config.codec.mimeType, config.width, config.height)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.iFrameIntervalSec)
            format.setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
            val caps = runCatching { info.getCapabilitiesForType(config.codec.mimeType) }.getOrNull()
            if (options.bitrateMode && caps != null) {
                val encoderCaps = caps.encoderCapabilities
                when {
                    encoderCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) ->
                        format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    encoderCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) ->
                        format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                }
            }
            if (options.profile && caps != null) {
                val wanted = when (config.codec) {
                    VideoCodec.H264 -> MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                    VideoCodec.HEVC -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
                }
                val levels = caps.profileLevels.filter { it.profile == wanted }
                if (levels.isNotEmpty()) {
                    format.setInteger(MediaFormat.KEY_PROFILE, wanted)
                    format.setInteger(MediaFormat.KEY_LEVEL, levels.maxOf { it.level })
                }
            }
            return format
        }
    }

    private class Callback(private val sink: Sink, private val holder: Array<VideoEncoder?>) : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                val buffer = codec.getOutputBuffer(index)
                val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (buffer != null && info.size > 0 && !isConfig) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    sink.onVideoSample(buffer, info)
                }
                codec.releaseOutputBuffer(index, false)
            } catch (e: IllegalStateException) {
                // Codec already stopped during shutdown.
                return
            }
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) holder[0]?.onEos()
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            RgLog.e(TAG, "codec error (transient=${e.isTransient}, recoverable=${e.isRecoverable})", e)
            if (!e.isTransient) {
                sink.onVideoError(e)
                holder[0]?.onEos()
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            sink.onVideoFormat(format)
        }
    }
}
