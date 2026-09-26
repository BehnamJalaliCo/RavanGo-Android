package com.ravango.feature.editor

import com.ravango.core.model.MediaKind
import com.ravango.core.model.VideoClip
import com.ravango.core.model.WordTiming
import com.ravango.engine.ai.api.Transcript
import com.ravango.engine.ai.api.TranscriptSegment
import com.ravango.engine.editor.timeline.TimelineMath

/**
 * Converts transcripts produced per clip (source time) into timeline time, accounting for trims and speed, so the
 * subtitle builder receives one transcript that lines up with the edited video.
 */
object CaptionMapping {
    fun toTimeline(transcript: Transcript, clip: VideoClip, clipStartUs: Long): Transcript {
        if (clip.reversed || clip.source.kind != MediaKind.VIDEO) return Transcript(transcript.language, emptyList())
        fun map(t: Long): Long = TimelineMath.sourceToTimeline(clip, clipStartUs, t.coerceIn(clip.trimStartUs, clip.trimEndUs))!!
        val segments = transcript.segments.mapNotNull { seg ->
            if (seg.endUs <= clip.trimStartUs || seg.startUs >= clip.trimEndUs) return@mapNotNull null
            val words = seg.words
                .filter { it.endUs > clip.trimStartUs && it.startUs < clip.trimEndUs }
                .map { w -> WordTiming(w.text, map(w.startUs), map(w.endUs)) }
            val text = if (seg.words.isNotEmpty() && words.size != seg.words.size) words.joinToString(" ") { it.text } else seg.text
            if (text.isBlank()) null else TranscriptSegment(map(seg.startUs), map(seg.endUs), text, words)
        }
        return Transcript(transcript.language, segments)
    }

    /** Concatenates timeline transcripts (already non-overlapping) in time order. */
    fun merge(parts: List<Transcript>): Transcript =
        Transcript(parts.firstNotNullOfOrNull { it.language }, parts.flatMap { it.segments }.sortedBy { it.startUs })
}
