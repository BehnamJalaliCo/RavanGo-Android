package com.ravango.engine.camera.muxer

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import com.ravango.core.common.log.RgLog
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * Writes encoded audio/video into a series of self-contained MP4 segments (~[segmentDurationUs] each), cutting at
 * key frames (see [SegmentPlanner]). Each finished segment is reported to [listener] so it can be recorded in the
 * crash-safe manifest. Thread-safe: video and audio arrive from different encoder threads.
 */
internal class SegmentedMuxer(
    private val dir: File,
    expectAudio: Boolean,
    segmentDurationUs: Long,
    private val frameDurationUs: Long,
    private val listener: Listener,
) {
    interface Listener {
        fun onSegmentFinished(entry: SegmentEntry)
        fun onRequestKeyFrame()
        fun onMuxerError(error: Exception)
        fun onAudioDropped()
    }

    private class OpenSegment(
        val index: Int,
        val file: File,
        val muxer: MediaMuxer,
        val videoTrack: Int,
        val audioTrack: Int,
        val baseUs: Long,
    ) {
        var lastVideoLocalUs = -1L
        var firstAudioLocalUs = -1L
        var samples = 0
    }

    private class PendingSample(val isVideo: Boolean, val data: ByteBuffer, val ptsUs: Long, val flags: Int)

    private val lock = Any()
    private val planner = SegmentPlanner(segmentDurationUs, expectAudio)
    private val open = HashMap<Int, OpenSegment>(4)
    private val pending = ArrayList<PendingSample>()
    private val writeInfo = MediaCodec.BufferInfo()
    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null
    private var expectAudio = expectAudio
    private var started = false
    private var failed = false
    private var firstPendingPtsUs = -1L

    val bytesWritten = AtomicLong(0)

    /** Recording time of the last video frame written (µs), for the duration display. */
    @Volatile var lastVideoPtsUs: Long = -1
        private set

    val hasAudioTrack: Boolean get() = synchronized(lock) { expectAudio && audioFormat != null }

    fun onVideoFormat(format: MediaFormat) = synchronized(lock) {
        if (videoFormat == null) videoFormat = format
        maybeStart()
    }

    fun onAudioFormat(format: MediaFormat) = synchronized(lock) {
        if (audioFormat == null) audioFormat = format
        maybeStart()
    }

    /** The audio source failed; continue video-only. */
    fun disableAudio() = synchronized(lock) {
        if (!expectAudio) return@synchronized
        expectAudio = false
        if (!started) {
            pending.removeAll { !it.isVideo }
            maybeStart()
        } else {
            val route = planner.disableAudio()
            if (route.close >= 0) closeSegment(route.close)
        }
    }

    fun writeVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        var requestKey = false
        synchronized(lock) {
            if (failed) return
            val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
            if (!started) {
                stash(true, buffer, info)
                return
            }
            val route = planner.routeVideo(info.presentationTimeUs, key)
            requestKey = route.requestKeyFrame
            apply(route, buffer, info, isVideo = true)
            if (route.segment >= 0) lastVideoPtsUs = info.presentationTimeUs
        }
        if (requestKey) listener.onRequestKeyFrame()
    }

    fun writeAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo) = synchronized(lock) {
        if (failed || !expectAudio) return@synchronized
        if (!started) {
            stash(false, buffer, info)
            return@synchronized
        }
        val route = planner.routeAudio(info.presentationTimeUs)
        apply(route, buffer, info, isVideo = false)
    }

    /** Closes all open segments (each finished one is reported to the listener). */
    fun finish() = synchronized(lock) {
        if (!started && pending.isNotEmpty() && videoFormat != null) {
            // Audio format never arrived: salvage the video.
            expectAudio = false
            maybeStart()
        }
        planner.openSegments().forEach { closeSegment(it) }
        open.values.toList().forEach { closeSegment(it.index) }
        pending.clear()
    }

    private fun apply(route: SegmentPlanner.Route, buffer: ByteBuffer, info: MediaCodec.BufferInfo, isVideo: Boolean) {
        try {
            if (route.open >= 0) openSegment(route.open, route.openBaseUs)
            if (route.segment >= 0) {
                val seg = open[route.segment]
                if (seg != null) {
                    val track = if (isVideo) seg.videoTrack else seg.audioTrack
                    if (track >= 0) {
                        writeInfo.set(info.offset, info.size, route.localPtsUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
                        seg.muxer.writeSampleData(track, buffer, writeInfo)
                        seg.samples++
                        bytesWritten.addAndGet(info.size.toLong())
                        if (isVideo) {
                            seg.lastVideoLocalUs = route.localPtsUs
                        } else if (seg.firstAudioLocalUs < 0) {
                            seg.firstAudioLocalUs = route.localPtsUs
                        }
                    }
                }
            }
            if (route.close >= 0) closeSegment(route.close)
        } catch (e: Exception) {
            failed = true
            RgLog.e(TAG, "mux write failed", e)
            listener.onMuxerError(e)
        }
    }

    private fun stash(isVideo: Boolean, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (firstPendingPtsUs < 0) firstPendingPtsUs = info.presentationTimeUs
        val copy = ByteBuffer.allocateDirect(info.size)
        val src = buffer.duplicate()
        src.position(info.offset)
        src.limit(info.offset + info.size)
        copy.put(src)
        copy.flip()
        pending += PendingSample(isVideo, copy, info.presentationTimeUs, info.flags)
        // The audio format should arrive within milliseconds; if it doesn't, record video-only rather than fail.
        if (isVideo && expectAudio && audioFormat == null && info.presentationTimeUs - firstPendingPtsUs > AUDIO_FORMAT_TIMEOUT_US) {
            RgLog.w(TAG, "no audio format after ${AUDIO_FORMAT_TIMEOUT_US / 1000}ms; recording video only")
            expectAudio = false
            pending.removeAll { !it.isVideo }
            listener.onAudioDropped()
            maybeStart()
        }
    }

    private fun maybeStart() {
        if (started || failed) return
        if (videoFormat == null || (expectAudio && audioFormat == null)) return
        started = true
        if (!expectAudio) planner.disableAudio()
        val info = MediaCodec.BufferInfo()
        // Replay buffered samples in timestamp order per track type.
        val samples = pending.sortedBy { it.ptsUs }
        pending.clear()
        for (s in samples) {
            info.set(0, s.data.remaining(), s.ptsUs, s.flags)
            if (s.isVideo) writeVideoLocked(s.data, info) else if (expectAudio) apply(planner.routeAudio(s.ptsUs), s.data, info, isVideo = false)
        }
    }

    private fun writeVideoLocked(data: ByteBuffer, info: MediaCodec.BufferInfo) {
        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        val route = planner.routeVideo(info.presentationTimeUs, key)
        apply(route, data, info, isVideo = true)
        if (route.segment >= 0) lastVideoPtsUs = info.presentationTimeUs
    }

    private fun openSegment(index: Int, baseUs: Long) {
        val file = File(dir, segmentName(index))
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val video = muxer.addTrack(videoFormat!!)
        val audio = if (expectAudio && audioFormat != null) muxer.addTrack(audioFormat!!) else -1
        muxer.start()
        open[index] = OpenSegment(index, file, muxer, video, audio, baseUs)
        RgLog.d(TAG, "segment $index opened at ${baseUs / 1000}ms")
    }

    private fun closeSegment(index: Int) {
        val seg = open.remove(index) ?: return
        val ok = runCatching {
            seg.muxer.stop()
            true
        }.onFailure { RgLog.e(TAG, "segment $index stop failed", it) }.getOrDefault(false)
        runCatching { seg.muxer.release() }
        if (ok && seg.samples > 0 && seg.lastVideoLocalUs >= 0) {
            listener.onSegmentFinished(
                SegmentEntry(
                    index = index,
                    file = seg.file.name,
                    baseUs = seg.baseUs,
                    durationUs = seg.lastVideoLocalUs + frameDurationUs,
                    firstAudioLocalUs = if (seg.audioTrack >= 0) seg.firstAudioLocalUs else -1,
                ),
            )
        } else {
            seg.file.delete()
        }
    }

    companion object {
        private const val TAG = "SegmentedMuxer"
        private const val AUDIO_FORMAT_TIMEOUT_US = 3_000_000L

        fun segmentName(index: Int): String = "segment_%04d.mp4".format(java.util.Locale.US, index)
    }
}
