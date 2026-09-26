package com.ravango.engine.camera.session

import kotlin.math.ln
import kotlin.math.pow

/**
 * Converts a color temperature to Camera2 RGGB color-correction gains (MANUAL_POST_PROCESSING devices).
 *
 * The illuminant color is approximated with Tanner Helland's black-body fit; the gains neutralize it. Raw Bayer
 * data is roughly twice as sensitive in green, so red/blue gains are scaled by 2 relative to green — the same
 * approximation used by widely deployed open-source camera apps. Pure, unit tested.
 */
object WhiteBalanceMath {
    const val MIN_KELVIN = 2000
    const val MAX_KELVIN = 10000
    private const val MAX_GAIN = 8f

    /** Illuminant color for [kelvin] as normalized (r, g, b) in 0..1. */
    fun kelvinToRgb(kelvin: Int): FloatArray {
        val t = kelvin.coerceIn(1000, 40000) / 100.0
        val r = if (t <= 66) 255.0 else 329.698727446 * (t - 60).pow(-0.1332047592)
        val g = if (t <= 66) 99.4708025861 * ln(t) - 161.1195681661 else 288.1221695283 * (t - 60).pow(-0.0755148492)
        val b = when {
            t >= 66 -> 255.0
            t <= 19 -> 0.0
            else -> 138.5177312231 * ln(t - 10) - 305.0447927307
        }
        return floatArrayOf(
            (r.coerceIn(0.0, 255.0) / 255.0).toFloat(),
            (g.coerceIn(0.0, 255.0) / 255.0).toFloat(),
            (b.coerceIn(0.0, 255.0) / 255.0).toFloat(),
        )
    }

    /** RGGB gains [R, G_even, G_odd, B] with green normalized to 1. */
    fun rggbGains(kelvin: Int): FloatArray {
        val rgb = kelvinToRgb(kelvin.coerceIn(MIN_KELVIN, MAX_KELVIN))
        val green = rgb[1].coerceAtLeast(1e-3f)
        val red = (2f * green / rgb[0].coerceAtLeast(1e-3f)).coerceIn(0.5f, MAX_GAIN)
        val blue = (2f * green / rgb[2].coerceAtLeast(1e-3f)).coerceIn(0.5f, MAX_GAIN)
        return floatArrayOf(red, 1f, 1f, blue)
    }
}
