package com.ravango.core.media.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Normalized biquad coefficients (a0 = 1), designed with the RBJ "Audio EQ Cookbook" formulas.
 * Transfer function: H(z) = (b0 + b1·z⁻¹ + b2·z⁻²) / (1 + a1·z⁻¹ + a2·z⁻²).
 */
class BiquadCoefficients(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a1: Double,
    val a2: Double,
) {
    /** Magnitude response in dB at [frequencyHz] for a filter running at [sampleRate]. */
    fun magnitudeDb(frequencyHz: Double, sampleRate: Int): Double {
        val w = 2.0 * PI * frequencyHz / sampleRate
        val c1 = cos(w); val s1 = sin(w)
        val c2 = cos(2 * w); val s2 = sin(2 * w)
        val nr = b0 + b1 * c1 + b2 * c2
        val ni = -(b1 * s1 + b2 * s2)
        val dr = 1.0 + a1 * c1 + a2 * c2
        val di = -(a1 * s1 + a2 * s2)
        return 20.0 * log10(hypot(nr, ni) / hypot(dr, di))
    }

    companion object {
        val Identity = BiquadCoefficients(1.0, 0.0, 0.0, 0.0, 0.0)

        fun highPass(sampleRate: Int, frequencyHz: Double, q: Double = 0.7071): BiquadCoefficients {
            val w0 = 2.0 * PI * frequencyHz / sampleRate
            val cw = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha
            return BiquadCoefficients(
                b0 = (1.0 + cw) / 2.0 / a0,
                b1 = -(1.0 + cw) / a0,
                b2 = (1.0 + cw) / 2.0 / a0,
                a1 = -2.0 * cw / a0,
                a2 = (1.0 - alpha) / a0,
            )
        }

        fun peaking(sampleRate: Int, frequencyHz: Double, gainDb: Double, q: Double = 1.0): BiquadCoefficients {
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * frequencyHz / sampleRate
            val cw = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha / a
            return BiquadCoefficients(
                b0 = (1.0 + alpha * a) / a0,
                b1 = -2.0 * cw / a0,
                b2 = (1.0 - alpha * a) / a0,
                a1 = -2.0 * cw / a0,
                a2 = (1.0 - alpha / a) / a0,
            )
        }

        /** Low shelf with shelf slope S (1 = steepest without overshoot). */
        fun lowShelf(sampleRate: Int, frequencyHz: Double, gainDb: Double, slope: Double = 1.0): BiquadCoefficients {
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * frequencyHz / sampleRate
            val cw = cos(w0)
            val alpha = sin(w0) / 2.0 * sqrt((a + 1.0 / a) * (1.0 / slope - 1.0) + 2.0)
            val sa = 2.0 * sqrt(a) * alpha
            val a0 = (a + 1) + (a - 1) * cw + sa
            return BiquadCoefficients(
                b0 = a * ((a + 1) - (a - 1) * cw + sa) / a0,
                b1 = 2 * a * ((a - 1) - (a + 1) * cw) / a0,
                b2 = a * ((a + 1) - (a - 1) * cw - sa) / a0,
                a1 = -2 * ((a - 1) + (a + 1) * cw) / a0,
                a2 = ((a + 1) + (a - 1) * cw - sa) / a0,
            )
        }
    }
}

/**
 * A biquad section with independent state for up to [channels] interleaved channels (transposed direct form II,
 * double-precision state so low-frequency filters stay accurate at 48 kHz). Allocation-free.
 */
class Biquad(coefficients: BiquadCoefficients, private val channels: Int) {
    private var b0 = 0.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private val z1 = DoubleArray(channels)
    private val z2 = DoubleArray(channels)

    init { setCoefficients(coefficients) }

    fun setCoefficients(c: BiquadCoefficients) {
        b0 = c.b0; b1 = c.b1; b2 = c.b2; a1 = c.a1; a2 = c.a2
    }

    /** Filters one sample of [channel]. */
    fun process(x: Float, channel: Int): Float {
        val xd = x.toDouble()
        val y = b0 * xd + z1[channel]
        z1[channel] = b1 * xd - a1 * y + z2[channel]
        z2[channel] = b2 * xd - a2 * y
        return y.toFloat()
    }

    fun reset() {
        z1.fill(0.0)
        z2.fill(0.0)
    }

    /** Flushes denormal-range state (called once per block; cheap). */
    fun sanitize() {
        for (c in 0 until channels) {
            if (z1[c] > -1e-20 && z1[c] < 1e-20) z1[c] = 0.0
            if (z2[c] > -1e-20 && z2[c] < 1e-20) z2[c] = 0.0
        }
    }
}
