package com.ravango.engine.ai.video

import com.ravango.core.media.dsp.TimeRange
import com.ravango.core.model.SubtitleCue
import com.ravango.core.model.VideoClip
import com.ravango.core.model.WordTiming

/**
 * Maps source-time ranges of a main-track clip to timeline (output) time, accounting for the clip's position on the
 * storyline, its trim window, speed and reversal: `t = clipStart + (src − trimStart) / speed`
 * (reversed: `t = clipStart + (trimEnd − src) / speed`). Ranges outside the trim window are clipped or dropped.
 */
class TimelineMapper(private val clip: VideoClip, private val clipStartUs: Long) {

    private val trimStart = clip.trimStartUs
    private val trimEnd = clip.trimStartUs + clip.sourceDurationUs
    private val speed = clip.speed.toDouble().coerceAtLeast(0.01)

    fun toTimeline(sourceUs: Long): Long {
        val src = sourceUs.coerceIn(trimStart, trimEnd)
        val offset = if (clip.reversed) trimEnd - src else src - trimStart
        return clipStartUs + (offset / speed).toLong()
    }

    /** Null when the range lies entirely outside the trim window. */
    fun toTimeline(range: TimeRange): TimeRange? {
        val s = range.startUs.coerceAtLeast(trimStart)
        val e = range.endUs.coerceAtMost(trimEnd)
        if (e <= s) return null
        val a = toTimeline(s)
        val b = toTimeline(e)
        return TimeRange(minOf(a, b), maxOf(a, b))
    }

    fun toTimeline(cue: SubtitleCue): SubtitleCue? {
        val range = toTimeline(TimeRange(cue.startUs, cue.endUs)) ?: return null
        val words = cue.words.mapNotNull { w ->
            toTimeline(TimeRange(w.startUs, w.endUs))?.let { WordTiming(w.text, it.startUs, it.endUs) }
        }.let { if (clip.reversed) it.reversed() else it }
        return cue.copy(startUs = range.startUs, endUs = range.endUs, words = words)
    }
}
