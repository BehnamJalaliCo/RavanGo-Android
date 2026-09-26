package com.ravango.engine.beauty.tracking

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Temporal stabilization of a dense landmark mesh (x, y, z per point) — a One Euro filter per coordinate whose
 * cutoff is driven by a *shared* face speed plus the point's own speed:
 *
 * `cutoff_i = minCutoff + beta · max(globalSpeed, localGain · speed_i)`
 *
 * With per-vertex cutoffs only, neighbouring vertices filter differently and a still face "swims"; with one
 * global cutoff a fast local motion (mouth opening, blink) lags. The mix keeps the whole mesh rigidly steady at
 * rest and lets moving parts respond immediately. Speeds are in face sizes per second (normalized by the
 * inter-ocular distance), so near and far faces feel the same.
 *
 * [predict] extrapolates with the filtered velocity to the render time, hiding detection latency; the horizon is
 * capped, which also freezes the mesh at its last good estimate during short dropouts.
 */
class MeshStabilizer(
    val pointCount: Int,
    private val minCutoff: Float = 1.1f,
    private val beta: Float = 4f,
    private val dCutoff: Float = 2f,
    private val localGain: Float = 1f,
    private val maxLeadSec: Float = 0.08f,
    private val predictionGain: Float = 0.8f,
) {
    private val n = pointCount * 3
    /** Filtered coordinates. */
    val value = FloatArray(n)
    /** Filtered velocity (units per second). */
    val velocity = FloatArray(n)
    private val raw = FloatArray(n)
    private val speed = FloatArray(pointCount)
    private var lastSec = 0.0

    var hasEstimate = false; private set

    /** RMS speed of the mesh at the last update, in face sizes per second. */
    var globalSpeed = 0f; private set

    fun reset() {
        hasEstimate = false
        velocity.fill(0f)
        speed.fill(0f)
        globalSpeed = 0f
    }

    /**
     * Feeds a measurement ([measured] = x, y, z per point) taken at [timeSec]. [faceScale] is the face size in the
     * same units (e.g. inter-ocular distance). A jump larger than ~1.2 face sizes (another face) restarts the filter.
     */
    fun update(measured: FloatArray, timeSec: Double, faceScale: Float) {
        val scale = max(faceScale, 0.01f)
        if (hasEstimate && meanDistance(measured) > scale * 1.2f) reset()
        if (!hasEstimate) {
            System.arraycopy(measured, 0, value, 0, n)
            System.arraycopy(measured, 0, raw, 0, n)
            velocity.fill(0f)
            lastSec = timeSec
            hasEstimate = true
            return
        }
        val dt = (timeSec - lastSec).toFloat()
        if (dt <= 1e-5f) return
        lastSec = timeSec
        val ad = OneEuroFilter.alpha(dt, dCutoff)
        val invScale = 1f / scale
        var sum2 = 0f
        for (p in 0 until pointCount) {
            val i = p * 3
            for (c in 0..2) {
                val rv = (measured[i + c] - raw[i + c]) / dt
                velocity[i + c] += ad * (rv - velocity[i + c])
                raw[i + c] = measured[i + c]
            }
            val vx = velocity[i]; val vy = velocity[i + 1]
            val s = sqrt(vx * vx + vy * vy) * invScale
            speed[p] = s
            sum2 += s * s
        }
        val global = sqrt(sum2 / pointCount)
        globalSpeed = global
        for (p in 0 until pointCount) {
            val cutoff = minCutoff + beta * max(global, localGain * speed[p])
            val a = OneEuroFilter.alpha(dt, cutoff)
            val i = p * 3
            value[i] += a * (measured[i] - value[i])
            value[i + 1] += a * (measured[i + 1] - value[i + 1])
            value[i + 2] += a * (measured[i + 2] - value[i + 2])
        }
    }

    /**
     * Writes the stabilized mesh extrapolated to [nowSec] into [out]. Extrapolation is gated by speed: a still face
     * is not extrapolated at all (the residual velocity noise would otherwise re-introduce jitter).
     */
    fun predict(nowSec: Double, out: FloatArray) {
        val lead = ((nowSec - lastSec).toFloat()).coerceIn(0f, maxLeadSec) * predictionGain
        if (lead <= 0f || !hasEstimate) {
            System.arraycopy(value, 0, out, 0, n)
            return
        }
        for (p in 0 until pointCount) {
            val s = max(globalSpeed, speed[p])
            val t = ((s - PREDICT_FROM) / (PREDICT_FULL - PREDICT_FROM)).coerceIn(0f, 1f)
            val k = lead * t * t * (3f - 2f * t)
            val i = p * 3
            out[i] = value[i] + velocity[i] * k
            out[i + 1] = value[i + 1] + velocity[i + 1] * k
            out[i + 2] = value[i + 2] + velocity[i + 2] * k
        }
    }

    private companion object {
        /** Speeds (face sizes per second) between which prediction fades in. */
        const val PREDICT_FROM = 0.25f
        const val PREDICT_FULL = 0.9f
    }

    private fun meanDistance(measured: FloatArray): Float {
        var sum = 0f
        for (p in 0 until pointCount) {
            val dx = measured[p * 3] - value[p * 3]
            val dy = measured[p * 3 + 1] - value[p * 3 + 1]
            sum += sqrt(dx * dx + dy * dy)
        }
        return sum / pointCount
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
