package com.ravango.engine.ai.speech

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Energy-based voice activity segmentation of 16-bit mono PCM into utterances (sample ranges) for recognizers that
 * only return text per request: each utterance becomes one recognition request, so its text can be placed in time.
 * Adaptive threshold = noise floor (10th percentile of frame energy) + [marginDb], capped at [maxThresholdDb].
 */
object UtteranceSegmenter {

    data class Utterance(val startSample: Int, val endSample: Int)

    fun segment(
        samples: ShortArray,
        sampleRate: Int,
        frameMs: Int = 30,
        marginDb: Float = 9f,
        maxThresholdDb: Float = -38f,
        minSilenceMs: Int = 400,
        minSpeechMs: Int = 250,
        maxUtteranceMs: Int = 15_000,
        padMs: Int = 150,
    ): List<Utterance> {
        val frame = sampleRate * frameMs / 1000
        if (frame <= 0 || samples.size < frame) return emptyList()
        val frames = samples.size / frame
        val db = FloatArray(frames) { f ->
            var sum = 0.0
            val base = f * frame
            for (i in base until base + frame) {
                val v = samples[i] / 32768.0
                sum += v * v
            }
            val rms = sqrt(sum / frame)
            (20 * log10(max(rms, 1e-9))).toFloat()
        }
        val floor = db.sorted()[(frames * 0.1).toInt().coerceIn(0, frames - 1)]
        val threshold = minOf(floor + marginDb, maxThresholdDb).coerceAtLeast(floor + 3f)
        val voiced = BooleanArray(frames) { db[it] > threshold }

        val minSilenceFrames = (minSilenceMs / frameMs).coerceAtLeast(1)
        val minSpeechFrames = (minSpeechMs / frameMs).coerceAtLeast(1)
        val maxFrames = (maxUtteranceMs / frameMs).coerceAtLeast(minSpeechFrames + 1)
        val raw = mutableListOf<IntRange>()
        var start = -1
        var silence = 0
        for (f in 0 until frames) {
            if (voiced[f]) {
                if (start < 0) start = f
                silence = 0
            } else if (start >= 0) {
                silence++
                if (silence >= minSilenceFrames) {
                    raw += start until (f - silence + 1)
                    start = -1
                    silence = 0
                }
            }
        }
        if (start >= 0) raw += start until (frames - silence)

        // Split overly long utterances at their quietest frame so each request stays short.
        val split = ArrayDeque(raw.filter { it.last - it.first + 1 >= minSpeechFrames })
        val result = mutableListOf<IntRange>()
        while (split.isNotEmpty()) {
            val r = split.removeFirst()
            val len = r.last - r.first + 1
            if (len <= maxFrames) {
                result += r
                continue
            }
            val searchFrom = r.first + len / 3
            val searchTo = r.last - len / 3
            val cut = (searchFrom..searchTo).minByOrNull { db[it] } ?: (r.first + len / 2)
            split.addFirst((cut + 1)..r.last)
            split.addFirst(r.first..cut)
        }
        val pad = sampleRate * padMs / 1000
        return result.map { r ->
            Utterance(
                startSample = (r.first * frame - pad).coerceAtLeast(0),
                endSample = ((r.last + 1) * frame + pad).coerceAtMost(samples.size),
            )
        }
    }
}
