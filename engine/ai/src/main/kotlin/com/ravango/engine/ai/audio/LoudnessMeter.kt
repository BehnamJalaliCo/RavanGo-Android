package com.ravango.engine.ai.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/**
 * Integrated loudness in the spirit of ITU-R BS.1770 for mono audio: K-weighting (high-shelf pre-filter + RLB
 * high-pass), 400 ms blocks with 75% overlap, absolute gate at −70 LUFS and relative gate at −10 LU. Streaming — feed
 * blocks with [add], read [integratedLufs]. Also tracks sample peak.
 */
class LoudnessMeter(private val sampleRate: Int) {

    private val shelf = Biquad.highShelf(sampleRate, 1681.97, 3.9998, 0.7072)
    private val highPass = Biquad.highPass(sampleRate, 38.135, 0.5003)

    private val step = (sampleRate * 0.1).toInt().coerceAtLeast(1) // 100 ms hop
    private val stepPowers = ArrayDeque<Double>()
    private var accumulated = 0.0
    private var accumulatedCount = 0
    private val blockPowers = ArrayList<Double>()

    var peak: Float = 0f
        private set

    fun add(samples: FloatArray, from: Int = 0, to: Int = samples.size) {
        for (i in from until to) {
            val x = samples[i]
            val a = abs(x)
            if (a > peak) peak = a
            val y = highPass.process(shelf.process(x.toDouble()))
            accumulated += y * y
            accumulatedCount++
            if (accumulatedCount == step) {
                stepPowers.addLast(accumulated / step)
                accumulated = 0.0
                accumulatedCount = 0
                if (stepPowers.size > 4) stepPowers.removeFirst()
                if (stepPowers.size == 4) blockPowers += stepPowers.average()
            }
        }
    }

    /** Integrated loudness in LUFS, or null when there is not enough (non-silent) audio. */
    fun integratedLufs(): Double? {
        val absGated = blockPowers.filter { lufs(it) > -70.0 }
        if (absGated.isEmpty()) return null
        val relThreshold = lufs(absGated.average()) - 10.0
        val relGated = absGated.filter { lufs(it) > relThreshold }
        if (relGated.isEmpty()) return null
        return lufs(relGated.average())
    }

    val peakDbfs: Double get() = if (peak <= 0f) -120.0 else 20 * log10(peak.toDouble())

    private fun lufs(power: Double): Double = -0.691 + 10 * log10(power.coerceAtLeast(1e-12))

    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        private var z1 = 0.0
        private var z2 = 0.0
        fun process(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }

        companion object {
            // Coefficient derivation matching the BS.1770 filters at any sample rate (as used by libebur128).
            fun highShelf(fs: Int, f0: Double, gainDb: Double, q: Double): Biquad {
                val k = tan(PI * f0 / fs)
                val vh = 10.0.pow(gainDb / 20)
                val vb = vh.pow(0.4996667741545416)
                val a0 = 1 + k / q + k * k
                return Biquad(
                    b0 = (vh + vb * k / q + k * k) / a0,
                    b1 = 2 * (k * k - vh) / a0,
                    b2 = (vh - vb * k / q + k * k) / a0,
                    a1 = 2 * (k * k - 1) / a0,
                    a2 = (1 - k / q + k * k) / a0,
                )
            }

            fun highPass(fs: Int, f0: Double, q: Double): Biquad {
                val k = tan(PI * f0 / fs)
                val a0 = 1 + k / q + k * k
                return Biquad(
                    b0 = 1.0 / a0,
                    b1 = -2.0 / a0,
                    b2 = 1.0 / a0,
                    a1 = 2 * (k * k - 1) / a0,
                    a2 = (1 - k / q + k * k) / a0,
                )
            }
        }
    }

    companion object {
        /** Gain (dB) that brings [measuredLufs] to [targetLufs] without pushing the peak above [ceilingDbfs] by more than [limiterHeadroomDb]. */
        fun normalizationGainDb(measuredLufs: Double, peakDbfs: Double, targetLufs: Double = -16.0, ceilingDbfs: Double = -1.0, limiterHeadroomDb: Double = 6.0): Double {
            val wanted = targetLufs - measuredLufs
            val peakLimit = ceilingDbfs - peakDbfs + limiterHeadroomDb
            return minOf(wanted, peakLimit).coerceIn(-20.0, 20.0)
        }
    }
}
