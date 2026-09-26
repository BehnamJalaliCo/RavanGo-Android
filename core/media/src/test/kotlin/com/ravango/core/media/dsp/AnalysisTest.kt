package com.ravango.core.media.dsp

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

class AnalysisTest {

    @Test
    fun levelMeterValues() {
        val sine = ShortArray(48_000) { (32767 * sin(2 * PI * 1000 * it / 48_000)).toInt().toShort() }
        val full = LevelMeter.measure(sine, 0, sine.size)
        assertThat(full.peakDbfs.toDouble()).isWithin(0.01).of(0.0)
        assertThat(full.rmsDbfs.toDouble()).isWithin(0.02).of(-3.01)
        assertThat(full.clipping).isTrue()

        val half = ShortArray(4800) { (16384 * sin(2 * PI * 1000 * it / 48_000)).toInt().toShort() }
        val h = LevelMeter.measure(half, 0, half.size)
        assertThat(h.peakDbfs.toDouble()).isWithin(0.05).of(-6.02)
        assertThat(h.clipping).isFalse()

        val silent = LevelMeter.measure(ShortArray(100), 0, 100)
        assertThat(silent.peakDbfs).isEqualTo(-90f)
        assertThat(silent.rmsDbfs).isEqualTo(-90f)

        assertThat(LevelMeter.measure(shortArrayOf(0, 32700, 0), 0, 3).clipping).isTrue()
        assertThat(LevelMeter.measure(shortArrayOf(0, 32699, 0), 0, 3).clipping).isFalse()
        assertThat(LevelMeter.measure(shortArrayOf(-32768), 0, 1).clipping).isTrue()
        // offset/length are respected
        assertThat(LevelMeter.measure(shortArrayOf(32767, 100, 32767), 1, 1).clipping).isFalse()

        assertThat(LevelMeter.measure(floatArrayOf(0.5f, -0.999f), 0, 2).clipping).isTrue()
        assertThat(LevelMeter.measure(floatArrayOf(0.5f, -0.5f), 0, 2).rmsDbfs.toDouble()).isWithin(0.01).of(-6.02)
    }

    @Test
    fun levelAccumulatorMatchesMeter() {
        val data = ShortArray(960) { (it * 31 % 20000 - 10000).toShort() }
        val acc = LevelAccumulator()
        acc.add(data, 0, 480); acc.add(data, 480, 480)
        val ref = LevelMeter.measure(data, 0, data.size)
        assertThat(acc.peakDbfs).isEqualTo(ref.peakDbfs)
        assertThat(acc.rmsDbfs.toDouble()).isWithin(1e-4).of(ref.rmsDbfs.toDouble())
        acc.reset()
        assertThat(acc.rmsDbfs).isEqualTo(-90f)
    }

    private fun frames(vararg segments: Pair<Int, Float>): FrameLevels {
        val levels = ArrayList<Float>()
        for ((ms, db) in segments) repeat(ms / 20) { levels += db }
        return FrameLevels.of(20, levels.toFloatArray())
    }

    @Test
    fun detectsSilencesWithPaddingAndMinimumLength() {
        val f = frames(1000 to -20f, 1000 to -60f, 1000 to -22f, 300 to -60f, 1000 to -21f, 800 to -58f)
        val ranges = SilenceAnalysis.findSilences(f, SilenceOptions(minSilenceMs = 450, paddingMs = 120))
        // 300 ms pause is too short; trailing silence is kept.
        assertThat(ranges).containsExactly(
            TimeRange(1_120_000, 1_880_000),
            TimeRange(4_420_000, 4_980_000),
        ).inOrder()
    }

    @Test
    fun hysteresisAndMergingIgnoreShortBlips() {
        // A 40 ms click inside a pause, and levels hovering just above the threshold inside silence.
        val f = frames(1000 to -20f, 500 to -60f, 40 to -25f, 500 to -60f, 200 to -50f, 300 to -60f, 1000 to -20f)
        // floor = −60 → threshold −52, release −49: −50 frames stay silent (hysteresis).
        val ranges = SilenceAnalysis.findSilences(f, SilenceOptions(paddingMs = 0))
        assertThat(ranges).containsExactly(TimeRange(1_000_000, 2_540_000))
    }

    @Test
    fun thresholdIsCappedAndRangeRespected() {
        // Very noisy floor (−30 dBFS): threshold capped at −35 → nothing is silent.
        val noisy = frames(1000 to -30f, 1000 to -10f)
        assertThat(SilenceAnalysis.findSilences(noisy, SilenceOptions())).isEmpty()

        val f = frames(1000 to -60f, 1000 to -20f, 1000 to -60f)
        val ranges = SilenceAnalysis.findSilences(f, SilenceOptions(paddingMs = 100), rangeStartUs = 0, rangeEndUs = 2_500_000)
        assertThat(ranges).containsExactly(TimeRange(100_000, 900_000), TimeRange(2_100_000, 2_500_000)).inOrder()
    }

    @Test
    fun framesFromSyntheticPcm() {
        val sr = 16_000
        val rnd = Random(5)
        // 1 s low noise, 1 s tone, 1 s low noise; delivered in odd-sized blocks with timestamps.
        val total = 3 * sr
        val pcm = FloatArray(total) {
            val noise = (rnd.nextGaussian() * 0.001).toFloat()
            if (it in sr until 2 * sr) noise + (0.3 * sin(2 * PI * 220 * it / sr)).toFloat() else noise
        }
        val levels = FrameLevels(SilenceAnalysis.FRAME_MS)
        var pos = 0
        while (pos < total) {
            val n = minOf(1234, total - pos)
            levels.add(pcm.copyOfRange(pos, pos + n), n, sr, pos * 1_000_000L / sr)
            pos += n
        }
        levels.finish()
        assertThat(levels.size).isEqualTo(150)
        val ranges = SilenceAnalysis.findSilences(levels, SilenceOptions(paddingMs = 100))
        assertThat(ranges).hasSize(2)
        assertThat(ranges[0].startUs).isEqualTo(100_000)
        assertThat(ranges[0].endUs.toDouble()).isWithin(1000.0).of(900_000.0)
        assertThat(ranges[1].startUs.toDouble()).isWithin(1000.0).of(2_100_000.0)
        assertThat(ranges[1].endUs.toDouble()).isWithin(1000.0).of(2_900_000.0)
    }

    @Test
    fun loudnessEnvelopeWindows() {
        val sr = 8_000
        val b = LoudnessEnvelopeBuilder(100)
        val half = FloatArray(800) { if (it % 2 == 0) 0.5f else -0.5f }
        b.add(half, half.size, sr, 0)           // window 0: −6 dB
        b.add(FloatArray(800), 800, sr, 200_000) // window 2: silence; window 1 missing
        val env = b.build()
        assertThat(env.size).isEqualTo(3)
        assertThat(env[0].toDouble()).isWithin(0.01).of(-6.02)
        assertThat(env[1]).isEqualTo(-90f)
        assertThat(env[2]).isEqualTo(-90f)
    }
}
