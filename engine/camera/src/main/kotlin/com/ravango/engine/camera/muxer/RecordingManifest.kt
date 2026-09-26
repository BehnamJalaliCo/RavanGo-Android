package com.ravango.engine.camera.muxer

import com.ravango.core.model.CaptureMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** One finished (playable) segment of a recording. Times are on the recording timeline (µs). */
@Serializable
data class SegmentEntry(
    val index: Int,
    val file: String,
    /** Recording time of the segment's first video frame (its local PTS 0). */
    val baseUs: Long,
    /** Local PTS of the last video frame + one frame duration. */
    val durationUs: Long,
    /** Local PTS of the first audio sample, or -1 when the segment has no audio. */
    val firstAudioLocalUs: Long = -1,
)

/**
 * Crash-safe description of a recording in progress, stored next to its segments. Rewritten atomically after every
 * finished segment so an interrupted recording can be rebuilt from the segments listed here.
 */
@Serializable
data class RecordingManifest(
    val id: String,
    val createdAt: Long,
    /** File name of the final MP4 inside the recordings directory. */
    val finalName: String,
    val captureMode: CaptureMode,
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val videoMime: String,
    val hasAudio: Boolean,
    val segments: List<SegmentEntry> = emptyList(),
) {
    val totalDurationUs: Long get() = segments.maxOfOrNull { it.baseUs + it.durationUs } ?: 0L
}

object ManifestCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    const val FILE_NAME = "manifest.json"

    fun encode(manifest: RecordingManifest): String = json.encodeToString(RecordingManifest.serializer(), manifest)

    fun decode(text: String): RecordingManifest? = runCatching { json.decodeFromString(RecordingManifest.serializer(), text) }.getOrNull()

    fun read(dir: File): RecordingManifest? {
        val file = File(dir, FILE_NAME)
        if (!file.exists()) return null
        return runCatching { decode(file.readText()) }.getOrNull()
    }

    /** Writes via a temp file + rename so a crash never leaves a half-written manifest. */
    fun write(dir: File, manifest: RecordingManifest) {
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.writeText(encode(manifest))
        val target = File(dir, FILE_NAME)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }
}

/**
 * Timestamp math for lossless concatenation. Segment files start their video at local 0; extractors may report
 * track start times with or without edit-list offsets, so we re-derive every sample time from the manifest.
 */
object ConcatTimeline {
    /**
     * @param sampleTimeUs time reported by the extractor for the sample.
     * @param trackFirstUs first sample time the extractor reports for the same track in this segment.
     * @param firstVideoUs first video sample time the extractor reports in this segment.
     */
    fun outputPtsUs(entry: SegmentEntry, isAudio: Boolean, sampleTimeUs: Long, trackFirstUs: Long, firstVideoUs: Long): Long {
        val relative = sampleTimeUs - trackFirstUs
        return if (!isAudio) {
            entry.baseUs + relative
        } else {
            val audioStartLocal = if (entry.firstAudioLocalUs >= 0) entry.firstAudioLocalUs else (trackFirstUs - firstVideoUs).coerceAtLeast(0)
            entry.baseUs + audioStartLocal + relative
        }
    }

    /** Ensures strictly increasing output timestamps per track (muxers reject anything else). */
    fun monotonic(candidateUs: Long, lastUs: Long): Long = if (candidateUs <= lastUs) lastUs + 1 else candidateUs
}
