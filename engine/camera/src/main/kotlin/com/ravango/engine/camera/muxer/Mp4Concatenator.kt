package com.ravango.engine.camera.muxer

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.ravango.core.common.log.RgLog
import java.io.File
import java.nio.ByteBuffer

/**
 * Lossless concatenation of segment MP4s (same codecs/parameters) into one file: samples are copied without
 * re-encoding, timestamps are rebuilt from the manifest so the result is continuous.
 */
internal object Mp4Concatenator {
    private const val TAG = "Mp4Concat"
    private const val MIN_BUFFER = 2 * 1024 * 1024

    /** Returns the output duration in µs. Throws on failure (the caller keeps the segments). */
    fun concat(dir: File, segments: List<SegmentEntry>, output: File): Long {
        require(segments.isNotEmpty()) { "no segments" }
        val tmp = File(output.parentFile, output.name + ".part")
        tmp.delete()
        val muxer = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var outVideo = -1
        var outAudio = -1
        var buffer = ByteBuffer.allocateDirect(MIN_BUFFER)
        val info = MediaCodec.BufferInfo()
        var lastVideo = -1L
        var lastAudio = -1L
        var endUs = 0L
        try {
            for ((i, entry) in segments.sortedBy { it.index }.withIndex()) {
                val file = File(dir, entry.file)
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.absolutePath)
                    var videoIn = -1
                    var audioIn = -1
                    for (t in 0 until extractor.trackCount) {
                        val mime = extractor.getTrackFormat(t).getString(MediaFormat.KEY_MIME) ?: continue
                        if (mime.startsWith("video/") && videoIn < 0) videoIn = t
                        if (mime.startsWith("audio/") && audioIn < 0) audioIn = t
                    }
                    if (videoIn < 0) {
                        RgLog.w(TAG, "segment ${entry.file} has no video; skipped")
                        continue
                    }
                    if (i == 0 || !muxerStarted) {
                        val vf = extractor.getTrackFormat(videoIn)
                        outVideo = muxer.addTrack(vf)
                        if (audioIn >= 0) outAudio = muxer.addTrack(extractor.getTrackFormat(audioIn))
                        muxer.start()
                        muxerStarted = true
                        buffer = ByteBuffer.allocateDirect(maxOf(MIN_BUFFER, maxInputSize(vf)))
                    }
                    val firstVideo = firstSampleTime(extractor, videoIn)
                    val firstAudio = if (audioIn >= 0) firstSampleTime(extractor, audioIn) else 0L
                    extractor.selectTrack(videoIn)
                    if (audioIn >= 0 && outAudio >= 0) extractor.selectTrack(audioIn)
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    while (true) {
                        val track = extractor.sampleTrackIndex
                        if (track < 0) break
                        val size = try {
                            extractor.readSampleData(buffer, 0)
                        } catch (e: IllegalArgumentException) {
                            buffer = ByteBuffer.allocateDirect(buffer.capacity() * 2)
                            continue
                        }
                        if (size < 0) break
                        val isAudio = track == audioIn
                        val time = extractor.sampleTime
                        var pts = ConcatTimeline.outputPtsUs(entry, isAudio, time, if (isAudio) firstAudio else firstVideo, firstVideo)
                        val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        if (isAudio) {
                            pts = ConcatTimeline.monotonic(pts, lastAudio)
                            lastAudio = pts
                            info.set(0, size, pts, flags)
                            muxer.writeSampleData(outAudio, buffer, info)
                        } else {
                            pts = ConcatTimeline.monotonic(pts, lastVideo)
                            lastVideo = pts
                            info.set(0, size, pts, flags)
                            muxer.writeSampleData(outVideo, buffer, info)
                        }
                        endUs = maxOf(endUs, pts)
                        extractor.advance()
                    }
                    endUs = maxOf(endUs, entry.baseUs + entry.durationUs)
                } finally {
                    extractor.release()
                }
            }
            check(muxerStarted) { "no playable segment" }
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
        }
        if (output.exists()) output.delete()
        check(tmp.renameTo(output)) { "rename failed" }
        return endUs
    }

    private fun firstSampleTime(extractor: MediaExtractor, track: Int): Long {
        extractor.selectTrack(track)
        extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val t = extractor.sampleTime.coerceAtLeast(0)
        extractor.unselectTrack(track)
        return t
    }

    private fun maxInputSize(format: MediaFormat): Int {
        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) return format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
        val w = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else 1920
        val h = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else 1080
        return w * h
    }
}
