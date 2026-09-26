package com.ravango.feature.editor

import com.google.common.truth.Truth.assertThat
import com.ravango.core.model.MediaKind
import com.ravango.core.model.MediaSource
import com.ravango.core.model.VideoClip
import com.ravango.core.model.WordTiming
import com.ravango.engine.ai.api.Transcript
import com.ravango.engine.ai.api.TranscriptSegment
import org.junit.Test

class CaptionMappingTest {
    private val s = 1_000_000L
    private val source = MediaSource("file:///a.mp4", MediaKind.VIDEO, 20 * s)

    private val transcript = Transcript(
        "fa",
        listOf(
            TranscriptSegment(1 * s, 3 * s, "outside", listOf(WordTiming("outside", 1 * s, 3 * s))),
            TranscriptSegment(5 * s, 7 * s, "hello world", listOf(WordTiming("hello", 5 * s, 6 * s), WordTiming("world", 6 * s, 7 * s))),
            TranscriptSegment(9 * s, 11 * s, "cut here", listOf(WordTiming("cut", 9 * s, 9_500_000), WordTiming("here", 10_500_000, 11 * s))),
        ),
    )

    @Test
    fun mapsIntoTimelineWithTrimAndSpeed() {
        val clip = VideoClip(source = source, trimStartUs = 4 * s, trimEndUs = 10 * s, speed = 2f)
        val out = CaptionMapping.toTimeline(transcript, clip, clipStartUs = 100 * s)
        assertThat(out.segments).hasSize(2)
        val first = out.segments[0]
        assertThat(first.startUs).isEqualTo(100 * s + 500_000)
        assertThat(first.endUs).isEqualTo(101 * s + 500_000)
        assertThat(first.words.map { it.startUs }).containsExactly(100 * s + 500_000, 101 * s).inOrder()
        // The last segment is cut by the trim: only its first word survives and the text follows.
        assertThat(out.segments[1].text).isEqualTo("cut")
        assertThat(out.segments[1].endUs).isEqualTo(103 * s)
    }

    @Test
    fun reversedClipsGetNoCaptions() {
        val clip = VideoClip(source = source, reversed = true)
        assertThat(CaptionMapping.toTimeline(transcript, clip, 0).segments).isEmpty()
    }

    @Test
    fun mergeSortsByTime() {
        val a = Transcript("fa", listOf(TranscriptSegment(5 * s, 6 * s, "b")))
        val b = Transcript(null, listOf(TranscriptSegment(1 * s, 2 * s, "a")))
        val merged = CaptionMapping.merge(listOf(a, b))
        assertThat(merged.segments.map { it.text }).containsExactly("a", "b").inOrder()
        assertThat(merged.language).isEqualTo("fa")
    }
}
