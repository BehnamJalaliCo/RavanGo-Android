package com.ravango.engine.editor.ops

import com.ravango.core.model.AudioClip
import com.ravango.core.model.AudioTrack
import com.ravango.core.model.AudioTrackKind
import com.ravango.core.model.CropRect
import com.ravango.core.model.EditorDocument
import com.ravango.core.model.MediaKind
import com.ravango.core.model.OverlayItem
import com.ravango.core.model.OverlayTrack
import com.ravango.core.model.SubtitleCue
import com.ravango.core.model.Transition
import com.ravango.core.model.VideoClip
import com.ravango.core.model.WordTiming
import com.ravango.core.model.newId
import com.ravango.engine.editor.timeline.ClipPlacement
import com.ravango.engine.editor.timeline.TimelineMath
import kotlin.math.roundToLong

/** Result of an edit: the new document plus the item that should be selected afterwards (if any). */
data class EditResult(val document: EditorDocument, val selectId: String? = null)

/** Which edge of a clip/item a trim gesture moves. */
enum class Edge { START, END }

/**
 * Pure, immutable edit operations on [EditorDocument]. Every function returns a new document (or null when the
 * edit is not applicable) so results can be pushed straight onto the undo history.
 *
 * Main-track edits "ripple": overlays, audio clips and subtitle cues that sit after the edited region shift with it,
 * which keeps captions and graphics attached to the footage they were placed on.
 */
object EditOps {

    // ---------------------------------------------------------------- main track

    /** Splits the main-track clip under [timeUs]. Returns null when too close to a clip edge. */
    fun split(doc: EditorDocument, timeUs: Long): EditResult? {
        val p = TimelineMath.clipAt(doc, timeUs) ?: return null
        val offset = timeUs - p.startUs
        if (offset < TimelineMath.MIN_CLIP_US || p.durationUs - offset < TimelineMath.MIN_CLIP_US) return null
        val (a, b) = splitClip(p.clip, offset) ?: return null
        val track = doc.mainTrack.toMutableList()
        track[p.index] = a
        track.add(p.index + 1, b)
        return EditResult(doc.copy(mainTrack = track), b.id)
    }

    /** Splits [clip] at [timelineOffsetUs] (relative to its placement start). */
    fun splitClip(clip: VideoClip, timelineOffsetUs: Long): Pair<VideoClip, VideoClip>? {
        if (timelineOffsetUs <= 0 || timelineOffsetUs >= clip.outputDurationUs) return null
        val sourceOffset = (timelineOffsetUs * clip.speed.toDouble()).roundToLong()
        val common = clip.copy(reversedUri = null)
        return if (clip.source.kind == MediaKind.IMAGE) {
            val first = common.copy(stillDurationUs = sourceOffset, audioFadeOutUs = 0, videoFadeOutUs = 0, transitionOut = Transition(), reversedUri = null)
            val second = common.copy(id = newId(), stillDurationUs = clip.stillDurationUs - sourceOffset, audioFadeInUs = 0, videoFadeInUs = 0)
            first to second
        } else if (!clip.reversed) {
            val cut = clip.trimStartUs + sourceOffset
            val first = common.copy(trimEndUs = cut, audioFadeOutUs = 0, videoFadeOutUs = 0, transitionOut = Transition())
            val second = common.copy(id = newId(), trimStartUs = cut, audioFadeInUs = 0, videoFadeInUs = 0)
            first to second
        } else {
            // Reversed: the first part on the timeline is the *end* of the source window.
            val cut = clip.trimEndUs - sourceOffset
            val first = common.copy(trimStartUs = cut, audioFadeOutUs = 0, videoFadeOutUs = 0, transitionOut = Transition())
            val second = common.copy(id = newId(), trimEndUs = cut, audioFadeInUs = 0, videoFadeInUs = 0)
            first to second
        }
    }

    /**
     * Trims one edge of a clip by a timeline delta (positive moves the edge right). Items after the clip ripple.
     */
    fun trimClip(doc: EditorDocument, clipId: String, edge: Edge, timelineDeltaUs: Long): EditorDocument? {
        val p = TimelineMath.placementOf(doc, clipId) ?: return null
        val c = p.clip
        val sourceDelta = (timelineDeltaUs * c.speed.toDouble()).roundToLong()
        val minSource = (TimelineMath.MIN_CLIP_US * c.speed).toLong()
        val updated = if (c.source.kind == MediaKind.IMAGE) {
            val d = if (edge == Edge.END) sourceDelta else -sourceDelta
            c.copy(stillDurationUs = (c.stillDurationUs + d).coerceIn(minSource, MAX_STILL_US))
        } else {
            // For reversed clips the timeline start shows trimEnd.
            val moveStartOfSource = (edge == Edge.START) != c.reversed
            val signed = if (c.reversed) -sourceDelta else sourceDelta
            if (moveStartOfSource) {
                c.copy(trimStartUs = (c.trimStartUs + signed).coerceIn(0, c.trimEndUs - minSource), reversedUri = null)
            } else {
                c.copy(trimEndUs = (c.trimEndUs + signed).coerceIn(c.trimStartUs + minSource, c.source.durationUs), reversedUri = null)
            }
        }
        if (updated == c) return null
        // Keep the reversed rendition when only the still/untouched window changed.
        val finalClip = if (c.reversed && (updated.trimStartUs != c.trimStartUs || updated.trimEndUs != c.trimEndUs)) updated else updated.copy(reversedUri = c.reversedUri)
        return replaceClipTrimmed(doc, p, finalClip, edge)
    }

    /** Sets the trim window directly (source time). */
    fun setTrim(doc: EditorDocument, clipId: String, trimStartUs: Long, trimEndUs: Long): EditorDocument? {
        val p = TimelineMath.placementOf(doc, clipId) ?: return null
        val c = p.clip
        if (c.source.kind == MediaKind.IMAGE) return null
        val start = trimStartUs.coerceIn(0, c.source.durationUs)
        val end = trimEndUs.coerceIn(start + (TimelineMath.MIN_CLIP_US * c.speed).toLong(), c.source.durationUs)
        if (start == c.trimStartUs && end == c.trimEndUs) return null
        val headOnly = end == c.trimEndUs
        val edge = if (headOnly != c.reversed) Edge.START else Edge.END
        return replaceClipTrimmed(doc, p, c.copy(trimStartUs = start, trimEndUs = end, reversedUri = null), edge)
    }

    fun deleteClip(doc: EditorDocument, clipId: String): EditResult? {
        val p = TimelineMath.placementOf(doc, clipId) ?: return null
        val track = doc.mainTrack.toMutableList().apply { removeAt(p.index) }
        val rippled = ripple(doc.copy(mainTrack = track), p.startUs, p.endUs, -p.durationUs, removeInside = true)
        val nextSelect = track.getOrNull(p.index)?.id ?: track.getOrNull(p.index - 1)?.id
        return EditResult(rippled, nextSelect)
    }

    fun duplicateClip(doc: EditorDocument, clipId: String): EditResult? {
        val p = TimelineMath.placementOf(doc, clipId) ?: return null
        val copy = p.clip.copy(id = newId())
        val track = doc.mainTrack.toMutableList().apply { add(p.index + 1, copy) }
        val rippled = ripple(doc.copy(mainTrack = track), p.endUs, p.endUs, p.durationUs, removeInside = false)
        return EditResult(rippled, copy.id)
    }

    /** Moves a clip to [toIndex]. Attached items are not moved (they stay at their timeline positions). */
    fun moveClip(doc: EditorDocument, clipId: String, toIndex: Int): EditorDocument? {
        val from = doc.mainTrack.indexOfFirst { it.id == clipId }
        if (from < 0) return null
        val target = toIndex.coerceIn(0, doc.mainTrack.lastIndex)
        if (target == from) return null
        val track = doc.mainTrack.toMutableList()
        val clip = track.removeAt(from)
        track.add(target, clip)
        return doc.copy(mainTrack = track)
    }

    /** True when [clipId] and the next clip are contiguous pieces of the same source (e.g. after a split). */
    fun canJoinWithNext(doc: EditorDocument, clipId: String): Boolean {
        val i = doc.mainTrack.indexOfFirst { it.id == clipId }
        if (i < 0 || i >= doc.mainTrack.lastIndex) return false
        return joinable(doc.mainTrack[i], doc.mainTrack[i + 1])
    }

    private fun joinable(a: VideoClip, b: VideoClip): Boolean {
        if (a.source.uri != b.source.uri || a.source.kind != b.source.kind) return false
        if (a.speed != b.speed || a.reversed != b.reversed) return false
        if (a.source.kind == MediaKind.IMAGE) return true
        val tolerance = 2_000L
        return if (!a.reversed) kotlin.math.abs(a.trimEndUs - b.trimStartUs) <= tolerance
        else kotlin.math.abs(a.trimStartUs - b.trimEndUs) <= tolerance
    }

    /** Joins the clip with the next one when they are adjacent pieces of the same source. */
    fun joinWithNext(doc: EditorDocument, clipId: String): EditResult? {
        val i = doc.mainTrack.indexOfFirst { it.id == clipId }
        if (!canJoinWithNext(doc, clipId)) return null
        val a = doc.mainTrack[i]
        val b = doc.mainTrack[i + 1]
        val merged = when {
            a.source.kind == MediaKind.IMAGE -> a.copy(stillDurationUs = a.stillDurationUs + b.stillDurationUs)
            !a.reversed -> a.copy(trimEndUs = b.trimEndUs)
            else -> a.copy(trimStartUs = b.trimStartUs, reversedUri = null)
        }.copy(audioFadeOutUs = b.audioFadeOutUs, videoFadeOutUs = b.videoFadeOutUs, transitionOut = b.transitionOut)
        val track = doc.mainTrack.toMutableList()
        track[i] = merged
        track.removeAt(i + 1)
        return EditResult(doc.copy(mainTrack = track), merged.id)
    }

    /** Inserts clips at a timeline position (splitting the clip under it when needed). */
    fun insertClips(doc: EditorDocument, clips: List<VideoClip>, atUs: Long): EditResult {
        if (clips.isEmpty()) return EditResult(doc)
        val base = split(doc, atUs)?.document ?: doc
        val placements = TimelineMath.placements(base)
        val index = placements.indexOfFirst { it.startUs >= atUs - 1 }.let { if (it < 0) base.mainTrack.size else it }
        val insertAt = placements.getOrNull(index)?.startUs ?: base.durationUs
        val track = base.mainTrack.toMutableList().apply { addAll(index, clips) }
        val added = clips.sumOf { it.outputDurationUs }
        val rippled = ripple(base.copy(mainTrack = track), insertAt, insertAt, added, removeInside = false)
        return EditResult(rippled, clips.first().id)
    }

    fun appendClips(doc: EditorDocument, clips: List<VideoClip>): EditResult =
        if (clips.isEmpty()) EditResult(doc) else EditResult(doc.copy(mainTrack = doc.mainTrack + clips), clips.first().id)

    /** Updates a clip in place. Duration changes (speed, still length) ripple the rest of the timeline. */
    fun updateClip(doc: EditorDocument, clipId: String, transform: (VideoClip) -> VideoClip): EditorDocument? {
        val p = TimelineMath.placementOf(doc, clipId) ?: return null
        val updated = transform(p.clip)
        if (updated == p.clip) return null
        return replaceClipRippling(doc, p, updated)
    }

    fun setSpeed(doc: EditorDocument, clipId: String, speed: Float): EditorDocument? =
        updateClip(doc, clipId) { it.copy(speed = TimelineMath.clampSpeed(speed)) }

    fun rotate90(doc: EditorDocument, clipId: String): EditorDocument? =
        updateClip(doc, clipId) { it.copy(rotationQuarterTurns = (it.rotationQuarterTurns + 1).mod(4)) }

    fun flip(doc: EditorDocument, clipId: String, horizontal: Boolean): EditorDocument? =
        updateClip(doc, clipId) { if (horizontal) it.copy(flipHorizontal = !it.flipHorizontal) else it.copy(flipVertical = !it.flipVertical) }

    fun setCrop(doc: EditorDocument, clipId: String, crop: CropRect): EditorDocument? {
        val l = crop.left.coerceIn(0f, 0.95f)
        val t = crop.top.coerceIn(0f, 0.95f)
        val r = crop.right.coerceIn(l + 0.05f, 1f)
        val b = crop.bottom.coerceIn(t + 0.05f, 1f)
        return updateClip(doc, clipId) { it.copy(crop = CropRect(l, t, r, b)) }
    }

    fun setTransition(doc: EditorDocument, clipId: String, transition: Transition): EditorDocument? {
        val i = doc.mainTrack.indexOfFirst { it.id == clipId }
        if (i < 0 || i == doc.mainTrack.lastIndex) return null
        return updateClip(doc, clipId) { it.copy(transitionOut = transition) }
    }

    /**
     * Replaces a clip whose duration changed without losing footage (speed, still length): items inside the clip are
     * scaled proportionally and items after it shift by the change.
     */
    private fun replaceClipRippling(doc: EditorDocument, p: ClipPlacement, updated: VideoClip): EditorDocument {
        val track = doc.mainTrack.toMutableList()
        track[p.index] = updated
        val next = doc.copy(mainTrack = track)
        val newEnd = p.startUs + updated.outputDurationUs
        return if (newEnd == p.endUs) next else scaleRegion(next, p.startUs, p.endUs, newEnd)
    }

    /** Replaces a clip after a trim of [edge]: removed footage takes attached items with it, added footage pushes them. */
    private fun replaceClipTrimmed(doc: EditorDocument, p: ClipPlacement, updated: VideoClip, edge: Edge): EditorDocument {
        val track = doc.mainTrack.toMutableList()
        track[p.index] = updated
        val next = doc.copy(mainTrack = track)
        val change = updated.outputDurationUs - p.durationUs
        if (change == 0L) return next
        return if (edge == Edge.END) {
            if (change > 0) ripple(next, p.endUs, p.endUs, change, removeInside = false)
            else ripple(next, p.endUs + change, p.endUs, change, removeInside = true)
        } else {
            if (change > 0) ripple(next, p.startUs, p.startUs, change, removeInside = false)
            else ripple(next, p.startUs, p.startUs - change, change, removeInside = true)
        }
    }

    /** Linearly remaps `[start, oldEnd]` to `[start, newEnd]` and shifts everything after by the difference. */
    fun scaleRegion(doc: EditorDocument, start: Long, oldEnd: Long, newEnd: Long): EditorDocument {
        val oldLen = (oldEnd - start).coerceAtLeast(1)
        val ratio = (newEnd - start).toDouble() / oldLen
        fun m(t: Long): Long = when {
            t <= start -> t
            t >= oldEnd -> t + (newEnd - oldEnd)
            else -> start + ((t - start) * ratio).roundToLong()
        }
        val overlays = doc.overlayTracks.map { tr -> tr.copy(items = tr.items.map { it.withTimes(m(it.startUs), maxOf(m(it.endUs), m(it.startUs) + 50_000)) }) }
        val audio = doc.audioTracks.map { tr -> tr.copy(clips = tr.clips.map { it.copy(startUs = m(it.startUs)) }) }
        val cues = doc.subtitles.cues.map { c ->
            c.copy(startUs = m(c.startUs), endUs = maxOf(m(c.endUs), m(c.startUs) + 50_000), words = c.words.map { w -> w.copy(startUs = m(w.startUs), endUs = m(w.endUs)) })
        }
        return doc.copy(overlayTracks = overlays, audioTracks = audio, subtitles = doc.subtitles.copy(cues = cues))
    }

    /**
     * Shifts every overlay, audio clip and subtitle cue that starts at or after [fromUs] by [deltaUs].
     * When [removeInside] is set, items entirely inside `[removedStartUs, fromUs)` are dropped and items that straddle
     * the removed range are shortened.
     */
    fun ripple(doc: EditorDocument, removedStartUs: Long, fromUs: Long, deltaUs: Long, removeInside: Boolean): EditorDocument {
        if (deltaUs == 0L) return doc
        val cutStart = removedStartUs
        val cutEnd = fromUs
        fun mapRange(start: Long, end: Long, minLen: Long = 50_000): Pair<Long, Long>? {
            if (deltaUs > 0) {
                return if (start >= fromUs) (start + deltaUs) to (end + deltaUs) else start to end
            }
            // Removal of [cutStart, cutEnd): times after cutEnd move left by the removed length.
            val removed = cutEnd - cutStart
            fun m(t: Long) = when {
                t <= cutStart -> t
                t >= cutEnd -> t - removed
                else -> cutStart
            }
            val ns = m(start)
            val ne = m(end)
            if (removeInside && start >= cutStart && end <= cutEnd) return null
            if (ne - ns < minLen || ne <= ns) return null
            return ns to ne
        }

        val overlays = doc.overlayTracks.map { track ->
            track.copy(items = track.items.mapNotNull { item ->
                val r = mapRange(item.startUs, item.endUs) ?: return@mapNotNull null
                item.withTimes(r.first, r.second)
            })
        }
        val audio = doc.audioTracks.map { track ->
            track.copy(clips = track.clips.mapNotNull { clip ->
                if (deltaUs > 0) {
                    if (clip.startUs >= fromUs) clip.copy(startUs = clip.startUs + deltaUs) else clip
                } else {
                    val r = mapRange(clip.startUs, clip.endUs) ?: return@mapNotNull null
                    // Keep the audio content aligned: shift start, trim the head if the cut removed it.
                    val headRemoved = if (clip.startUs in cutStart until cutEnd) cutEnd - clip.startUs else 0L
                    val newTrimStart = clip.trimStartUs + (headRemoved * clip.speed).toLong()
                    if (newTrimStart >= clip.trimEndUs) null else clip.copy(startUs = r.first, trimStartUs = newTrimStart)
                }
            })
        }
        val cues = doc.subtitles.cues.mapNotNull { cue ->
            val r = mapRange(cue.startUs, cue.endUs) ?: return@mapNotNull null
            val words = cue.words.mapNotNull { w ->
                val wr = mapRange(w.startUs, w.endUs, minLen = 1) ?: return@mapNotNull null
                w.copy(startUs = wr.first, endUs = wr.second)
            }
            // If some words were cut away, the text no longer matches the timings: rebuild it from the kept words.
            if (words.size != cue.words.size && words.isNotEmpty()) {
                cue.copy(startUs = r.first, endUs = r.second, words = words, text = words.joinToString(" ") { it.text })
            } else {
                cue.copy(startUs = r.first, endUs = r.second, words = words)
            }
        }
        return doc.copy(overlayTracks = overlays, audioTracks = audio, subtitles = doc.subtitles.copy(cues = cues))
    }

    /**
     * Removes source ranges (e.g. silences or filler words) from a clip, splitting it into the kept pieces.
     * Everything after the clip ripples left so captions stay in sync.
     */
    fun removeSourceRanges(doc: EditorDocument, clipId: String, ranges: List<LongRange>): EditResult? {
        val p = TimelineMath.placementOf(doc, clipId) ?: return null
        val clip = p.clip
        if (clip.source.kind == MediaKind.IMAGE || ranges.isEmpty()) return null
        // Keep windows in source time.
        val cuts = ranges.map { maxOf(it.first, clip.trimStartUs)..minOf(it.last, clip.trimEndUs) }
            .filter { it.last - it.first > 10_000 }
            .sortedBy { it.first }
        if (cuts.isEmpty()) return null
        val keep = ArrayList<LongRange>()
        var cursor = clip.trimStartUs
        for (c in cuts) {
            if (c.first > cursor) keep += cursor..c.first
            cursor = maxOf(cursor, c.last)
        }
        if (cursor < clip.trimEndUs) keep += cursor..clip.trimEndUs
        val minSource = (TimelineMath.MIN_CLIP_US * clip.speed).toLong()
        val pieces = keep.filter { it.last - it.first >= minSource }
        if (pieces.isEmpty()) return deleteClip(doc, clipId)
        val ordered = if (clip.reversed) pieces.reversed() else pieces
        val newClips = ordered.mapIndexed { i, r ->
            clip.copy(
                id = if (i == 0) clip.id else newId(),
                trimStartUs = r.first,
                trimEndUs = r.last,
                reversedUri = null,
                audioFadeInUs = if (i == 0) clip.audioFadeInUs else 0,
                videoFadeInUs = if (i == 0) clip.videoFadeInUs else 0,
                audioFadeOutUs = if (i == ordered.lastIndex) clip.audioFadeOutUs else 0,
                videoFadeOutUs = if (i == ordered.lastIndex) clip.videoFadeOutUs else 0,
                transitionOut = if (i == ordered.lastIndex) clip.transitionOut else Transition(),
            )
        }
        // Ripple each removed range, last first so earlier timeline positions stay valid.
        var result = doc
        val removedTimeline = cuts.mapNotNull { c ->
            val s = TimelineMath.sourceToTimeline(clip, p.startUs, if (clip.reversed) c.last else c.first) ?: return@mapNotNull null
            val e = TimelineMath.sourceToTimeline(clip, p.startUs, if (clip.reversed) c.first else c.last) ?: return@mapNotNull null
            minOf(s, e) to maxOf(s, e)
        }.sortedByDescending { it.first }
        for ((s, e) in removedTimeline) result = ripple(result, s, e, -(e - s), removeInside = true)
        val track = result.mainTrack.toMutableList()
        val idx = track.indexOfFirst { it.id == clip.id }
        track.removeAt(idx)
        track.addAll(idx, newClips)
        return EditResult(result.copy(mainTrack = track), newClips.first().id)
    }

    // ---------------------------------------------------------------- overlays

    /** Adds an overlay item to the first track where it does not overlap anything (or a new track on top). */
    fun addOverlay(doc: EditorDocument, item: OverlayItem): EditResult {
        val tracks = doc.overlayTracks.toMutableList()
        val idx = tracks.indexOfFirst { t -> !t.locked && t.items.none { it.startUs < item.endUs && item.startUs < it.endUs } }
        if (idx >= 0) tracks[idx] = tracks[idx].copy(items = (tracks[idx].items + item).sortedBy { it.startUs })
        else tracks += OverlayTrack(items = listOf(item))
        return EditResult(doc.copy(overlayTracks = tracks), item.id)
    }

    fun updateOverlay(doc: EditorDocument, itemId: String, transform: (OverlayItem) -> OverlayItem): EditorDocument? {
        var changed = false
        val tracks = doc.overlayTracks.map { t ->
            if (t.items.none { it.id == itemId }) t
            else t.copy(items = t.items.map { if (it.id == itemId) transform(it).also { n -> changed = n != it } else it })
        }
        return if (changed) doc.copy(overlayTracks = tracks) else null
    }

    fun removeOverlay(doc: EditorDocument, itemId: String): EditorDocument? {
        if (doc.overlayTracks.none { t -> t.items.any { it.id == itemId } }) return null
        val tracks = doc.overlayTracks.map { t -> t.copy(items = t.items.filterNot { it.id == itemId }) }.filter { it.items.isNotEmpty() }
        return doc.copy(overlayTracks = tracks)
    }

    fun duplicateOverlay(doc: EditorDocument, itemId: String): EditResult? {
        val item = findOverlay(doc, itemId) ?: return null
        val len = item.endUs - item.startUs
        val copy = item.withId(newId()).withTimes(item.endUs, item.endUs + len)
        return addOverlay(doc, copy)
    }

    /** Moves/trims an overlay in time, keeping at least 100 ms and not before 0. */
    fun retimeOverlay(doc: EditorDocument, itemId: String, startUs: Long, endUs: Long): EditorDocument? {
        val s = startUs.coerceAtLeast(0)
        val e = endUs.coerceAtLeast(s + TimelineMath.MIN_CLIP_US)
        return updateOverlay(doc, itemId) { it.withTimes(s, e) }
    }

    fun findOverlay(doc: EditorDocument, itemId: String): OverlayItem? =
        doc.overlayTracks.firstNotNullOfOrNull { t -> t.items.firstOrNull { it.id == itemId } }

    // ---------------------------------------------------------------- audio

    /** Adds an audio clip to a track of [kind] where it fits, creating a new track when needed. */
    fun addAudioClip(doc: EditorDocument, kind: AudioTrackKind, clip: AudioClip): EditResult {
        val tracks = doc.audioTracks.toMutableList()
        val idx = tracks.indexOfFirst { t -> t.kind == kind && t.clips.none { it.startUs < clip.endUs && clip.startUs < it.endUs } }
        if (idx >= 0) tracks[idx] = tracks[idx].copy(clips = (tracks[idx].clips + clip).sortedBy { it.startUs })
        else tracks += AudioTrack(kind = kind, clips = listOf(clip))
        return EditResult(doc.copy(audioTracks = tracks), clip.id)
    }

    fun updateAudioClip(doc: EditorDocument, clipId: String, transform: (AudioClip) -> AudioClip): EditorDocument? {
        var changed = false
        val tracks = doc.audioTracks.map { t ->
            if (t.clips.none { it.id == clipId }) t
            else t.copy(clips = t.clips.map { if (it.id == clipId) transform(it).also { n -> changed = n != it } else it }.sortedBy { it.startUs })
        }
        return if (changed) doc.copy(audioTracks = tracks) else null
    }

    fun removeAudioClip(doc: EditorDocument, clipId: String): EditorDocument? {
        if (doc.audioTracks.none { t -> t.clips.any { it.id == clipId } }) return null
        return doc.copy(audioTracks = doc.audioTracks.map { t -> t.copy(clips = t.clips.filterNot { it.id == clipId }) }.filter { it.clips.isNotEmpty() })
    }

    fun updateAudioTrack(doc: EditorDocument, trackId: String, transform: (AudioTrack) -> AudioTrack): EditorDocument? {
        var changed = false
        val tracks = doc.audioTracks.map { if (it.id == trackId) transform(it).also { n -> changed = n != it } else it }
        return if (changed) doc.copy(audioTracks = tracks) else null
    }

    fun findAudioClip(doc: EditorDocument, clipId: String): Pair<AudioTrack, AudioClip>? =
        doc.audioTracks.firstNotNullOfOrNull { t -> t.clips.firstOrNull { it.id == clipId }?.let { t to it } }

    /** Trims an audio clip edge by a timeline delta. */
    fun trimAudioClip(doc: EditorDocument, clipId: String, edge: Edge, timelineDeltaUs: Long): EditorDocument? =
        updateAudioClip(doc, clipId) { c ->
            val sourceDelta = (timelineDeltaUs * c.speed.toDouble()).roundToLong()
            val min = (TimelineMath.MIN_CLIP_US * c.speed).toLong()
            if (edge == Edge.START) {
                val newTrim = (c.trimStartUs + sourceDelta).coerceIn(0, c.trimEndUs - min)
                val applied = ((newTrim - c.trimStartUs) / c.speed.toDouble()).roundToLong()
                c.copy(trimStartUs = newTrim, startUs = (c.startUs + applied).coerceAtLeast(0))
            } else {
                c.copy(trimEndUs = (c.trimEndUs + sourceDelta).coerceIn(c.trimStartUs + min, c.source.durationUs))
            }
        }

    /**
     * Moves a clip's audio to its own track (so it can be edited independently) and mutes the clip.
     */
    fun detachAudio(doc: EditorDocument, clipId: String): EditResult? {
        val p = TimelineMath.placementOf(doc, clipId) ?: return null
        val c = p.clip
        if (c.source.kind != MediaKind.VIDEO || !c.source.hasAudio || c.muted || c.reversed) return null
        val audio = AudioClip(
            source = c.source.copy(kind = MediaKind.AUDIO),
            startUs = p.startUs,
            trimStartUs = c.trimStartUs,
            trimEndUs = c.trimEndUs,
            volume = c.volume,
            fadeInUs = c.audioFadeInUs,
            fadeOutUs = c.audioFadeOutUs,
            speed = c.speed,
        )
        val muted = updateClip(doc, clipId) { it.copy(muted = true) } ?: doc
        return addAudioClip(muted, AudioTrackKind.EXTRACTED, audio)
    }

    /** Replaces a clip's audio with [replacement] (e.g. a cleaned-up rendition of the same source). */
    fun replaceClipAudio(doc: EditorDocument, clipId: String, replacement: com.ravango.core.model.MediaSource, alignToSource: Boolean): EditResult? {
        val p = TimelineMath.placementOf(doc, clipId) ?: return null
        val c = p.clip
        val audio = if (alignToSource) {
            AudioClip(source = replacement, startUs = p.startUs, trimStartUs = c.trimStartUs, trimEndUs = minOf(c.trimEndUs, replacement.durationUs), volume = c.volume, fadeInUs = c.audioFadeInUs, fadeOutUs = c.audioFadeOutUs, speed = c.speed)
        } else {
            AudioClip(source = replacement, startUs = p.startUs, trimStartUs = 0, trimEndUs = minOf(replacement.durationUs, p.durationUs), volume = 1f)
        }
        val muted = updateClip(doc, clipId) { it.copy(muted = true) } ?: doc
        return addAudioClip(muted, AudioTrackKind.EXTRACTED, audio)
    }

    // ---------------------------------------------------------------- subtitles

    fun setCues(doc: EditorDocument, cues: List<SubtitleCue>): EditorDocument =
        doc.copy(subtitles = doc.subtitles.copy(cues = cues.sortedBy { it.startUs }))

    fun updateCue(doc: EditorDocument, cueId: String, transform: (SubtitleCue) -> SubtitleCue): EditorDocument? {
        var changed = false
        val cues = doc.subtitles.cues.map { if (it.id == cueId) normalizeCue(transform(it)).also { n -> changed = n != it } else it }
        return if (changed) setCues(doc, cues) else null
    }

    fun deleteCue(doc: EditorDocument, cueId: String): EditorDocument? {
        if (doc.subtitles.cues.none { it.id == cueId }) return null
        return setCues(doc, doc.subtitles.cues.filterNot { it.id == cueId })
    }

    fun addCue(doc: EditorDocument, cue: SubtitleCue): EditResult = EditResult(setCues(doc, doc.subtitles.cues + normalizeCue(cue)), cue.id)

    /** Splits a cue at [atUs]; word timings decide where the text breaks, otherwise the text is halved at a space. */
    fun splitCue(doc: EditorDocument, cueId: String, atUs: Long): EditResult? {
        val cue = doc.subtitles.cues.firstOrNull { it.id == cueId } ?: return null
        val (a, b) = splitCueAt(cue, atUs) ?: return null
        val cues = doc.subtitles.cues.flatMap { if (it.id == cueId) listOf(a, b) else listOf(it) }
        return EditResult(setCues(doc, cues), b.id)
    }

    fun splitCueAt(cue: SubtitleCue, atUs: Long): Pair<SubtitleCue, SubtitleCue>? {
        if (atUs <= cue.startUs + 100_000 || atUs >= cue.endUs - 100_000) return null
        return if (cue.words.size >= 2) {
            val firstWords = cue.words.filter { (it.startUs + it.endUs) / 2 < atUs }
            val secondWords = cue.words.drop(firstWords.size)
            if (firstWords.isEmpty() || secondWords.isEmpty()) return null
            SubtitleCue(id = cue.id, startUs = cue.startUs, endUs = atUs, text = firstWords.joinToString(" ") { it.text }, words = firstWords) to
                SubtitleCue(startUs = atUs, endUs = cue.endUs, text = secondWords.joinToString(" ") { it.text }, words = secondWords)
        } else {
            val tokens = cue.text.trim().split(Regex("\\s+"))
            if (tokens.size < 2) return null
            val fraction = (atUs - cue.startUs).toDouble() / (cue.endUs - cue.startUs)
            val cut = (tokens.size * fraction).toInt().coerceIn(1, tokens.size - 1)
            SubtitleCue(id = cue.id, startUs = cue.startUs, endUs = atUs, text = tokens.take(cut).joinToString(" ")) to
                SubtitleCue(startUs = atUs, endUs = cue.endUs, text = tokens.drop(cut).joinToString(" "))
        }
    }

    /** Merges a cue with the following one. */
    fun mergeCueWithNext(doc: EditorDocument, cueId: String): EditResult? {
        val sorted = doc.subtitles.cues.sortedBy { it.startUs }
        val i = sorted.indexOfFirst { it.id == cueId }
        if (i < 0 || i >= sorted.lastIndex) return null
        val a = sorted[i]
        val b = sorted[i + 1]
        val merged = a.copy(endUs = b.endUs, text = (a.text.trim() + " " + b.text.trim()).trim(), words = if (a.words.isNotEmpty() && b.words.isNotEmpty()) a.words + b.words else emptyList())
        val cues = sorted.toMutableList().apply { set(i, merged); removeAt(i + 1) }
        return EditResult(setCues(doc, cues), merged.id)
    }

    /** Drops word timings that no longer match the edited text and keeps start < end. */
    fun normalizeCue(cue: SubtitleCue): SubtitleCue {
        val end = maxOf(cue.endUs, cue.startUs + 100_000)
        val tokens = cue.text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val wordsMatch = cue.words.size == tokens.size && cue.words.zip(tokens).all { (w, t) -> w.text == t }
        val words: List<WordTiming> = if (wordsMatch) cue.words else emptyList()
        return cue.copy(endUs = end, words = words)
    }

    const val MAX_STILL_US = 60_000_000L
}

/** Returns a copy of the overlay item with new times, whatever its concrete type. */
fun OverlayItem.withTimes(startUs: Long, endUs: Long): OverlayItem = when (this) {
    is OverlayItem.Text -> copy(startUs = startUs, endUs = endUs)
    is OverlayItem.Sticker -> copy(startUs = startUs, endUs = endUs)
    is OverlayItem.Image -> copy(startUs = startUs, endUs = endUs)
    is OverlayItem.Video -> {
        // Moving the start of a PiP keeps the visible content: advance the trim when the head is cut.
        val headDelta = startUs - this.startUs
        val keepContent = endUs - startUs != this.endUs - this.startUs && headDelta > 0
        copy(startUs = startUs, endUs = endUs, trimStartUs = if (keepContent) (trimStartUs + headDelta).coerceAtMost(source.durationUs) else trimStartUs)
    }
}

fun OverlayItem.withTransform(transform: com.ravango.core.model.Transform2D): OverlayItem = when (this) {
    is OverlayItem.Text -> copy(transform = transform)
    is OverlayItem.Sticker -> copy(transform = transform)
    is OverlayItem.Image -> copy(transform = transform)
    is OverlayItem.Video -> copy(transform = transform)
}

fun OverlayItem.withAnimations(animIn: com.ravango.core.model.OverlayAnimation, animOut: com.ravango.core.model.OverlayAnimation): OverlayItem = when (this) {
    is OverlayItem.Text -> copy(animationIn = animIn, animationOut = animOut)
    is OverlayItem.Sticker -> copy(animationIn = animIn, animationOut = animOut)
    is OverlayItem.Image -> copy(animationIn = animIn, animationOut = animOut)
    is OverlayItem.Video -> copy(animationIn = animIn, animationOut = animOut)
}

fun OverlayItem.withId(id: String): OverlayItem = when (this) {
    is OverlayItem.Text -> copy(id = id)
    is OverlayItem.Sticker -> copy(id = id)
    is OverlayItem.Image -> copy(id = id)
    is OverlayItem.Video -> copy(id = id)
}
