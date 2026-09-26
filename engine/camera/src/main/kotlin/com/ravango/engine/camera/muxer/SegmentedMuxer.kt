package com.ravango.engine.camera.muxer

import android.media.MediaCodec
import android.media.MediaFormat
import com.ravango.core.common.diagnostics.Diagnostics
import com.ravango.core.common.log.RgLog
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * Writes encoded audio/video into a series of self-contained MP4 segments (~[segmentDurationUs] each), cutting at
 * key frames (see [SegmentPlanner]). Each finished segment is reported to [listener] so it can be recorded in the
 * crash-safe manifest. Thread-safe: video and audio arrive from different encoder threads.
 *
 * Robustness rules (a take must never be lost because one track misbehaves):
 * - Muxing starts with the first video key frame (audio received before it is kept); audio never blocks video.
 * - A segment file is only created once it has video, and it only gets an audio track once an audio sample for it
 *   arrived. If the microphone yields nothing within [audioWaitUs], the take continues video-only
 *   ([Listener.onAudioDropped]). So no track is ever left without samples (MediaMuxer.stop() fails on those and
 *   the whole segment would be lost).
 * - A segment without samples is deleted, never stopped. [finish] never throws and reports what was received.
 */
internal class SegmentedMuxer(
    private val dir: File,
    expectAudio: Boolean,
    segmentDurationUs: Long,
    private val frameDurationUs: Long,
    private val listener: Listener,
    private val writerFactory: MuxerWriterFactory = MediaMuxerWriterFactory,
    private val audioWaitUs: Long = AUDIO_WAIT_US,
    private val clockNs: () -> Long = System::nanoTime,
) {
    interface Listener {
        fun onSegmentFinished(entry: SegmentEntry)
        fun onRequestKeyFrame()
        fun onMuxerError(error: Exception)
        fun onAudioDropped()
    }

    private class Sample(val isVideo: Boolean, val data: ByteBuffer, val ptsUs: Long, val flags: Int)

    private class Segment(val index: Int, val file: File, val baseUs: Long, val openedNs: Long) {
        var writer: MuxerWriter? = null
        var videoTrack = -1
        var audioTrack = -1
        val stash = ArrayList<Sample>()
        var stashBytes = 0L
        var firstStashVideoUs = -1L
        var lastStashVideoUs = -1L
        var videoSamples = 0
        var audioSamples = 0
        var lastVideoLocalUs = -1L
        var firstAudioLocalUs = -1L
    }

    private val lock = Any()
    private val planner = SegmentPlanner(segmentDurationUs, expectAudio)
    private val segments = HashMap<Int, Segment>(4)
    private val pending = ArrayList<Sample>()
    private var pendingBytes = 0L
    private var videoFormat: Any? = null
    private var audioFormat: Any? = null
    private var expectAudio = expectAudio
    private var started = false
    private var failed = false
    private var finished = false
    private var videoIn = 0L
    private var audioIn = 0L
    private var videoWritten = 0L
    private var audioWritten = 0L
    private var segmentsFinished = 0
    private var segmentsDiscarded = 0

    val bytesWritten = AtomicLong(0)

    /** Recording time of the last video frame written (µs), for the duration display. */
    @Volatile var lastVideoPtsUs: Long = -1
        private set

    /** [clockNs] time of the last encoded video sample received, or -1 before the first one (stall watchdog). */
    @Volatile var lastVideoSampleNs: Long = -1
        private set

    /** True once audio samples were written into the file. */
    val hasAudioTrack: Boolean get() = synchronized(lock) { audioWritten > 0 }

    fun onVideoFormat(format: MediaFormat) = setVideoFormat(format)
    fun onAudioFormat(format: MediaFormat) = setAudioFormat(format)

    fun setVideoFormat(format: Any) = synchronized(lock) {
        if (videoFormat == null) videoFormat = format
        guarded { maybeStart() }
    }

    fun setAudioFormat(format: Any) = synchronized(lock) {
        if (audioFormat == null) audioFormat = format
    }

    /** The audio source failed; continue video-only. */
    fun disableAudio() = synchronized(lock) {
        if (!expectAudio) return@synchronized
        expectAudio = false
        dropPendingAudio()
        if (!started) return@synchronized
        guarded {
            val close = planner.disableAudio().close
            if (close >= 0) closeSegment(close)
            // Segments waiting for their first audio sample can go ahead video-only now.
            segments.values.toList().forEach { maybeMaterialize(it, force = false) }
        }
    }

    fun writeVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) =
        writeVideo(buffer, info.offset, info.size, info.presentationTimeUs, info.flags)

    fun writeAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo) =
        writeAudio(buffer, info.offset, info.size, info.presentationTimeUs, info.flags)

    fun writeVideo(buffer: ByteBuffer, offset: Int, size: Int, ptsUs: Long, flags: Int) {
        var requestKey = false
        synchronized(lock) {
            if (failed || finished) return
            videoIn++
            lastVideoSampleNs = clockNs()
            if (!started) {
                stashPending(true, buffer, offset, size, ptsUs, flags)
                guarded { maybeStart() }
                return
            }
            requestKey = routeVideo(buffer, offset, size, ptsUs, flags)
        }
        if (requestKey) listener.onRequestKeyFrame()
    }

    fun writeAudio(buffer: ByteBuffer, offset: Int, size: Int, ptsUs: Long, flags: Int) = synchronized(lock) {
        if (failed || finished || !expectAudio) return@synchronized
        audioIn++
        if (!started) {
            stashPending(false, buffer, offset, size, ptsUs, flags)
            return@synchronized
        }
        val route = planner.routeAudio(ptsUs)
        apply(route.open, route.openBaseUs, route.segment, route.localPtsUs, route.close, false, buffer, offset, size, flags)
    }

    /**
     * Closes all open segments (each finished one is reported to the listener). Never throws; the summary says what
     * was received and written (zero finished segments = nothing playable).
     */
    fun finish(): MuxSummary = synchronized(lock) {
        if (!finished) {
            finished = true
            try {
                planner.openSegments().forEach { closeSegment(it) }
                segments.keys.toList().forEach { closeSegment(it) }
            } catch (e: Exception) {
                RgLog.e(TAG, "closing segments failed", e)
            }
            pending.clear()
            pendingBytes = 0
            if (segmentsFinished == 0) Diagnostics.record(TAG, "take produced no playable segment: ${summaryLocked().describe()}")
        }
        summaryLocked()
    }

    fun summary(): MuxSummary = synchronized(lock) { summaryLocked() }

    private fun summaryLocked() = MuxSummary(
        videoFormat = videoFormat != null,
        audioFormat = audioFormat != null,
        videoSamplesIn = videoIn,
        audioSamplesIn = audioIn,
        videoSamplesWritten = videoWritten,
        audioSamplesWritten = audioWritten,
        segmentsFinished = segmentsFinished,
        segmentsDiscarded = segmentsDiscarded,
    )

    // --- routing (under lock) -------------------------------------------------------------------------------

    private fun routeVideo(buffer: ByteBuffer, offset: Int, size: Int, ptsUs: Long, flags: Int): Boolean {
        val key = flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        val route = planner.routeVideo(ptsUs, key)
        // The planner reuses its Route object: copy the fields before anything else may call it.
        val segment = route.segment
        val requestKey = route.requestKeyFrame
        apply(route.open, route.openBaseUs, segment, route.localPtsUs, route.close, true, buffer, offset, size, flags)
        if (segment >= 0) lastVideoPtsUs = ptsUs
        return requestKey
    }

    @Suppress("LongParameterList")
    private fun apply(
        open: Int,
        openBaseUs: Long,
        segment: Int,
        localPtsUs: Long,
        close: Int,
        isVideo: Boolean,
        buffer: ByteBuffer,
        offset: Int,
        size: Int,
        flags: Int,
    ) = guarded {
        if (open >= 0) segments[open] = Segment(open, File(dir, segmentName(open)), openBaseUs, clockNs())
        if (segment >= 0) segments[segment]?.let { write(it, isVideo, buffer, offset, size, localPtsUs, flags) }
        if (close >= 0) closeSegment(close)
    }

    private fun write(seg: Segment, isVideo: Boolean, buffer: ByteBuffer, offset: Int, size: Int, localPtsUs: Long, flags: Int) {
        if (seg.writer != null) {
            writeNow(seg, isVideo, buffer, offset, size, localPtsUs, flags)
            return
        }
        seg.stash += copy(isVideo, buffer, offset, size, localPtsUs, flags)
        seg.stashBytes += size
        if (isVideo) {
            if (seg.firstStashVideoUs < 0) seg.firstStashVideoUs = localPtsUs
            seg.lastStashVideoUs = localPtsUs
        }
        maybeMaterialize(seg, force = false)
    }

    private fun writeNow(seg: Segment, isVideo: Boolean, buffer: ByteBuffer, offset: Int, size: Int, localPtsUs: Long, flags: Int) {
        val w = seg.writer ?: return
        val track = if (isVideo) seg.videoTrack else seg.audioTrack
        if (track < 0) return
        w.writeSample(track, buffer, offset, size, localPtsUs, flags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
        bytesWritten.addAndGet(size.toLong())
        if (isVideo) {
            seg.videoSamples++
            videoWritten++
            seg.lastVideoLocalUs = localPtsUs
        } else {
            seg.audioSamples++
            audioWritten++
            if (seg.firstAudioLocalUs < 0) seg.firstAudioLocalUs = localPtsUs
        }
    }

    /**
     * Creates the segment's file once its track set is known: video plus audio when an audio sample arrived for it,
     * video-only when audio is not expected, or when it did not arrive within [audioWaitUs] (or [force]).
     */
    private fun maybeMaterialize(seg: Segment, force: Boolean) {
        if (seg.writer != null) return
        if (seg.stash.none { it.isVideo }) {
            if (force) {
                seg.stash.clear()
                seg.stashBytes = 0
            }
            return
        }
        val wantAudio = expectAudio && planner.hasAudio
        val stashedAudio = seg.stash.any { !it.isVideo }
        val waited = seg.lastStashVideoUs - seg.firstStashVideoUs >= audioWaitUs ||
            clockNs() - seg.openedNs >= audioWaitUs * 1_000 ||
            seg.stashBytes >= MAX_STASH_BYTES
        if (wantAudio && !stashedAudio && !force && !waited) return
        val withAudio = wantAudio && stashedAudio && audioFormat != null
        if (wantAudio && !withAudio && !force) {
            // The microphone produced nothing for this segment in time: keep recording video rather than stall.
            RgLog.w(TAG, "no audio within ${audioWaitUs / 1000}ms for segment ${seg.index}; continuing video only")
            Diagnostics.record(TAG, "no audio samples within ${audioWaitUs / 1000}ms (format=${audioFormat != null}); take continues video-only")
            expectAudio = false
            dropPendingAudio()
            val close = planner.disableAudio().close
            listener.onAudioDropped()
            if (close >= 0 && close != seg.index) closeSegment(close)
        }
        val w = writerFactory.create(seg.file)
        try {
            seg.videoTrack = w.addTrack(videoFormat!!)
            if (withAudio) seg.audioTrack = w.addTrack(audioFormat!!)
            w.start()
        } catch (e: Exception) {
            runCatching { w.release() }
            seg.file.delete()
            throw e
        }
        seg.writer = w
        RgLog.d(TAG, "segment ${seg.index} opened at ${seg.baseUs / 1000}ms (audio=$withAudio)")
        val stash = ArrayList(seg.stash)
        seg.stash.clear()
        seg.stashBytes = 0
        for (s in stash) writeNow(seg, s.isVideo, s.data, 0, s.data.limit(), s.ptsUs, s.flags)
    }

    private fun closeSegment(index: Int) {
        val seg = segments.remove(index) ?: return
        if (seg.writer == null) {
            try {
                maybeMaterialize(seg, force = true)
            } catch (e: Exception) {
                RgLog.e(TAG, "segment $index could not be created", e)
            }
        }
        val w = seg.writer
        if (w == null || seg.videoSamples == 0) {
            // Nothing (or no video) was written: never stop() such a muxer, just drop the file.
            if (w != null) runCatching { w.release() }
            seg.file.delete()
            segmentsDiscarded++
            RgLog.w(TAG, "segment $index discarded (video=${seg.videoSamples}, audio=${seg.audioSamples} samples)")
            return
        }
        val ok = try {
            w.stop()
            true
        } catch (e: Exception) {
            Diagnostics.record(TAG, "segment $index could not be finalized (video=${seg.videoSamples}, audio=${seg.audioSamples} samples)", e)
            false
        }
        runCatching { w.release() }
        if (ok && seg.lastVideoLocalUs >= 0) {
            segmentsFinished++
            listener.onSegmentFinished(
                SegmentEntry(
                    index = index,
                    file = seg.file.name,
                    baseUs = seg.baseUs,
                    durationUs = seg.lastVideoLocalUs + frameDurationUs,
                    firstAudioLocalUs = if (seg.audioTrack >= 0 && seg.audioSamples > 0) seg.firstAudioLocalUs else -1,
                ),
            )
        } else {
            seg.file.delete()
            segmentsDiscarded++
        }
    }

    /**
     * Starts routing once the video format and the first video key frame are there. Audio that arrived earlier
     * (the audio encoder usually outputs before the video encoder) is replayed into the first segment.
     */
    private fun maybeStart() {
        if (started || failed || videoFormat == null) return
        if (pending.none { it.isVideo && it.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 }) return
        started = true
        if (!expectAudio) planner.disableAudio()
        // Replay buffered samples in timestamp order (video first on ties: it opens the segment audio goes into).
        val samples = pending.sortedWith(compareBy<Sample> { it.ptsUs }.thenBy { if (it.isVideo) 0 else 1 })
        pending.clear()
        pendingBytes = 0
        for (s in samples) {
            if (failed) break
            if (s.isVideo) {
                routeVideo(s.data, 0, s.data.limit(), s.ptsUs, s.flags)
            } else if (expectAudio) {
                val route = planner.routeAudio(s.ptsUs)
                apply(route.open, route.openBaseUs, route.segment, route.localPtsUs, route.close, false, s.data, 0, s.data.limit(), s.flags)
            }
        }
    }

    private fun stashPending(isVideo: Boolean, buffer: ByteBuffer, offset: Int, size: Int, ptsUs: Long, flags: Int) {
        pending += copy(isVideo, buffer, offset, size, ptsUs, flags)
        pendingBytes += size
        while (pendingBytes > MAX_PENDING_BYTES && pending.isNotEmpty()) {
            pendingBytes -= pending.removeAt(0).data.limit()
        }
    }

    private fun dropPendingAudio() {
        pending.removeAll { !it.isVideo }
        pendingBytes = pending.sumOf { it.data.limit().toLong() }
    }

    private fun copy(isVideo: Boolean, buffer: ByteBuffer, offset: Int, size: Int, ptsUs: Long, flags: Int): Sample {
        val copy = ByteBuffer.allocateDirect(size)
        val src = buffer.duplicate()
        src.clear()
        src.position(offset)
        src.limit(offset + size)
        copy.put(src)
        copy.flip()
        return Sample(isVideo, copy, ptsUs, flags)
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            if (!failed) {
                failed = true
                RgLog.e(TAG, "mux write failed", e)
                listener.onMuxerError(e)
            }
        }
    }

    companion object {
        private const val TAG = "SegmentedMuxer"

        /** How long a segment waits for its first audio sample before going video-only. */
        const val AUDIO_WAIT_US = 2_000_000L
        private const val MAX_STASH_BYTES = 32L * 1024 * 1024
        private const val MAX_PENDING_BYTES = 8L * 1024 * 1024

        fun segmentName(index: Int): String = "segment_%04d.mp4".format(java.util.Locale.US, index)
    }
}
