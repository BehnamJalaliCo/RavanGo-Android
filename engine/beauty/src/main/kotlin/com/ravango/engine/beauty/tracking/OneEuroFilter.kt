package com.ravango.engine.beauty.tracking

import kotlin.math.PI
import kotlin.math.abs

/**
 * One Euro filter (Casiez et al., CHI 2012): an adaptive low-pass filter whose cutoff rises with speed, so it
 * removes jitter when still and lag when moving fast.
 *
 * @param minCutoff cutoff (Hz) at zero speed — lower = smoother when still.
 * @param beta speed coefficient — higher = less lag when moving.
 * @param dCutoff cutoff (Hz) used to smooth the derivative.
 */
class OneEuroFilter(
    var minCutoff: Float = 1.5f,
    var beta: Float = 8f,
    var dCutoff: Float = 1f,
) {
    private var initialized = false
    private var xPrev = 0f
    private var rawPrev = 0f
    private var dxPrev = 0f
    private var tPrevSec = 0.0

    /** Filtered value from the last [filter] call. */
    val value: Float get() = xPrev

    /** Filtered derivative (units per second) from the last [filter] call. */
    val velocity: Float get() = dxPrev

    val isInitialized: Boolean get() = initialized

    fun reset() {
        initialized = false
        dxPrev = 0f
    }

    /** Filters [x] measured at [timestampSec]. Non-increasing timestamps return the previous estimate. */
    fun filter(x: Float, timestampSec: Double): Float {
        if (!initialized) {
            initialized = true
            xPrev = x
            rawPrev = x
            dxPrev = 0f
            tPrevSec = timestampSec
            return x
        }
        val dt = (timestampSec - tPrevSec).toFloat()
        if (dt <= 1e-6f) return xPrev
        tPrevSec = timestampSec
        // Derivative of the raw signal (not of the lagging estimate) so [velocity] is unbiased for prediction.
        val dx = (x - rawPrev) / dt
        rawPrev = x
        val edx = lowPass(dxPrev, dx, alpha(dt, dCutoff))
        dxPrev = edx
        val cutoff = minCutoff + beta * abs(edx)
        xPrev = lowPass(xPrev, x, alpha(dt, cutoff))
        return xPrev
    }

    companion object {
        internal fun alpha(dt: Float, cutoff: Float): Float {
            val tau = 1f / (2f * PI.toFloat() * cutoff)
            return 1f / (1f + tau / dt)
        }

        private fun lowPass(prev: Float, x: Float, a: Float): Float = prev + a * (x - prev)
    }
}
