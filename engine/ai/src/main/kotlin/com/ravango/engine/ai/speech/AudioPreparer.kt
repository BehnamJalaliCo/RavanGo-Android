package com.ravango.engine.ai.speech

import android.content.Context
import com.ravango.core.common.di.IoDispatcher
import com.ravango.core.common.result.AppException
import com.ravango.core.common.result.ErrorKind
import com.ravango.core.media.PcmDecoder
import com.ravango.engine.ai.api.AiErrors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A prepared piece of audio: 16 kHz mono 16-bit WAV covering [startUs, startUs + durationUs) of the source. */
data class AudioChunk(val file: File, val startUs: Long, val durationUs: Long, val index: Int)

/**
 * Decodes a media file's audio (any container/codec MediaCodec supports), downmixes to mono, resamples to 16 kHz and
 * writes WAV chunks of at most [MAX_CHUNK_SEC] seconds (≈ 19 MB each, under the 24 MB upload limit of
 * Whisper-compatible APIs) with [OVERLAP_SEC] seconds of overlap so words on a boundary are not lost.
 */
@Singleton
class AudioPreparer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val decoder: PcmDecoder,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    class Prepared(val chunks: List<AudioChunk>, val totalDurationUs: Long, val directory: File) : AutoCloseable {
        override fun close() {
            directory.deleteRecursively()
        }
    }

    suspend fun prepare(
        uri: String,
        startUs: Long,
        endUs: Long,
        maxChunkSec: Int = MAX_CHUNK_SEC,
        onProgress: (Float) -> Unit = {},
    ): Prepared = withContext(io) {
        val dir = File(context.cacheDir, "ai-audio/${System.nanoTime()}").apply { mkdirs() }
        val chunkSamples = maxChunkSec.toLong() * TARGET_RATE
        val overlapSamples = (OVERLAP_SEC * TARGET_RATE).toInt()
        val chunks = mutableListOf<AudioChunk>()
        val tail = FloatArray(overlapSamples)
        var tailCount = 0
        var writer: WavWriter? = null
        var writerStartSample = 0L
        var totalOut = 0L
        var resampler: Resampler? = null
        val rangeStart = startUs.coerceAtLeast(0)
        val expectedUs = if (endUs == Long.MAX_VALUE) -1L else (endUs - rangeStart)

        fun openChunk() {
            val index = chunks.size + (if (writer != null) 1 else 0)
            val w = WavWriter(File(dir, "chunk_$index.wav"), TARGET_RATE)
            writerStartSample = totalOut - tailCount
            if (tailCount > 0) w.write(tail, 0, tailCount)
            writer = w
        }

        fun closeChunk() {
            val w = writer ?: return
            w.close()
            chunks += AudioChunk(
                file = w.file,
                startUs = rangeStart + writerStartSample * 1_000_000 / TARGET_RATE,
                durationUs = w.samplesWritten * 1_000_000 / TARGET_RATE,
                index = chunks.size,
            )
            writer = null
        }

        fun rememberTail(samples: FloatArray, from: Int, to: Int) {
            val n = to - from
            if (n >= overlapSamples) {
                System.arraycopy(samples, to - overlapSamples, tail, 0, overlapSamples)
                tailCount = overlapSamples
            } else {
                val keep = (overlapSamples - n).coerceAtMost(tailCount)
                System.arraycopy(tail, tailCount - keep, tail, 0, keep)
                System.arraycopy(samples, from, tail, keep, n)
                tailCount = keep + n
            }
        }

        fun emit(out: FloatArray) {
            var offset = 0
            while (offset < out.size) {
                if (writer == null) openChunk()
                val w = writer!!
                val room = (chunkSamples - w.samplesWritten).toInt().coerceAtLeast(0)
                val n = minOf(room, out.size - offset)
                w.write(out, offset, offset + n)
                rememberTail(out, offset, offset + n)
                offset += n
                totalOut += n
                if (w.samplesWritten >= chunkSamples) closeChunk()
            }
        }

        try {
            val rate = decoder.decode(uri, rangeStart, endUs) { block ->
                val r = resampler ?: Resampler(block.sampleRate, TARGET_RATE).also { resampler = it }
                val usPerSample = 1_000_000.0 / block.sampleRate
                // Trim to the requested range with sample accuracy.
                var from = 0
                var to = block.count
                if (block.startUs < rangeStart) from = ((rangeStart - block.startUs) / usPerSample).toInt().coerceIn(0, block.count)
                if (endUs != Long.MAX_VALUE) {
                    val lastUs = block.startUs + (block.count * usPerSample).toLong()
                    if (lastUs > endUs) to = ((endUs - block.startUs) / usPerSample).toInt().coerceIn(from, block.count)
                }
                if (to > from) {
                    val slice = if (from == 0 && to == block.samples.size) block.samples else block.samples.copyOfRange(from, to)
                    emit(r.process(slice, to - from))
                }
                if (expectedUs > 0) onProgress((totalOut * 1_000_000f / TARGET_RATE / expectedUs).coerceIn(0f, 1f))
            }
            if (rate == 0) throw AppException(ErrorKind.INVALID_INPUT, AiErrors.NO_AUDIO)
            closeChunk()
            // A trailing chunk that is only overlap adds nothing.
            val filtered = chunks.filterIndexed { i, c -> i == 0 || c.durationUs > (OVERLAP_SEC * 1_000_000).toLong() + 200_000 }
            chunks.filterNot { it in filtered }.forEach { it.file.delete() }
            if (filtered.isEmpty() || totalOut == 0L) throw AppException(ErrorKind.INVALID_INPUT, AiErrors.NO_AUDIO)
            Prepared(filtered, totalOut * 1_000_000 / TARGET_RATE, dir)
        } catch (e: Throwable) {
            runCatching { writer?.close() }
            dir.deleteRecursively()
            throw e
        }
    }

    companion object {
        const val TARGET_RATE = 16_000
        const val MAX_CHUNK_SEC = 600
        const val OVERLAP_SEC = 1.5
    }
}
