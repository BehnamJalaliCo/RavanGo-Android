package com.ravango.feature.camera.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StudioLogicTest {

    @Test
    fun `orientation snaps with hysteresis`() {
        assertThat(snapOrientation(50, 0)).isEqualTo(0)
        assertThat(snapOrientation(70, 0)).isEqualTo(90)
        assertThat(snapOrientation(350, 0)).isEqualTo(0)
        assertThat(snapOrientation(300, 0)).isEqualTo(270)
        assertThat(snapOrientation(130, 90)).isEqualTo(90)
        assertThat(snapOrientation(-1, 180)).isEqualTo(180) // ORIENTATION_UNKNOWN keeps the last value
    }

    @Test
    fun `meter maps dBFS to a 60 dB scale`() {
        assertThat(meterFraction(0f)).isEqualTo(1f)
        assertThat(meterFraction(-60f)).isEqualTo(0f)
        assertThat(meterFraction(-90f)).isEqualTo(0f)
        assertThat(meterFraction(-30f)).isWithin(1e-4f).of(0.5f)
    }

    @Test
    fun `exposure and shutter labels`() {
        assertThat(evLabel(0, 1f / 3f)).isEqualTo("0 EV")
        assertThat(evLabel(2, 1f / 3f)).isEqualTo("+0.7 EV")
        assertThat(evLabel(-3, 1f / 6f)).isEqualTo("-0.5 EV")
        assertThat(shutterLabel(16_666_667)).isEqualTo("1/60")
        assertThat(shutterLabel(500_000_000)).isEqualTo("0.5\"")
        assertThat(zoomLabel(0.6f)).isEqualTo("0.6×")
        assertThat(zoomLabel(2f)).isEqualTo("2×")
    }

    @Test
    fun `shutter stops are sorted fastest first`() {
        assertThat(SHUTTER_STOPS_NS).isInOrder()
        assertThat(SHUTTER_STOPS_NS.first()).isEqualTo(125_000L)
    }
}
