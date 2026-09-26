package com.ravango.engine.editor.audio

import android.util.LruCache
import com.ravango.core.common.log.RgLog
import com.ravango.core.media.PcmDecoder
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Energy-based voice activity detection used for music ducking. Returns speech ranges in source time.
 * Results are cached per URI for the process lifetime (sources are immutable files).
 */
@Singleton
class SpeechActivityAnalyzer @Inject constructor(private val decoder: PcmDecoder) {
    private val cache = LruCache<String, List<LongRange>>(32)

    suspend fun speechRanges(uri: String): List<LongRange> {
        cache.get(uri)?.let { return it }
        val levels = ArrayList<Float>(4096)
        var windowUs = 0L
        try {
            var acc = 0.0
            var count = 0
            var windowSamples = 0
            decoder.decode(uri) { block ->
                if (windowSamples == 0) {
                    windowSamples = (block.sampleRate * WINDOW_MS / 1000).coerceAtLeast(1)
                    windowUs = WINDOW_MS * 1000L
                }
                for (i in 0 until block.count) {
                    val s = block.samples[i]
                    acc += s * s
                    if (++count == windowSamples) {
                        levels += toDb(sqrt(acc / count))
                        acc = 0.0; count = 0
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RgLog.w(TAG, "Speech analysis failed for $uri", e)
            return emptyList()
        }
        val ranges = detect(levels.toFloatArray(), windowUs.coerceAtLeast(1))
        cache.put(uri, ranges)
        return ranges
    }

    companion object {
        private const val TAG = "SpeechVad"
        const val WINDOW_MS = 30

        private fun toDb(rms: Double): Float = if (rms <= 1e-9) -120f else (20 * log10(rms)).toFloat()

        /** Pure detection over per-window dBFS levels; exposed for tests. */
        fun detect(levelsDb: FloatArray, windowUs: Long, minSpeechUs: Long = 150_000, hangoverUs: Long = 300_000, mergeGapUs: Long = 250_000): List<LongRange> {
            if (levelsDb.isEmpty()) return emptyList()
            val sorted = levelsDb.sortedArray()
            val floor = sorted[(sorted.size * 0.1).toInt().coerceIn(0, sorted.lastIndex)]
            val threshold = maxOf(floor + 10f, -50f)
            val raw = ArrayList<LongRange>()
            var start = -1
            for (i in levelsDb.indices) {
                val speech = levelsDb[i] > threshold
                if (speech && start < 0) start = i
                if (!speech && start >= 0) {
                    raw += (start * windowUs)..(i * windowUs); start = -1
                }
            }
            if (start >= 0) raw += (start * windowUs)..(levelsDb.size * windowUs)
            // Hangover + merge short gaps, then drop blips.
            val merged = ArrayList<LongRange>()
            for (r in raw) {
                val extended = r.first..(r.last + hangoverUs)
                val last = merged.lastOrNull()
                if (last != null && extended.first - last.last <= mergeGapUs) merged[merged.lastIndex] = last.first..maxOf(last.last, extended.last)
                else merged += extended
            }
            return merged.filter { it.last - it.first >= minSpeechUs + hangoverUs }
        }
    }
}
