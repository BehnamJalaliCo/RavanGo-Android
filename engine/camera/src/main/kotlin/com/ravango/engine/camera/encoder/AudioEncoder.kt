package com.ravango.engine.camera.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.ravango.core.common.log.RgLog
import com.ravango.engine.audio.PcmFormat
import com.ravango.engine.audio.PcmSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * AAC-LC encoder fed by the audio engine through [PcmSink]. PCM is copied into pooled chunks on the audio thread
 * (never blocking it) and encoded on this encoder's own thread.
 *
 * [mapPtsUs] converts capture timestamps (System.nanoTime) to recording time in µs, or -1 to drop the samples
 * (before the first video frame, or while paused).
 */
internal class AudioEncoder(
    private val bitrate: Int,
    private val sink: Sink,
    private val mapPtsUs: (Long) -> Long,
) : PcmSink {

    interface Sink {
        fun onAudioFormat(format: MediaFormat)
        fun onAudioSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo)
        fun onAudioError(error: Exception)
        fun onAudioFormatMismatch()
    }

    private class Chunk(var data: ShortArray, var length: Int = 0, var ptsUs: Long = 0, var eos: Boolean = false)

    private val free = ArrayBlockingQueue<Chunk>(POOL_SIZE)
    private val filled = ArrayBlockingQueue<Chunk>(POOL_SIZE + 1)
    private val info = MediaCodec.BufferInfo()

    @Volatile private var format: PcmFormat? = null
    @Volatile private var accepting = true
    @Volatile private var mismatchReported = false
    private var codec: MediaCodec? = null
    private var worker: Thread? = null
    private var overflowCount = 0L

    init {
        repeat(POOL_SIZE) { free.offer(Chunk(ShortArray(4096))) }
    }

    override fun onFormat(format: PcmFormat) {
        val current = this.format
        if (current == null) {
            this.format = format
            startCodec(format)
        } else if (current != format && !mismatchReported) {
            // AAC parameters cannot change mid-track; keep the file valid and tell the user.
            mismatchReported = true
            sink.onAudioFormatMismatch()
        }
    }

    override fun onPcm(samples: ShortArray, length: Int, presentationTimeNs: Long) {
        if (!accepting || mismatchReported || codec == null || length <= 0) return
        val pts = mapPtsUs(presentationTimeNs)
        if (pts < 0) return
        val chunk = free.poll()
        if (chunk == null) {
            overflowCount++
            if (overflowCount % 50 == 1L) RgLog.w(TAG, "audio encoder behind; dropped $overflowCount chunks")
            return
        }
        if (chunk.data.size < length) chunk.data = ShortArray(length)
        System.arraycopy(samples, 0, chunk.data, 0, length)
        chunk.length = length
        chunk.ptsUs = pts
        chunk.eos = false
        filled.offer(chunk)
    }

    /** Stops accepting PCM, drains the encoder and releases it. Blocking (bounded). */
    fun finish(timeoutMs: Long = 3_000) {
        accepting = false
        val w = worker ?: return
        filled.offer(Chunk(ShortArray(0), eos = true))
        w.join(timeoutMs)
        if (w.isAlive) {
            RgLog.w(TAG, "audio encoder drain timed out")
            w.interrupt()
            w.join(500)
        }
    }

    private fun startCodec(format: PcmFormat) {
        try {
            val mediaFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, format.sampleRate, format.channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            c.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            codec = c
            worker = Thread({ loop(c, format) }, "RgAudioEnc").apply {
                priority = Thread.MAX_PRIORITY - 1
                start()
            }
        } catch (e: Exception) {
            RgLog.e(TAG, "AAC encoder start failed", e)
            sink.onAudioError(e)
        }
    }

    private fun loop(c: MediaCodec, format: PcmFormat) {
        try {
            while (true) {
                val chunk = filled.poll(20, TimeUnit.MILLISECONDS)
                if (chunk == null) {
                    drain(c, false)
                    continue
                }
                if (chunk.eos) {
                    queueEos(c)
                    drain(c, true)
                    break
                }
                feed(c, chunk, format)
                free.offer(chunk)
                drain(c, false)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            RgLog.e(TAG, "audio encoding failed", e)
            sink.onAudioError(e)
        } finally {
            runCatching { c.stop() }
            runCatching { c.release() }
            codec = null
        }
    }

    private fun feed(c: MediaCodec, chunk: Chunk, format: PcmFormat) {
        var offsetShorts = 0
        var attempts = 0
        while (offsetShorts < chunk.length) {
            val index = c.dequeueInputBuffer(10_000)
            if (index < 0) {
                drain(c, false)
                if (++attempts > 50) return // encoder stuck: drop this chunk rather than blocking forever
                continue
            }
            val buffer = c.getInputBuffer(index) ?: continue
            buffer.clear()
            buffer.order(ByteOrder.nativeOrder())
            val capacityShorts = buffer.remaining() / 2
            val framesFit = capacityShorts / format.channels
            val count = minOf(chunk.length - offsetShorts, framesFit * format.channels)
            buffer.asShortBuffer().put(chunk.data, offsetShorts, count)
            val framesBefore = offsetShorts / format.channels
            val pts = chunk.ptsUs + framesBefore * 1_000_000L / format.sampleRate
            c.queueInputBuffer(index, 0, count * 2, pts, 0)
            offsetShorts += count
            if (count == 0) return
        }
    }

    private fun queueEos(c: MediaCodec) {
        repeat(50) {
            val index = c.dequeueInputBuffer(10_000)
            if (index >= 0) {
                c.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                return
            }
            drain(c, false)
        }
    }

    private fun drain(c: MediaCodec, untilEos: Boolean) {
        var idleLoops = 0
        while (true) {
            val index = c.dequeueOutputBuffer(info, if (untilEos) 10_000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!untilEos || ++idleLoops > 200) return
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> sink.onAudioFormat(c.outputFormat)
                index >= 0 -> {
                    val buffer = c.getOutputBuffer(index)
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buffer != null && info.size > 0 && !isConfig) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        sink.onAudioSample(buffer, info)
                    }
                    c.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private companion object {
        const val TAG = "AudioEncoder"
        const val POOL_SIZE = 48
    }
}
