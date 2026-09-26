package com.ravango.engine.ai.video

import com.google.common.truth.Truth.assertThat
import com.ravango.core.media.dsp.TimeRange
import com.ravango.core.model.EditorDocument
import com.ravango.core.model.MediaKind
import com.ravango.core.model.MediaSource
import com.ravango.core.model.SubtitleCue
import com.ravango.core.model.VideoClip
import com.ravango.core.model.WordTiming
import org.junit.Test

class TimelineMapperTest {

    private val source = MediaSource(uri = "file:///a.mp4", kind = MediaKind.VIDEO, durationUs = 60_000_000)

    @Test
    fun `maps with trim, speed and clip offset`() {
        val first = VideoClip(id = "a", source = source, trimStartUs = 0, trimEndUs = 10_000_000)
        val second = VideoClip(id = "b", source = source, trimStartUs = 20_000_000, trimEndUs = 40_000_000, speed = 2f)
        val doc = EditorDocument(mainTrack = listOf(first, second))
        val mapper = TimelineMapper(second, doc.clipStartUs("b"))
        // 10 s of the first clip, then (24 s − 20 s) / 2 = 2 s into the second.
        assertThat(mapper.toTimeline(24_000_000)).isEqualTo(12_000_000)
        assertThat(mapper.toTimeline(TimeRange(30_000_000, 32_000_000))).isEqualTo(TimeRange(15_000_000, 16_000_000))
    }

    @Test
    fun `ranges outside trim are clipped or dropped`() {
        val clip = VideoClip(id = "a", source = source, trimStartUs = 5_000_000, trimEndUs = 15_000_000)
        val mapper = TimelineMapper(clip, 0)
        assertThat(mapper.toTimeline(TimeRange(0, 4_000_000))).isNull()
        assertThat(mapper.toTimeline(TimeRange(4_000_000, 6_000_000))).isEqualTo(TimeRange(0, 1_000_000))
        assertThat(mapper.toTimeline(TimeRange(14_000_000, 20_000_000))).isEqualTo(TimeRange(9_000_000, 10_000_000))
    }

    @Test
    fun `reversed clip maps from the trim end`() {
        val clip = VideoClip(id = "a", source = source, trimStartUs = 0, trimEndUs = 10_000_000, reversed = true)
        val mapper = TimelineMapper(clip, 1_000_000)
        assertThat(mapper.toTimeline(TimeRange(2_000_000, 3_000_000))).isEqualTo(TimeRange(8_000_000, 9_000_000))
    }

    @Test
    fun `cues keep word timings in timeline time`() {
        val clip = VideoClip(id = "a", source = source, trimStartUs = 10_000_000, trimEndUs = 20_000_000, speed = 0.5f)
        val mapper = TimelineMapper(clip, 3_000_000)
        val cue = SubtitleCue(startUs = 11_000_000, endUs = 12_000_000, text = "hi there", words = listOf(WordTiming("hi", 11_000_000, 11_400_000), WordTiming("there", 11_500_000, 12_000_000)))
        val mapped = mapper.toTimeline(cue)!!
        assertThat(mapped.startUs).isEqualTo(5_000_000)
        assertThat(mapped.endUs).isEqualTo(7_000_000)
        assertThat(mapped.words.map { it.startUs }).containsExactly(5_000_000L, 6_000_000L).inOrder()
    }
}
