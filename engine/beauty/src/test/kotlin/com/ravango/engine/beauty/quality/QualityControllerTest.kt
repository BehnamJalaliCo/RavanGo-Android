package com.ravango.engine.beauty.quality

import com.google.common.truth.Truth.assertThat
import com.ravango.engine.beauty.BeautyQuality
import org.junit.Test

class QualityControllerTest {

    private val budget = 33_333_333L

    /** Feeds [seconds] of frames at 30 fps with a cost of [ratio] × budget, starting at [start]. Returns the end time. */
    private fun QualityController.run(ratio: Float, seconds: Double, start: Double): Double {
        var t = start
        val frames = (seconds * 30).toInt()
        repeat(frames) {
            onFrame((budget * ratio).toLong(), budget, t)
            t += 1 / 30.0
        }
        return t
    }

    @Test
    fun capCombinesTierThermalAndPowerSave() {
        assertThat(QualityController.capFor(TierHint.HIGH, ThermalHint.NORMAL, false)).isEqualTo(BeautyQuality.FULL)
        assertThat(QualityController.capFor(TierHint.MID, ThermalHint.NORMAL, false)).isEqualTo(BeautyQuality.BALANCED)
        assertThat(QualityController.capFor(TierHint.LOW, ThermalHint.NORMAL, false)).isEqualTo(BeautyQuality.LIGHT)
        assertThat(QualityController.capFor(TierHint.HIGH, ThermalHint.HOT, false)).isEqualTo(BeautyQuality.LIGHT)
        assertThat(QualityController.capFor(TierHint.HIGH, ThermalHint.CRITICAL, false)).isEqualTo(BeautyQuality.MINIMAL)
        assertThat(QualityController.capFor(TierHint.HIGH, ThermalHint.NORMAL, true)).isEqualTo(BeautyQuality.BALANCED)
    }

    @Test
    fun sustainedOverBudgetStepsDownAfterAboutOneSecond() {
        val c = QualityController(TierHint.HIGH)
        var t = c.run(0.7f, 0.8, 0.0)
        assertThat(c.level).isEqualTo(BeautyQuality.FULL)
        t = c.run(0.7f, 0.6, t)
        assertThat(c.level).isEqualTo(BeautyQuality.BALANCED)
        // Cooldown + hold again before the next step.
        t = c.run(0.7f, 1.0, t)
        assertThat(c.level).isEqualTo(BeautyQuality.BALANCED)
        c.run(0.7f, 2.0, t)
        assertThat(c.level).isEqualTo(BeautyQuality.LIGHT)
    }

    @Test
    fun briefSpikeDoesNotStepDown() {
        val c = QualityController(TierHint.HIGH)
        var t = c.run(0.2f, 1.0, 0.0)
        t = c.run(1.5f, 0.2, t)
        c.run(0.2f, 2.0, t)
        assertThat(c.level).isEqualTo(BeautyQuality.FULL)
    }

    @Test
    fun stepsUpAfterSustainedHeadroomButNeverAboveCap() {
        val c = QualityController(TierHint.MID)
        assertThat(c.level).isEqualTo(BeautyQuality.BALANCED)
        var t = c.run(0.8f, 2.0, 0.0)
        assertThat(c.level).isEqualTo(BeautyQuality.LIGHT)
        t = c.run(0.1f, 3.0, t)
        assertThat(c.level).isEqualTo(BeautyQuality.LIGHT)
        t = c.run(0.1f, 5.0, t)
        assertThat(c.level).isEqualTo(BeautyQuality.BALANCED)
        c.run(0.05f, 20.0, t)
        assertThat(c.level).isEqualTo(BeautyQuality.BALANCED) // MID tier cap
    }

    @Test
    fun recordingIsConservative() {
        val c = QualityController(TierHint.HIGH)
        var t = c.run(0.8f, 2.0, 0.0)
        assertThat(c.level).isEqualTo(BeautyQuality.BALANCED)
        c.recording = true
        // Moderate headroom (that would step up when idle) keeps the level while recording.
        t = c.run(0.2f, 15.0, t)
        assertThat(c.level).isEqualTo(BeautyQuality.BALANCED)
        // Huge headroom eventually allows stepping up.
        c.run(0.05f, 12.0, t)
        assertThat(c.level).isEqualTo(BeautyQuality.FULL)
    }

    @Test
    fun recordingStepsDownSooner() {
        val c = QualityController(TierHint.HIGH)
        c.recording = true
        c.run(0.42f, 1.2, 0.0)
        assertThat(c.level).isEqualTo(BeautyQuality.BALANCED)
    }

    @Test
    fun thermalCapAppliesImmediately() {
        val c = QualityController(TierHint.HIGH)
        c.thermal = ThermalHint.HOT
        assertThat(c.level).isEqualTo(BeautyQuality.LIGHT)
        c.thermal = ThermalHint.NORMAL
        // Cooling down does not jump back instantly; headroom must be measured first.
        assertThat(c.level).isEqualTo(BeautyQuality.LIGHT)
        c.run(0.1f, 6.0, 0.0)
        assertThat(c.level).isEqualTo(BeautyQuality.BALANCED)
    }

    @Test
    fun neverStepsBelowMinimal() {
        val c = QualityController(TierHint.LOW)
        c.run(2f, 20.0, 0.0)
        assertThat(c.level).isEqualTo(BeautyQuality.MINIMAL)
    }
}
