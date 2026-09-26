package com.ravango.engine.beauty.effects

import kotlin.math.abs

/**
 * Downscales a person-confidence mask by an integer [factor] (box filter) and smooths it over time with an
 * adaptive exponential moving average: small changes (segmentation flicker) are averaged heavily, large ones
 * (real motion) pass through almost immediately, so edges are calm without trailing behind a moving person.
 * Pure Kotlin; buffers are reused between updates.
 */
class MaskSmoother(
    private val minAlpha: Float = 0.3f,
    private val motionGain: Float = 1.8f,
) {
    var width = 0; private set
    var height = 0; private set
    private var state = FloatArray(0)
    private var primed = false

    fun reset() { primed = false }

    /**
     * Integrates [src] ([srcW] × [srcH] confidences 0..1, row-major) downscaled by [factor] and writes the smoothed
     * mask as bytes into [out] (size ≥ width × height after the call). Returns the output size as width × height.
     */
    fun update(src: FloatArray, srcW: Int, srcH: Int, factor: Int, out: ByteArray): Int {
        val f = factor.coerceAtLeast(1)
        val w = (srcW / f).coerceAtLeast(1)
        val h = (srcH / f).coerceAtLeast(1)
        if (w != width || h != height || state.size != w * h) {
            width = w; height = h
            state = FloatArray(w * h)
            primed = false
        }
        val norm = 1f / (f * f)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f
                val sy = y * f
                val sx = x * f
                for (dy in 0 until f) {
                    val row = (sy + dy).coerceAtMost(srcH - 1) * srcW
                    for (dx in 0 until f) sum += src[row + (sx + dx).coerceAtMost(srcW - 1)]
                }
                val n = (sum * norm).coerceIn(0f, 1f)
                val i = y * w + x
                val s = if (!primed) n else {
                    val prev = state[i]
                    val alpha = (minAlpha + motionGain * abs(n - prev)).coerceIn(minAlpha, 1f)
                    prev + (n - prev) * alpha
                }
                state[i] = s
                out[i] = (s * 255f + 0.5f).toInt().toByte()
            }
        }
        primed = true
        return w * h
    }
}
