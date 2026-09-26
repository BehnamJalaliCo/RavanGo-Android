package com.ravango.engine.editor.timeline

import com.ravango.core.model.EditorDocument
import com.ravango.core.model.MediaKind
import com.ravango.core.model.VideoClip
import kotlin.math.abs
import kotlin.math.roundToLong

/** Where a main-track clip sits on the output timeline. `[startUs, endUs)` in timeline (output) time. */
data class ClipPlacement(val clip: VideoClip, val index: Int, val startUs: Long, val endUs: Long) {
    val durationUs: Long get() = endUs - startUs
    operator fun contains(timeUs: Long): Boolean = timeUs in startUs until endUs
}

/**
 * Pure timeline arithmetic shared by the composition builder, the edit operations and the UI.
 *
 * Conventions: all times are microseconds. "Source time" is a position inside the clip's media file,
 * "timeline time" is the position in the edited output (after trims and speed changes).
 */
object TimelineMath {
    const val MIN_SPEED = 0.25f
    const val MAX_SPEED = 4f

    /** Shortest clip the editor allows after trim/split (in timeline time). */
    const val MIN_CLIP_US = 100_000L

    fun placements(doc: EditorDocument): List<ClipPlacement> = placements(doc.mainTrack)

    fun placements(clips: List<VideoClip>): List<ClipPlacement> {
        var t = 0L
        return clips.mapIndexed { i, c ->
            val start = t
            t += c.outputDurationUs
            ClipPlacement(c, i, start, t)
        }
    }

    fun placementOf(doc: EditorDocument, clipId: String): ClipPlacement? = placements(doc).firstOrNull { it.clip.id == clipId }

    /** The clip showing at [timeUs]. The very end of the timeline maps to the last clip. */
    fun clipAt(doc: EditorDocument, timeUs: Long): ClipPlacement? {
        val all = placements(doc)
        if (all.isEmpty()) return null
        if (timeUs >= all.last().endUs) return all.last()
        if (timeUs <= 0) return all.first()
        return all.firstOrNull { timeUs in it }
    }

    fun outputDurationUs(sourceDurationUs: Long, speed: Float): Long = (sourceDurationUs / speed.toDouble()).roundToLong()

    /**
     * Maps a timeline position inside the clip placed at [clipStartUs] to source time. Reversed clips play
     * their trim window backwards, so the start of the placement shows [VideoClip.trimEndUs].
     */
    fun timelineToSource(clip: VideoClip, clipStartUs: Long, timelineUs: Long): Long {
        val offset = (timelineUs - clipStartUs).coerceIn(0, clip.outputDurationUs)
        val sourceOffset = (offset * clip.speed.toDouble()).roundToLong()
        if (clip.source.kind == MediaKind.IMAGE) return 0
        return if (clip.reversed) (clip.trimEndUs - sourceOffset).coerceAtLeast(clip.trimStartUs)
        else (clip.trimStartUs + sourceOffset).coerceAtMost(clip.trimEndUs)
    }

    /** Inverse of [timelineToSource]; null when [sourceUs] is outside the clip's trim window. */
    fun sourceToTimeline(clip: VideoClip, clipStartUs: Long, sourceUs: Long): Long? {
        if (clip.source.kind == MediaKind.IMAGE) return null
        if (sourceUs < clip.trimStartUs || sourceUs > clip.trimEndUs) return null
        val sourceOffset = if (clip.reversed) clip.trimEndUs - sourceUs else sourceUs - clip.trimStartUs
        return clipStartUs + (sourceOffset / clip.speed.toDouble()).roundToLong()
    }

    /** Clamps a speed to the supported range and rounds it to 2 decimals (so presets compare exactly). */
    fun clampSpeed(speed: Float): Float = ((speed.coerceIn(MIN_SPEED, MAX_SPEED) * 100f).roundToLong() / 100f)

    /** Every edge the playhead and dragged items can snap to. Sorted and distinct. */
    fun snapPoints(doc: EditorDocument, includeItems: Boolean = true): LongArray {
        val points = ArrayList<Long>()
        points += 0L
        placements(doc).forEach { points += it.endUs }
        if (includeItems) {
            doc.overlayTracks.forEach { track -> track.items.forEach { points += it.startUs; points += it.endUs } }
            doc.audioTracks.forEach { track -> track.clips.forEach { points += it.startUs; points += it.endUs } }
            doc.subtitles.cues.forEach { points += it.startUs; points += it.endUs }
        }
        return points.distinct().sorted().toLongArray()
    }

    /** Returns the closest snap point within [thresholdUs] of [timeUs], or [timeUs] itself. */
    fun snap(timeUs: Long, points: LongArray, thresholdUs: Long): Long {
        if (points.isEmpty()) return timeUs
        var idx = points.binarySearch(timeUs)
        if (idx >= 0) return points[idx]
        idx = -idx - 1
        var best = timeUs
        var bestDist = thresholdUs + 1
        for (i in intArrayOf(idx - 1, idx)) {
            if (i in points.indices) {
                val d = abs(points[i] - timeUs)
                if (d <= thresholdUs && d < bestDist) {
                    best = points[i]; bestDist = d
                }
            }
        }
        return best
    }

    /** Frame duration for stepping the playhead. */
    fun frameDurationUs(frameRate: Float): Long = if (frameRate <= 1f) 33_333L else (1_000_000.0 / frameRate).roundToLong()
}
