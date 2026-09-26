package com.ravango.core.media.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Radix-2 FFT of a real signal of length [size] (power of two, ≥ 4), computed with one complex FFT of half the size
 * plus a split step. All tables are precomputed; [forward] and [inverse] never allocate.
 *
 * Spectrum layout: `re[k]`, `im[k]` for k = 0..size/2 (inclusive: DC … Nyquist), i.e. arrays of length size/2 + 1.
 * [forward] is unnormalized (X[k] = Σ x[n]·e^(−2πikn/N)); [inverse] divides by N so `inverse(forward(x)) == x`.
 */
class RealFft(val size: Int) {
    init {
        require(size >= 4 && size and (size - 1) == 0) { "FFT size must be a power of two ≥ 4, was $size" }
    }

    /** Number of spectrum bins (DC … Nyquist). */
    val bins: Int = size / 2 + 1

    private val half = size / 2

    // Complex FFT (size/2) tables.
    private val cosTable = FloatArray(half / 2)
    private val sinTable = FloatArray(half / 2)
    private val bitReverse = IntArray(half)

    // Split-step twiddles e^(−2πik/N), k = 0..half.
    private val splitCos = FloatArray(half + 1)
    private val splitSin = FloatArray(half + 1)

    // Work buffers for the half-size complex transform.
    private val zr = FloatArray(half)
    private val zi = FloatArray(half)

    init {
        for (i in 0 until half / 2) {
            val a = -2.0 * PI * i / half
            cosTable[i] = cos(a).toFloat()
            sinTable[i] = sin(a).toFloat()
        }
        var bits = 0
        while ((1 shl bits) < half) bits++
        for (i in 0 until half) {
            var r = 0
            var v = i
            repeat(bits) { r = (r shl 1) or (v and 1); v = v shr 1 }
            bitReverse[i] = r
        }
        for (k in 0..half) {
            val a = -2.0 * PI * k / size
            splitCos[k] = cos(a).toFloat()
            splitSin[k] = sin(a).toFloat()
        }
    }

    /** Transforms [input] (length ≥ [size], read from index 0) into [re]/[im] (length ≥ [bins]). */
    fun forward(input: FloatArray, re: FloatArray, im: FloatArray) {
        for (k in 0 until half) {
            zr[k] = input[2 * k]
            zi[k] = input[2 * k + 1]
        }
        complexFft(zr, zi, inverse = false)
        // X[k] = E[k] + W^k·O[k],  E = (Z[k] + conj(Z[M−k]))/2,  O = (Z[k] − conj(Z[M−k]))/(2i)
        for (k in 0..half) {
            val a = k % half
            val b = (half - k) % half
            val zkr = zr[a]; val zki = zi[a]
            val zmr = zr[b]; val zmi = -zi[b] // conj(Z[M−k])
            val er = 0.5f * (zkr + zmr)
            val ei = 0.5f * (zki + zmi)
            // (Z − conj)/ (2i) = (dr + i·di)/(2i) = (di − i·dr)/2
            val dr = zkr - zmr
            val di = zki - zmi
            val or = 0.5f * di
            val oi = -0.5f * dr
            val wr = splitCos[k]; val wi = splitSin[k]
            re[k] = er + (wr * or - wi * oi)
            im[k] = ei + (wr * oi + wi * or)
        }
        im[0] = 0f
        im[half] = 0f
    }

    /** Inverse of [forward]: reads bins 0..size/2 from [re]/[im] and writes [size] samples to [output]. */
    fun inverse(re: FloatArray, im: FloatArray, output: FloatArray) {
        // E[k] = (X[k] + conj(X[M−k]))/2 ; O[k] = (X[k] − conj(X[M−k]))·W^(−k)/2 ; Z[k] = E[k] + i·O[k]
        for (k in 0 until half) {
            val xkr = re[k]; val xki = im[k]
            val xmr = re[half - k]; val xmi = -im[half - k]
            val er = 0.5f * (xkr + xmr)
            val ei = 0.5f * (xki + xmi)
            val dr = 0.5f * (xkr - xmr)
            val di = 0.5f * (xki - xmi)
            // multiply by W^(−k) = conj(W^k)
            val wr = splitCos[k]; val wi = -splitSin[k]
            val or = dr * wr - di * wi
            val oi = dr * wi + di * wr
            // Z = E + i·O
            zr[k] = er - oi
            zi[k] = ei + or
        }
        complexFft(zr, zi, inverse = true)
        val scale = 1f / half
        for (k in 0 until half) {
            output[2 * k] = zr[k] * scale
            output[2 * k + 1] = zi[k] * scale
        }
    }

    /** In-place iterative radix-2 complex FFT of length [half] (unnormalized). */
    private fun complexFft(re: FloatArray, im: FloatArray, inverse: Boolean) {
        val n = half
        for (i in 0 until n) {
            val j = bitReverse[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        val sign = if (inverse) -1f else 1f
        var len = 2
        while (len <= n) {
            val halfLen = len / 2
            val step = n / len
            var start = 0
            while (start < n) {
                var t = 0
                for (j in 0 until halfLen) {
                    val wr = cosTable[t]
                    val wi = sign * sinTable[t]
                    val a = start + j
                    val b = a + halfLen
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                    t += step
                }
                start += len
            }
            len = len shl 1
        }
    }
}
