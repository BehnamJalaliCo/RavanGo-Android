package com.ravango.engine.beauty.tracking

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Stabilizes face contour points with one [OneEuroFilter] per coordinate and extrapolates them to the current
 * frame time using the filtered velocity (latency compensation: detection runs asynchronously and its result
 * describes a frame from the past).
 *
 * Filter responsiveness is normalized by face size so near and far faces feel equally stable.
 */
class LandmarkStabilizer(
    /** Speed coefficient in "face sizes per second" units. */
    private val betaFace: Float = 4f,
    private val minCutoff: Float = 1.4f,
    /** Maximum extrapolation horizon. */
    private val maxLeadSec: Float = 0.1f,
    /** Fraction of the extrapolated motion applied (partial prediction avoids overshoot on sudden stops). */
    private val predictionGain: Float = 0.75f,
) {
    private val n = Contour.TOTAL_POINTS * 2
    private val filters = Array(n) { OneEuroFilter(minCutoff = minCutoff, beta = 1f, dCutoff = 1f) }
    private var lastMeasurementSec = 0.0
    var hasEstimate: Boolean = false
        private set

    fun reset() {
        filters.forEach { it.reset() }
        hasEstimate = false
    }

    /**
     * Feeds a new measurement taken at [timestampSec]. [faceScale] is a size measure of the face in frame units
     * (e.g. inter-ocular distance). A jump larger than ~1.5 face sizes (a different face) resets the filters.
     */
    fun onMeasurement(measured: FaceLandmarks, timestampSec: Double, faceScale: Float) {
        val scale = max(faceScale, 0.02f)
        if (hasEstimate && meanDistanceTo(measured) > scale * 1.5f) reset()
        val beta = betaFace / scale
        val p = measured.points
        for (i in 0 until n) {
            val f = filters[i]
            f.beta = beta
            f.filter(p[i], timestampSec)
        }
        lastMeasurementSec = timestampSec
        hasEstimate = true
    }

    /** Writes the stabilized landmarks, extrapolated to [nowSec], into [out]. */
    fun predict(nowSec: Double, out: FaceLandmarks) {
        val lead = ((nowSec - lastMeasurementSec).toFloat()).coerceIn(0f, maxLeadSec) * predictionGain
        val o = out.points
        for (i in 0 until n) {
            val f = filters[i]
            o[i] = f.value + f.velocity * lead
        }
    }

    private fun meanDistanceTo(measured: FaceLandmarks): Float {
        val p = measured.points
        var sum = 0f
        var i = 0
        while (i < n) {
            val dx = p[i] - filters[i].value
            val dy = p[i + 1] - filters[i + 1].value
            sum += sqrt(dx * dx + dy * dy)
            i += 2
        }
        return sum / (n / 2)
    }
}

/**
 * Smooth 0..1 confidence used to fade landmark-dependent effects in and out instead of popping.
 * Ramps linearly over [rampSec] toward the target.
 */
class PresenceFader(private val rampSec: Float = 0.15f) {
    var value: Float = 0f
        private set

    fun update(visible: Boolean, dtSec: Float): Float {
        val step = if (rampSec <= 0f) 1f else (dtSec / rampSec).coerceIn(0f, 1f)
        value = if (visible) (value + step).coerceAtMost(1f) else (value - step).coerceAtLeast(0f)
        return value
    }

    fun reset() { value = 0f }
}
