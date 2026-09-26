package com.ravango.engine.camera.muxer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SegmentPlannerTest {

    private data class R(val segment: Int, val local: Long, val open: Int, val close: Int, val key: Boolean)

    private fun SegmentPlanner.video(pts: Long, key: Boolean = false) = routeVideo(pts, key).let { R(it.segment, it.localPtsUs, it.open, it.close, it.requestKeyFrame) }
    private fun SegmentPlanner.audio(pts: Long) = routeAudio(pts).let { R(it.segment, it.localPtsUs, it.open, it.close, it.requestKeyFrame) }

    @Test
    fun `first key frame opens segment zero at local zero`() {
        val p = SegmentPlanner(1_000_000, hasAudio = true)
        assertThat(p.audio(0).segment).isEqualTo(-1) // before any video
        assertThat(p.video(10, key = false).segment).isEqualTo(-1) // must start on a key frame
        assertThat(p.video(33_000, key = true)).isEqualTo(R(0, 0, 0, -1, false))
        assertThat(p.audio(20_000).segment).isEqualTo(-1) // captured before the anchor
        assertThat(p.audio(40_000)).isEqualTo(R(0, 7_000, -1, -1, false))
    }

    @Test
    fun `cut happens at the next key frame after the duration and audio follows the cut point`() {
        val p = SegmentPlanner(1_000_000, hasAudio = true)
        p.video(0, key = true)
        p.audio(1_000)
        // Duration reached on a non-key frame: request exactly one key frame.
        assertThat(p.video(1_000_000).key).isTrue()
        assertThat(p.video(1_033_000).key).isFalse()
        // Key frame arrives: new segment with local time 0; previous stays open for audio.
        assertThat(p.video(1_066_000, key = true)).isEqualTo(R(1, 0, 1, -1, false))
        // Audio before the cut goes to the old segment...
        assertThat(p.audio(1_050_000)).isEqualTo(R(0, 1_050_000, -1, -1, false))
        // ...the first audio at/after the cut closes it and starts the new segment.
        assertThat(p.audio(1_070_000)).isEqualTo(R(1, 4_000, -1, 0, false))
        assertThat(p.openSegments()).containsExactly(1)
    }

    @Test
    fun `video only recordings close the previous segment at the cut`() {
        val p = SegmentPlanner(1_000_000, hasAudio = false)
        p.video(0, key = true)
        assertThat(p.video(1_200_000, key = true)).isEqualTo(R(1, 0, 1, 0, false))
    }

    @Test
    fun `non increasing timestamps are dropped`() {
        val p = SegmentPlanner(1_000_000, hasAudio = true)
        p.video(100, key = true)
        assertThat(p.video(100).segment).isEqualTo(-1)
        assertThat(p.video(50).segment).isEqualTo(-1)
        p.audio(200)
        assertThat(p.audio(200).segment).isEqualTo(-1)
    }

    @Test
    fun `previous segment is closed when audio stalls`() {
        val p = SegmentPlanner(1_000_000, hasAudio = true, audioCloseTimeoutUs = 500_000)
        p.video(0, key = true)
        p.video(1_000_000, key = true)
        assertThat(p.openSegments()).containsExactly(0, 1).inOrder()
        assertThat(p.video(1_400_000).close).isEqualTo(-1)
        assertThat(p.video(1_600_000).close).isEqualTo(0)
        assertThat(p.openSegments()).containsExactly(1)
    }

    @Test
    fun `disabling audio closes a pending previous segment`() {
        val p = SegmentPlanner(1_000_000, hasAudio = true)
        p.video(0, key = true)
        p.video(1_000_000, key = true)
        assertThat(p.disableAudio().close).isEqualTo(0)
        assertThat(p.video(2_000_000, key = true).close).isEqualTo(1)
    }

    @Test
    fun `concat timeline rebuilds continuous timestamps`() {
        val entry = SegmentEntry(index = 1, file = "s1.mp4", baseUs = 60_000_000, durationUs = 60_000_000, firstAudioLocalUs = 5_000)
        assertThat(ConcatTimeline.outputPtsUs(entry, isAudio = false, sampleTimeUs = 33_333, trackFirstUs = 0, firstVideoUs = 0)).isEqualTo(60_033_333)
        // Extractor normalized the audio start to 0: the manifest offset restores it.
        assertThat(ConcatTimeline.outputPtsUs(entry, isAudio = true, sampleTimeUs = 21_333, trackFirstUs = 0, firstVideoUs = 0)).isEqualTo(60_026_333)
        // Extractor kept the edit-list offset: same result.
        assertThat(ConcatTimeline.outputPtsUs(entry, isAudio = true, sampleTimeUs = 26_333, trackFirstUs = 5_000, firstVideoUs = 0)).isEqualTo(60_026_333)
        val unknown = entry.copy(firstAudioLocalUs = -1)
        assertThat(ConcatTimeline.outputPtsUs(unknown, isAudio = true, sampleTimeUs = 8_000, trackFirstUs = 8_000, firstVideoUs = 0)).isEqualTo(60_008_000)
        assertThat(ConcatTimeline.monotonic(10, 10)).isEqualTo(11)
        assertThat(ConcatTimeline.monotonic(12, 10)).isEqualTo(12)
    }

    @Test
    fun `manifest survives a round trip`() {
        val manifest = RecordingManifest(
            id = "abc",
            createdAt = 1L,
            finalName = "RavanGo_1.mp4",
            captureMode = com.ravango.core.model.CaptureMode.VIDEO_WITH_AUDIO,
            width = 1080,
            height = 1920,
            frameRate = 30,
            videoMime = "video/avc",
            hasAudio = true,
            segments = listOf(SegmentEntry(0, "segment_0000.mp4", 0, 60_000_000, 1_000), SegmentEntry(1, "segment_0001.mp4", 60_000_000, 12_000_000)),
        )
        val decoded = ManifestCodec.decode(ManifestCodec.encode(manifest))
        assertThat(decoded).isEqualTo(manifest)
        assertThat(decoded!!.totalDurationUs).isEqualTo(72_000_000)
        assertThat(ManifestCodec.decode("{broken")).isNull()
    }
}
