package com.ravango.engine.camera.muxer

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer

class SegmentedMuxerTest {

    @get:Rule val tmp = TemporaryFolder()

    /** Behaves like MediaMuxer/MPEG4Writer: stop() fails when any added track has no samples. */
    private class FakeWriter(val file: File) : MuxerWriter {
        val tracks = ArrayList<String>()
        val samples = HashMap<Int, MutableList<Long>>()
        var started = false
        var stopped = false
        var stopCalls = 0
        var released = false

        override fun addTrack(format: Any): Int {
            check(!started)
            tracks += format as String
            return tracks.size - 1
        }

        override fun start() {
            started = true
            file.writeBytes(byteArrayOf(1))
        }

        override fun writeSample(track: Int, data: ByteBuffer, offset: Int, size: Int, ptsUs: Long, flags: Int) {
            check(started && !stopped)
            val list = samples.getOrPut(track) { ArrayList() }
            check(list.isEmpty() || ptsUs > list.last()) { "non-monotonic pts on track $track" }
            list += ptsUs
        }

        override fun stop() {
            stopCalls++
            check(started) { "not started" }
            check(tracks.indices.all { samples[it].orEmpty().isNotEmpty() }) { "Failed to stop the muxer" }
            stopped = true
        }

        override fun release() {
            released = true
        }

        fun count(kind: String): Int = tracks.indexOf(kind).let { if (it < 0) 0 else samples[it].orEmpty().size }
    }

    private class Events : SegmentedMuxer.Listener {
        val finished = ArrayList<SegmentEntry>()
        var errors = 0
        var audioDropped = 0
        var keyRequests = 0
        override fun onSegmentFinished(entry: SegmentEntry) { finished += entry }
        override fun onRequestKeyFrame() { keyRequests++ }
        override fun onMuxerError(error: Exception) { errors++ }
        override fun onAudioDropped() { audioDropped++ }
    }

    private var nowNs = 0L
    private val writers = ArrayList<FakeWriter>()
    private val events = Events()

    private fun muxer(expectAudio: Boolean, segmentUs: Long = 60_000_000) = SegmentedMuxer(
        dir = tmp.root,
        expectAudio = expectAudio,
        segmentDurationUs = segmentUs,
        frameDurationUs = 33_333,
        listener = events,
        writerFactory = { file -> FakeWriter(file).also { writers += it } },
        audioWaitUs = 2_000_000,
        clockNs = { nowNs },
    )

    private val data = ByteBuffer.allocateDirect(64)

    private fun SegmentedMuxer.video(ptsUs: Long, key: Boolean = false) {
        nowNs = ptsUs * 1_000
        writeVideo(data, 0, 16, ptsUs, if (key) KEY else 0)
    }

    private fun SegmentedMuxer.audio(ptsUs: Long) = writeAudio(data, 0, 8, ptsUs, 0)

    @Test
    fun `zero samples - nothing is stopped, no file is left, finish does not throw`() {
        val m = muxer(expectAudio = true)
        m.setVideoFormat(VIDEO)
        m.setAudioFormat(AUDIO)
        val summary = m.finish()
        assertThat(writers).isEmpty()
        assertThat(events.finished).isEmpty()
        assertThat(summary.segmentsFinished).isEqualTo(0)
        assertThat(summary.videoSamplesIn).isEqualTo(0)
        assertThat(tmp.root.listFiles()!!.toList()).isEmpty()
    }

    @Test
    fun `no formats at all - finish reports and does not throw`() {
        val m = muxer(expectAudio = true)
        m.audio(0) // ignored: no audio format was ever delivered in this test either
        val summary = m.finish()
        assertThat(summary.videoFormat).isFalse()
        assertThat(summary.segmentsFinished).isEqualTo(0)
    }

    @Test
    fun `audio and video - one segment with both tracks`() {
        val m = muxer(expectAudio = true)
        m.setVideoFormat(VIDEO)
        m.setAudioFormat(AUDIO)
        m.video(0, key = true)
        m.audio(10_000)
        for (i in 1..30) {
            m.video(i * 33_333L)
            m.audio(10_000 + i * 21_333L)
        }
        m.finish()
        assertThat(writers).hasSize(1)
        val w = writers.single()
        assertThat(w.tracks).containsExactly(VIDEO, AUDIO).inOrder()
        assertThat(w.count(VIDEO)).isEqualTo(31)
        assertThat(w.count(AUDIO)).isEqualTo(31)
        assertThat(w.stopped).isTrue()
        assertThat(events.finished.single().firstAudioLocalUs).isEqualTo(10_000)
        assertThat(m.hasAudioTrack).isTrue()
    }

    @Test
    fun `mic yields nothing - take continues video only after the wait`() {
        val m = muxer(expectAudio = true)
        m.setVideoFormat(VIDEO)
        // No audio format and no audio samples at all.
        for (i in 0..90) m.video(i * 33_333L, key = i == 0)
        m.finish()
        val w = writers.single()
        assertThat(w.tracks).containsExactly(VIDEO)
        assertThat(w.count(VIDEO)).isEqualTo(91) // frames from before the timeout are kept too
        assertThat(w.stopped).isTrue()
        assertThat(events.audioDropped).isEqualTo(1)
        assertThat(events.finished.single().firstAudioLocalUs).isEqualTo(-1)
        assertThat(m.hasAudioTrack).isFalse()
    }

    @Test
    fun `audio format arrives but no samples - video only, the audio track is never added empty`() {
        val m = muxer(expectAudio = true)
        m.setVideoFormat(VIDEO)
        m.setAudioFormat(AUDIO)
        for (i in 0..10) m.video(i * 33_333L, key = i == 0)
        // Stop before the wait elapsed (the lost-take case: format, zero audio samples).
        val summary = m.finish()
        val w = writers.single()
        assertThat(w.tracks).containsExactly(VIDEO)
        assertThat(w.stopped).isTrue()
        assertThat(summary.segmentsFinished).isEqualTo(1)
        assertThat(events.finished).hasSize(1)
    }

    @Test
    fun `a single video frame delivered at stop is still saved`() {
        // Reproduces the emulator take: nothing for 11 s, then one frame + formats while stopping.
        val m = muxer(expectAudio = true)
        m.setAudioFormat(AUDIO)
        m.setVideoFormat(VIDEO)
        m.video(0, key = true)
        val summary = m.finish()
        assertThat(summary.segmentsFinished).isEqualTo(1)
        assertThat(events.finished.single().durationUs).isEqualTo(33_333)
        assertThat(writers.single().tracks).containsExactly(VIDEO)
    }

    @Test
    fun `only audio arrived - no file, reported precisely, no throw`() {
        val m = muxer(expectAudio = true)
        m.setAudioFormat(AUDIO)
        repeat(50) { m.audio(it * 21_333L) }
        val summary = m.finish()
        assertThat(writers).isEmpty()
        assertThat(summary.audioSamplesIn).isEqualTo(50)
        assertThat(summary.videoSamplesWritten).isEqualTo(0)
        assertThat(summary.segmentsFinished).isEqualTo(0)
        assertThat(summary.describe()).contains("video format=false")
    }

    @Test
    fun `audio samples buffered before the video format are kept`() {
        val m = muxer(expectAudio = true)
        m.setAudioFormat(AUDIO)
        m.audio(5_000)
        m.audio(26_333)
        m.setVideoFormat(VIDEO)
        m.video(0, key = true)
        m.video(33_333)
        m.finish()
        val w = writers.single()
        assertThat(w.tracks).containsExactly(VIDEO, AUDIO).inOrder()
        assertThat(w.count(AUDIO)).isEqualTo(2)
    }

    @Test
    fun `audio failing mid take - later segments are video only and every segment finalizes`() {
        // 500 ms segments, a key frame every 15 frames; the microphone fails 2/3 into segment 1.
        val m = muxer(expectAudio = true, segmentUs = 500_000)
        m.setVideoFormat(VIDEO)
        m.setAudioFormat(AUDIO)
        var audioPts = 0L
        for (i in 0..90) {
            val pts = i * 33_334L
            m.video(pts, key = i % 15 == 0)
            if (i < 25) {
                while (audioPts <= pts) {
                    m.audio(audioPts)
                    audioPts += 21_333
                }
            }
            if (i == 25) m.disableAudio()
        }
        m.finish()
        assertThat(events.errors).isEqualTo(0)
        assertThat(writers.all { it.stopped }).isTrue()
        assertThat(events.finished.map { it.index }).containsExactly(0, 1, 2, 3, 4, 5, 6).inOrder()
        assertThat(events.finished.take(2).map { it.firstAudioLocalUs >= 0 }).containsExactly(true, true)
        assertThat(events.finished.drop(2).map { it.firstAudioLocalUs }.distinct()).containsExactly(-1L)
    }

    @Test
    fun `video only recording never waits for audio`() {
        val m = muxer(expectAudio = false)
        m.setVideoFormat(VIDEO)
        m.video(0, key = true)
        assertThat(writers).hasSize(1) // created immediately
        m.audio(1_000) // ignored
        m.finish()
        assertThat(writers.single().tracks).containsExactly(VIDEO)
        assertThat(events.audioDropped).isEqualTo(0)
    }

    @Test
    fun `finish is idempotent and never stops a writer twice`() {
        val m = muxer(expectAudio = false)
        m.setVideoFormat(VIDEO)
        m.video(0, key = true)
        m.finish()
        m.finish()
        assertThat(writers.single().stopCalls).isEqualTo(1)
        assertThat(events.finished).hasSize(1)
    }

    private companion object {
        const val VIDEO = "video/avc"
        const val AUDIO = "audio/mp4a-latm"
        const val KEY = 1 // MediaCodec.BUFFER_FLAG_KEY_FRAME
    }
}
