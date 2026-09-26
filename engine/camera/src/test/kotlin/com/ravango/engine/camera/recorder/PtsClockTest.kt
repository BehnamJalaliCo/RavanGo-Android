package com.ravango.engine.camera.recorder

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PtsClockTest {

    @Test
    fun `nothing maps before the anchor`() {
        val clock = PtsClock()
        assertThat(clock.mapNs(5_000)).isEqualTo(-1)
        clock.anchor(1_000)
        assertThat(clock.anchor(9_999)).isEqualTo(1_000) // anchor is set once
        assertThat(clock.mapNs(900)).isEqualTo(-1)
        assertThat(clock.mapNs(1_000)).isEqualTo(0)
        assertThat(clock.mapNs(2_500)).isEqualTo(1_500)
    }

    @Test
    fun `pauses are removed from the timeline`() {
        val clock = PtsClock()
        clock.anchor(1_000)
        clock.pause(2_000)
        assertThat(clock.isPaused).isTrue()
        assertThat(clock.mapNs(2_500)).isEqualTo(-1)
        clock.resume(3_000)
        assertThat(clock.mapNs(2_999)).isEqualTo(-1)
        assertThat(clock.mapNs(3_000)).isEqualTo(1_000)
        assertThat(clock.mapNs(3_500)).isEqualTo(1_500)
        clock.pause(4_000)
        clock.resume(10_000)
        assertThat(clock.mapNs(10_000)).isEqualTo(2_000)
        assertThat(clock.pausedBefore(10_000)).isEqualTo(7_000)
        // Samples captured just before a pause keep their time.
        assertThat(clock.mapNs(1_999)).isEqualTo(999)
    }

    @Test
    fun `microsecond mapping`() {
        val clock = PtsClock()
        clock.anchor(1_000_000_000)
        assertThat(clock.mapUs(1_033_333_333)).isEqualTo(33_333)
        assertThat(clock.mapUs(10)).isEqualTo(-1)
    }

    @Test
    fun `double pause and resume are ignored`() {
        val clock = PtsClock()
        clock.anchor(0)
        clock.resume(10)
        clock.pause(100)
        clock.pause(150)
        clock.resume(200)
        clock.resume(300)
        assertThat(clock.mapNs(300)).isEqualTo(200)
    }
}
