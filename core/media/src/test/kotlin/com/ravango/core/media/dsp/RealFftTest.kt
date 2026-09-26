package com.ravango.core.media.dsp

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class RealFftTest {

    @Test
    fun roundTripIsAccurate() {
        for (n in listOf(4, 8, 64, 256, 512, 1024)) {
            val fft = RealFft(n)
            val rnd = Random(n)
            val x = FloatArray(n) { rnd.nextFloat() * 2f - 1f }
            val re = FloatArray(fft.bins)
            val im = FloatArray(fft.bins)
            val y = FloatArray(n)
            fft.forward(x, re, im)
            fft.inverse(re, im, y)
            var maxErr = 0f
            for (i in 0 until n) maxErr = maxOf(maxErr, abs(x[i] - y[i]))
            assertThat(maxErr).isLessThan(1e-5f)
        }
    }

    @Test
    fun forwardMatchesNaiveDft() {
        val n = 64
        val fft = RealFft(n)
        val rnd = Random(7)
        val x = FloatArray(n) { rnd.nextFloat() - 0.5f }
        val re = FloatArray(fft.bins)
        val im = FloatArray(fft.bins)
        fft.forward(x, re, im)
        for (k in 0..n / 2) {
            var sr = 0.0
            var si = 0.0
            for (t in 0 until n) {
                val a = -2.0 * PI * k * t / n
                sr += x[t] * cos(a)
                si += x[t] * sin(a)
            }
            assertThat(re[k].toDouble()).isWithin(1e-4).of(sr)
            assertThat(im[k].toDouble()).isWithin(1e-4).of(si)
        }
    }

    @Test
    fun pureToneLandsInItsBin() {
        val n = 512
        val fft = RealFft(n)
        val x = FloatArray(n) { cos(2 * PI * 10 * it / n).toFloat() }
        val re = FloatArray(fft.bins)
        val im = FloatArray(fft.bins)
        fft.forward(x, re, im)
        assertThat(re[10].toDouble()).isWithin(1e-3).of(n / 2.0)
        assertThat(abs(re[11])).isLessThan(1e-3f)
    }
}
