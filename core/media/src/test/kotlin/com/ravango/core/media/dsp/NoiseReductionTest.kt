package com.ravango.core.media.dsp

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class NoiseReductionTest {
    private val sr = 48_000
    private val toneHz = 1000.0
    private val toneAmp = 0.25
    private val periodSamples = (0.8 * sr).toInt() // 400 ms tone, 400 ms pause (speech-like gating)

    private fun toneEnvelope(n: Int): Double {
        val p = n % periodSamples
        val on = periodSamples / 2
        val fade = sr / 200
        return when {
            p >= on -> 0.0
            p < fade -> p.toDouble() / fade
            p > on - fade -> (on - p).toDouble() / fade
            else -> 1.0
        }
    }

    private fun tone(n: Int) = toneAmp * toneEnvelope(n) * sin(2 * PI * toneHz * n / sr)

    private fun signal(seconds: Int, noiseStd: Double, seed: Long = 1): Pair<FloatArray, FloatArray> {
        val rnd = Random(seed)
        val n = seconds * sr
        val noise = FloatArray(n) { (rnd.nextGaussian() * noiseStd).toFloat() }
        val mixed = FloatArray(n) { (tone(it) + noise[it]).toFloat() }
        return mixed to noise
    }

    private data class Metrics(val noiseReductionDb: Double, val toneLevelDb: Double)

    private fun measure(strength: Float): Metrics {
        val (input, _) = signal(seconds = 8, noiseStd = 0.02)
        val nr = SpectralNoiseReducer(sr, 1)
        nr.setStrength(strength)
        nr.reset()
        val out = input.copyOf()
        var pos = 0
        while (pos < out.size) { val len = min(480, out.size - pos); nr.process(out, pos, len); pos += len }
        val lat = nr.latencySamples
        val margin = sr / 20
        var inOff = 0.0; var outOff = 0.0
        var sinSum = 0.0; var cosSum = 0.0; var onCount = 0
        for (n in 4 * sr until out.size - lat) {
            val p = n % periodSamples
            val y = out[n + lat].toDouble()
            if (p >= periodSamples / 2 + margin && p < periodSamples - margin) {
                inOff += input[n] * input[n].toDouble(); outOff += y * y
            } else if (p in margin until periodSamples / 2 - margin) {
                sinSum += y * sin(2 * PI * toneHz * n / sr); cosSum += y * cos(2 * PI * toneHz * n / sr); onCount++
            }
        }
        val amp = 2 * sqrt(sinSum * sinSum + cosSum * cosSum) / onCount
        return Metrics(10 * log10(inOff / outOff), 20 * log10(amp / toneAmp))
    }

    @Test
    fun reducesNoiseAndPreservesTone() {
        val strong = measure(1f)
        assertThat(strong.noiseReductionDb).isGreaterThan(18.0)
        assertThat(abs(strong.toneLevelDb)).isLessThan(1.0)

        val medium = measure(0.5f)
        assertThat(medium.noiseReductionDb).isGreaterThan(11.0)
        assertThat(medium.noiseReductionDb).isLessThan(strong.noiseReductionDb)
        assertThat(abs(medium.toneLevelDb)).isLessThan(1.0)
    }

    @Test
    fun zeroStrengthIsAnExactDelay() {
        val (input, _) = signal(seconds = 1, noiseStd = 0.05)
        val nr = SpectralNoiseReducer(sr, 2)
        val stereo = FloatArray(input.size * 2) { input[it / 2] * if (it % 2 == 0) 1f else -0.5f }
        val out = stereo.copyOf()
        nr.process(out, 0, input.size)
        val lat = nr.latencySamples
        assertThat(lat).isEqualTo(512)
        var maxErr = 0f
        for (n in 0 until input.size - lat) {
            maxErr = maxOf(maxErr, abs(out[2 * (n + lat)] - stereo[2 * n]), abs(out[2 * (n + lat) + 1] - stereo[2 * n + 1]))
        }
        assertThat(maxErr).isLessThan(1e-5f)
    }

    @Test
    fun stereoChannelsShareGains() {
        val (input, _) = signal(seconds = 3, noiseStd = 0.02)
        val nr = SpectralNoiseReducer(sr, 2)
        nr.setStrength(0.8f); nr.reset()
        val stereo = FloatArray(input.size * 2) { input[it / 2] }
        nr.process(stereo, 0, input.size)
        for (n in 0 until input.size) assertThat(stereo[2 * n]).isWithin(1e-6f).of(stereo[2 * n + 1])
    }
}
