package com.ravango.engine.editor

import com.ravango.core.model.EditorDocument
import com.ravango.core.model.MediaKind
import com.ravango.core.model.MediaSource
import com.ravango.core.model.SubtitleCue
import com.ravango.core.model.SubtitleTrack
import com.ravango.core.model.VideoClip

internal const val S = 1_000_000L

internal fun video(uri: String = "file:///a.mp4", durationUs: Long = 10 * S) =
    MediaSource(uri = uri, kind = MediaKind.VIDEO, durationUs = durationUs, width = 1080, height = 1920)

internal fun image(uri: String = "file:///p.png") = MediaSource(uri = uri, kind = MediaKind.IMAGE, durationUs = 0, width = 1000, height = 1000, hasAudio = false)

internal fun clip(id: String, start: Long = 0, end: Long = 10 * S, speed: Float = 1f, reversed: Boolean = false, uri: String = "file:///$id.mp4") =
    VideoClip(id = id, source = video(uri), trimStartUs = start, trimEndUs = end, speed = speed, reversed = reversed)

internal fun doc(vararg clips: VideoClip, cues: List<SubtitleCue> = emptyList()) =
    EditorDocument(id = "doc", mainTrack = clips.toList(), subtitles = SubtitleTrack(cues = cues))
