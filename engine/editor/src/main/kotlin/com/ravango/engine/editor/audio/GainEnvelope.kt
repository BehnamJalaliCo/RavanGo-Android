package com.ravango.engine.editor.audio

/**
 * Time-varying gain for one media item: static volume × fade in × fade out × ducking.
 *
 * Times are relative to the start of the item's *output* (after speed changes), in microseconds.
 * Ducking ranges are where speech is present on the main track; the gain ramps smoothly to [duckGain] around them.
 */
class GainEnvelope(
    val volume: Float,
    val fadeInUs: Long = 0,
    val fadeOutUs: Long = 0,
    /** Output duration of the item; required for fade-out. <= 0 disables fade-out. */
    val durationUs: Long = 0,
    duckRanges: List<LongRange> = emptyList(),
    val duckGain: Float = DEFAULT_DUCK_GAIN,
    val duckRampUs: Long = 180_000,
) {
    private val ducks: List<LongRange> = mergeRanges(duckRanges)
    private val duckStarts = LongArray(ducks.size) { ducks[it].first }

    /** True when the envelope never changes the signal. */
    val isUnity: Boolean get() = volume == 1f && fadeInUs <= 0 && (fadeOutUs <= 0 || durationUs <= 0) && ducks.isEmpty()

    fun gainAt(timeUs: Long): Float {
        var g = volume
        if (g == 0f) return 0f
        if (fadeInUs > 0 && timeUs < fadeInUs) g *= curve(timeUs.toFloat() / fadeInUs)
        if (fadeOutUs > 0 && durationUs > 0) {
            val remaining = durationUs - timeUs
            if (remaining < fadeOutUs) g *= curve(remaining.toFloat() / fadeOutUs)
        }
        if (ducks.isNotEmpty()) g *= 1f - (1f - duckGain) * duckAmount(timeUs)
        return g
    }

    /** 0 = no ducking, 1 = fully ducked. */
    fun duckAmount(timeUs: Long): Float {
        // Nearest range starting before t + ramp.
        var idx = duckStarts.binarySearch(timeUs + duckRampUs)
        if (idx < 0) idx = -idx - 2
        var best = 0f
        var i = idx
        while (i >= 0) {
            val r = ducks[i]
            val a = when {
                timeUs in r.first..r.last -> 1f
                timeUs < r.first -> 1f - (r.first - timeUs).toFloat() / duckRampUs
                else -> 1f - (timeUs - r.last).toFloat() / duckRampUs
            }.coerceIn(0f, 1f)
            if (a > best) best = a
            if (best >= 1f || r.last < timeUs - duckRampUs * 4) break
            i--
        }
        return best
    }

    /** Perceptually smooth fade curve (equal-power-like). */
    private fun curve(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    companion object {
        /** About -10 dB. */
        const val DEFAULT_DUCK_GAIN = 0.32f

        /** Sorts and merges overlapping/adjacent ranges. */
        fun mergeRanges(ranges: List<LongRange>): List<LongRange> {
            val sorted = ranges.filter { it.last > it.first }.sortedBy { it.first }
            val out = ArrayList<LongRange>(sorted.size)
            for (r in sorted) {
                val last = out.lastOrNull()
                if (last != null && r.first <= last.last) out[out.lastIndex] = last.first..maxOf(last.last, r.last) else out += r
            }
            return out
        }
    }
}
