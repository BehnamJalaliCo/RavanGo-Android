package com.ravango.engine.editor

import com.google.common.truth.Truth.assertThat
import com.ravango.engine.editor.timeline.TimelineMath
import org.junit.Test

class TimelineMathTest {
    @Test
    fun placements_accumulateOutputDurations() {
        val d = doc(clip("a", 0, 4 * S), clip("b", 2 * S, 6 * S, speed = 2f), clip("c", 0, 3 * S))
        val p = TimelineMath.placements(d)
        assertThat(p.map { it.startUs }).containsExactly(0L, 4 * S, 6 * S).inOrder()
        assertThat(p.map { it.endUs }).containsExactly(4 * S, 6 * S, 9 * S).inOrder()
        assertThat(d.durationUs).isEqualTo(9 * S)
    }

    @Test
    fun clipAt_endMapsToLastClip() {
        val d = doc(clip("a", 0, 4 * S), clip("b", 0, 2 * S))
        assertThat(TimelineMath.clipAt(d, 3 * S)?.clip?.id).isEqualTo("a")
        assertThat(TimelineMath.clipAt(d, 4 * S)?.clip?.id).isEqualTo("b")
        assertThat(TimelineMath.clipAt(d, 6 * S)?.clip?.id).isEqualTo("b")
        assertThat(TimelineMath.clipAt(d, 60 * S)?.clip?.id).isEqualTo("b")
    }

    @Test
    fun timelineToSource_handlesSpeed() {
        val c = clip("a", 2 * S, 8 * S, speed = 2f)
        assertThat(c.outputDurationUs).isEqualTo(3 * S)
        assertThat(TimelineMath.timelineToSource(c, 10 * S, 11 * S)).isEqualTo(4 * S)
        assertThat(TimelineMath.sourceToTimeline(c, 10 * S, 4 * S)).isEqualTo(11 * S)
        assertThat(TimelineMath.sourceToTimeline(c, 10 * S, 1 * S)).isNull()
    }

    @Test
    fun timelineToSource_reversedPlaysBackwards() {
        val c = clip("a", 2 * S, 8 * S, reversed = true)
        assertThat(TimelineMath.timelineToSource(c, 0, 0)).isEqualTo(8 * S)
        assertThat(TimelineMath.timelineToSource(c, 0, 1 * S)).isEqualTo(7 * S)
        assertThat(TimelineMath.sourceToTimeline(c, 0, 7 * S)).isEqualTo(1 * S)
    }

    @Test
    fun speed_isClampedAndRounded() {
        assertThat(TimelineMath.clampSpeed(10f)).isEqualTo(4f)
        assertThat(TimelineMath.clampSpeed(0.1f)).isEqualTo(0.25f)
        assertThat(TimelineMath.clampSpeed(1.234f)).isEqualTo(1.23f)
    }

    @Test
    fun snap_picksClosestWithinThreshold() {
        val points = longArrayOf(0, 1_000_000, 2_000_000)
        assertThat(TimelineMath.snap(1_040_000, points, 50_000)).isEqualTo(1_000_000)
        assertThat(TimelineMath.snap(1_100_000, points, 50_000)).isEqualTo(1_100_000)
        assertThat(TimelineMath.snap(1_960_000, points, 50_000)).isEqualTo(2_000_000)
    }

    @Test
    fun snapPoints_includeClipEdgesAndCues() {
        val d = doc(clip("a", 0, 2 * S), clip("b", 0, 3 * S), cues = listOf(com.ravango.core.model.SubtitleCue(startUs = 500_000, endUs = 900_000, text = "x")))
        assertThat(TimelineMath.snapPoints(d).toList()).containsExactly(0L, 500_000L, 900_000L, 2 * S, 5 * S).inOrder()
    }
}
