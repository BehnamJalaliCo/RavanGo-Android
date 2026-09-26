package com.ravango.core.media.dsp

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class VoiceProcessorTest {
    private val sr = 48_000

    @Test
    fun limiterNeverExceedsCeiling() {
        val vp = VoiceProcessor(VoiceProcessingConfig(sr, 2, gainDb = 24f, noiseReduction = 0.5f, voiceEnhance = true, limiter = true))
        val ceiling = dbToLinear(-1f)
        val rnd = Random(3)
        val buf = FloatArray(480 * 2)
        repeat(400) { block ->
            for (i in buf.indices) {
                val n = block * 480 + i / 2
                buf[i] = (0.9 * sin(2 * PI * 440 * n / sr) + rnd.nextGaussian() * 0.2).toFloat().coerceIn(-1f, 1f)
            }
            vp.process(buf, 0, buf.size)
            for (v in buf) assertThat(abs(v)).isAtMost(ceiling + 1e-6f)
        }
    }

    @Test
    fun limiterCeilingHoldsForShortPcm() {
        val vp = VoiceProcessor(VoiceProcessingConfig(sr, 1, gainDb = 18f, highPass = false))
        val limit = (dbToLinear(-1f) * 32768f).toInt() + 1
        val buf = ShortArray(480)
        repeat(200) { block ->
            for (i in buf.indices) buf[i] = (30000 * sin(2 * PI * 200 * (block * 480 + i) / sr)).toInt().toShort()
            vp.process(buf, 0, buf.size)
            for (v in buf) assertThat(abs(v.toInt())).isAtMost(limit)
        }
    }

    @Test
    fun gainChangesAreRampedWithoutZipperNoise() {
        val vp = VoiceProcessor(VoiceProcessingConfig(sr, 1, gainDb = 0f, highPass = false, limiter = false))
        val lat = vp.latencySamples
        val level = 0.1f
        val out = ArrayList<Float>()
        val buf = FloatArray(480)
        repeat(20) { block ->
            if (block == 5) vp.updateConfig(vp.config.copy(gainDb = 12f))
            buf.fill(level)
            vp.process(buf, 0, buf.size)
            buf.forEach { out += it }
        }
        // Steady state before the change, then a smooth monotonic rise to +12 dB.
        val gains = out.drop(lat + 480).map { it / level }
        assertThat(gains.first().toDouble()).isWithin(1e-3).of(1.0)
        var maxStep = 0f
        for (i in 1 until gains.size) {
            val step = gains[i] - gains[i - 1]
            assertThat(step).isAtLeast(-1e-4f)
            maxStep = maxOf(maxStep, step)
        }
        assertThat(maxStep).isLessThan(0.01f) // a jump from 1.0 to 3.98 in one sample would be 2.98
        assertThat(gains.last().toDouble()).isWithin(0.02).of(3.981)
    }

    @Test
    fun togglingStagesIsGlitchFree() {
        val vp = VoiceProcessor(VoiceProcessingConfig(sr, 1, highPass = false, limiter = false))
        val buf = FloatArray(480)
        var prev = 0f
        var maxDelta = 0f
        var n = 0
        repeat(100) { block ->
            if (block == 30) vp.updateConfig(vp.config.copy(highPass = true, voiceEnhance = true, noiseReduction = 0.7f))
            if (block == 60) vp.updateConfig(vp.config.copy(highPass = false, voiceEnhance = false, noiseReduction = 0f, limiter = true))
            for (i in buf.indices) { buf[i] = (0.3 * sin(2 * PI * 300 * n / sr)).toFloat(); n++ }
            vp.process(buf, 0, buf.size)
            for (v in buf) { if (block > 5) maxDelta = maxOf(maxDelta, abs(v - prev)); prev = v }
        }
        // A 300 Hz sine at 0.3 (+ makeup gain) changes by at most ~0.03 per sample; a click would be far larger.
        assertThat(maxDelta).isLessThan(0.08f)
    }

    @Test
    fun formatChangeRebuildsChain() {
        val vp = VoiceProcessor(VoiceProcessingConfig(48_000, 1))
        assertThat(vp.latencySamples).isEqualTo(512)
        vp.updateConfig(VoiceProcessingConfig(16_000, 2))
        vp.process(FloatArray(64), 0, 64)
        assertThat(vp.latencySamples).isEqualTo(256)
        assertThat(vp.latencyNs).isEqualTo(16_000_000L)
    }
}
