package com.ravango.engine.ai.video

import com.ravango.core.media.dsp.TimeRange

/**
 * On-device signals for highlight detection: loudness energy (mean level above the recording's median), loudness
 * peaks, and speaking pace (words/s vs. the median). Candidates come from transcript units grouped into
 * 6–20 s windows, or from a sliding window over the loudness envelope when there is no transcript.
 */
object HighlightScorer {

    data class Candidate(
        val range: TimeRange,
        val firstUnit: Int = -1,
        val lastUnit: Int = -1,
        val text: String? = null,
        val energy: Float = 0f,
        val peak: Float = 0f,
        val pace: Float = 0f,
        var llm: Float? = null,
        var llmTitle: String? = null,
        var llmReason: String? = null,
    ) {
        val score: Float
            get() = llm?.let { 0.5f * it + 0.3f * energy + 0.1f * peak + 0.1f * pace } ?: (0.55f * energy + 0.2f * peak + 0.25f * pace)
    }

    const val MIN_WINDOW_US = 6_000_000L
    const val MAX_WINDOW_US = 20_000_000L

    fun fromUnits(units: List<TranscriptUnit>, envelopeDb: FloatArray, windowMs: Int): List<Candidate> {
        if (units.isEmpty()) return emptyList()
        val median = median(envelopeDb)
        val raw = mutableListOf<Candidate>()
        for (i in units.indices) {
            var j = i
            while (j + 1 < units.size && units[j].endUs - units[i].startUs < MIN_WINDOW_US && units[j + 1].endUs - units[i].startUs <= MAX_WINDOW_US) j++
            val range = TimeRange(units[i].startUs, units[j].endUs)
            if (range.durationUs < 2_000_000) continue
            val words = (i..j).sumOf { units[it].words.size }
            val pace = words / (range.durationUs / 1_000_000.0)
            val (energy, peak) = acoustic(envelopeDb, windowMs, range, median)
            raw += Candidate(range, i, j, (i..j).joinToString(" ") { units[it].text }, energy, peak, pace.toFloat())
        }
        val medianPace = median(raw.map { it.pace }.toFloatArray()).coerceAtLeast(0.1f)
        return raw.map { it.copy(pace = ((it.pace / medianPace - 0.8f) / 0.6f).coerceIn(0f, 1f)) }
    }

    fun fromEnvelope(envelopeDb: FloatArray, windowMs: Int, windowUs: Long = 8_000_000, hopUs: Long = 2_000_000): List<Candidate> {
        val totalUs = envelopeDb.size.toLong() * windowMs * 1000
        if (totalUs < windowUs / 2) return emptyList()
        val median = median(envelopeDb)
        val out = mutableListOf<Candidate>()
        var t = 0L
        while (t + windowUs / 2 <= totalUs) {
            val range = TimeRange(t, minOf(t + windowUs, totalUs))
            val (energy, peak) = acoustic(envelopeDb, windowMs, range, median)
            out += Candidate(range, energy = energy, peak = peak)
            t += hopUs
        }
        return out
    }

    /** Greedy non-overlapping selection by score. */
    fun select(candidates: List<Candidate>, maxResults: Int): List<Candidate> {
        val chosen = mutableListOf<Candidate>()
        for (c in candidates.sortedByDescending { it.score }) {
            if (chosen.size >= maxResults) break
            if (chosen.none { it.range.startUs < c.range.endUs && c.range.startUs < it.range.endUs }) chosen += c
        }
        return chosen
    }

    /** (energy, peak) in 0..1: mean and max level above the median, scaled so +12 dB ≈ 1. */
    internal fun acoustic(envelopeDb: FloatArray, windowMs: Int, range: TimeRange, median: Float): Pair<Float, Float> {
        if (envelopeDb.isEmpty() || windowMs <= 0) return 0f to 0f
        val from = (range.startUs / 1000 / windowMs).toInt().coerceIn(0, envelopeDb.size - 1)
        val to = (range.endUs / 1000 / windowMs).toInt().coerceIn(from + 1, envelopeDb.size)
        var sum = 0.0
        var max = -200f
        for (i in from until to) {
            sum += envelopeDb[i]
            if (envelopeDb[i] > max) max = envelopeDb[i]
        }
        val mean = (sum / (to - from)).toFloat()
        return ((mean - median) / 12f).coerceIn(0f, 1f) to ((max - median) / 18f).coerceIn(0f, 1f)
    }

    internal fun median(values: FloatArray): Float {
        val finite = values.filter { it.isFinite() && it > -120f }
        if (finite.isEmpty()) return -60f
        val sorted = finite.sorted()
        return sorted[sorted.size / 2]
    }
}
