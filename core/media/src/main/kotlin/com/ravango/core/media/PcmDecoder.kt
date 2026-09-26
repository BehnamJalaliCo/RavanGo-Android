package com.ravango.core.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.ravango.core.common.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** A block of decoded mono PCM samples in [-1, 1]. */
class PcmBlock(val samples: FloatArray, val count: Int, val sampleRate: Int, val startUs: Long)

/**
 * Decodes the audio track of a media file to mono float PCM, streaming blocks to [onBlock].
 * Used for waveforms, silence detection, loudness analysis and speech-to-text upload preparation.
 */
@Singleton
class PcmDecoder @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    /**
     * @param startUs/endUs optional source range.
     * @return the source sample rate, or 0 if the file has no audio track.
     */
    suspend fun decode(uri: String, startUs: Long = 0, endUs: Long = Long.MAX_VALUE, onBlock: (PcmBlock) -> Unit): Int = withContext(io) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            val u = uri.toUri()
            if (u.scheme == null || u.scheme == "file") extractor.setDataSource(u.path!!) else extractor.setDataSource(context, u, null)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return@withContext 0
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec = MediaCodec.createDecoderByType(mime).apply { configure(format, null, null, 0); start() }
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var pcmEncodingFloat = false
            while (!outputDone) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0 || extractor.sampleTime > endUs) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = codec.outputFormat
                        sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmEncodingFloat = out.containsKey(MediaFormat.KEY_PCM_ENCODING) && out.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                    }
                    outIndex >= 0 -> {
                        if (info.size > 0 && info.presentationTimeUs >= startUs - 100_000) {
                            val buf = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            val block = if (pcmEncodingFloat) {
                                val fb = buf.asFloatBuffer()
                                val frames = fb.remaining() / channels
                                val out = FloatArray(frames)
                                for (i in 0 until frames) { var s = 0f; for (c in 0 until channels) s += fb.get(i * channels + c); out[i] = s / channels }
                                out
                            } else {
                                val sb = buf.asShortBuffer()
                                val frames = sb.remaining() / channels
                                val out = FloatArray(frames)
                                for (i in 0 until frames) { var s = 0; for (c in 0 until channels) s += sb.get(i * channels + c); out[i] = s / (32768f * channels) }
                                out
                            }
                            onBlock(PcmBlock(block, block.size, sampleRate, info.presentationTimeUs))
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            sampleRate
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    /**
     * Computes a peak waveform with [buckets] values in 0..1 — for timeline rendering.
     */
    suspend fun waveform(uri: String, buckets: Int, durationUs: Long): FloatArray {
        val result = FloatArray(buckets)
        if (durationUs <= 0) return result
        decode(uri) { block ->
            val usPerSample = 1_000_000.0 / block.sampleRate
            for (i in 0 until block.count) {
                val t = block.startUs + (i * usPerSample).toLong()
                val b = ((t.toDouble() / durationUs) * buckets).toInt().coerceIn(0, buckets - 1)
                val v = kotlin.math.abs(block.samples[i])
                if (v > result[b]) result[b] = v
            }
        }
        return result
    }
}
