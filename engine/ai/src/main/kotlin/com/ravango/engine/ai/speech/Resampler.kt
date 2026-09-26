package com.ravango.engine.ai.speech

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Streaming mono resampler for speech (e.g. 44.1/48 kHz → 16 kHz). A 4th-order Butterworth low-pass (two cascaded
 * biquads) at 0.45 × the target rate removes content that would alias, then linear interpolation picks the output
 * samples. Plenty for speech recognition, cheap enough for hour-long files, and state carries across blocks.
 */
class Resampler(private val inputRate: Int, private val outputRate: Int) {

    private val ratio = inputRate.toDouble() / outputRate
    private val filters: List<Biquad> = if (inputRate > outputRate) {
        val cutoff = 0.45 * outputRate
        listOf(Biquad.lowPass(inputRate, cutoff, 0.5412), Biquad.lowPass(inputRate, cutoff, 1.3066))
    } else {
        emptyList()
    }

    /** Position of the next output sample, in input-sample units relative to the first sample of the next block. */
    private var position = 0.0
    private var previous = 0f
    private var hasPrevious = false

    /** Resamples [count] samples of [input]; returns the produced output samples. */
    fun process(input: FloatArray, count: Int = input.size): FloatArray {
        if (count <= 0) return FloatArray(0)
        val filtered = FloatArray(count)
        for (i in 0 until count) {
            var s = input[i]
            for (f in filters) s = f.process(s)
            filtered[i] = s
        }
        if (inputRate == outputRate) return filtered
        val out = ArrayList<Float>(((count / ratio) + 2).toInt())
        // Index -1 refers to the last sample of the previous block.
        while (true) {
            val idx = position
            val i0 = kotlin.math.floor(idx).toInt()
            val frac = (idx - i0).toFloat()
            if (i0 + 1 >= count) break
            val a = if (i0 < 0) (if (hasPrevious) previous else filtered[0]) else filtered[i0]
            val b = filtered[i0 + 1]
            out += a + (b - a) * frac
            position += ratio
        }
        position -= count
        previous = filtered[count - 1]
        hasPrevious = true
        return out.toFloatArray()
    }

    internal class Biquad(private val b0: Double, private val b1: Double, private val b2: Double, private val a1: Double, private val a2: Double) {
        private var z1 = 0.0
        private var z2 = 0.0

        fun process(x: Float): Float {
            val out = b0 * x + z1
            z1 = b1 * x - a1 * out + z2
            z2 = b2 * x - a2 * out
            return out.toFloat()
        }

        companion object {
            /** RBJ cookbook low-pass. */
            fun lowPass(sampleRate: Int, cutoff: Double, q: Double): Biquad {
                val w0 = 2 * PI * cutoff / sampleRate
                val alpha = sin(w0) / (2 * q)
                val cosw = cos(w0)
                val a0 = 1 + alpha
                return Biquad(
                    b0 = (1 - cosw) / 2 / a0,
                    b1 = (1 - cosw) / a0,
                    b2 = (1 - cosw) / 2 / a0,
                    a1 = -2 * cosw / a0,
                    a2 = (1 - alpha) / a0,
                )
            }
        }
    }

    companion object {
        /** RMS of a block, for quick level checks. */
        fun rms(samples: FloatArray, from: Int = 0, to: Int = samples.size): Float {
            if (to <= from) return 0f
            var sum = 0.0
            for (i in from until to) sum += samples[i] * samples[i]
            return sqrt(sum / (to - from)).toFloat()
        }
    }
}
