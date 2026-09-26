package com.ravango.engine.editor

import com.google.common.truth.Truth.assertThat
import com.ravango.core.model.OverlayItem
import com.ravango.core.model.SubtitleCue
import com.ravango.core.model.VideoClip
import com.ravango.core.model.WordTiming
import com.ravango.engine.editor.ops.Edge
import com.ravango.engine.editor.ops.EditOps
import org.junit.Test

class EditOpsTest {
    @Test
    fun split_createsTwoContiguousPieces() {
        val d = doc(clip("a", 1 * S, 9 * S))
        val r = EditOps.split(d, 3 * S)!!
        val (a, b) = r.document.mainTrack
        assertThat(a.trimStartUs).isEqualTo(1 * S)
        assertThat(a.trimEndUs).isEqualTo(4 * S)
        assertThat(b.trimStartUs).isEqualTo(4 * S)
        assertThat(b.trimEndUs).isEqualTo(9 * S)
        assertThat(r.selectId).isEqualTo(b.id)
        assertThat(r.document.durationUs).isEqualTo(d.durationUs)
    }

    @Test
    fun split_withSpeedUsesSourceTime() {
        val d = doc(clip("a", 0, 8 * S, speed = 2f))
        val (a, b) = EditOps.split(d, 1 * S)!!.document.mainTrack
        assertThat(a.trimEndUs).isEqualTo(2 * S)
        assertThat(b.trimStartUs).isEqualTo(2 * S)
    }

    @Test
    fun split_reversedKeepsTimelineOrder() {
        val d = doc(clip("a", 0, 10 * S, reversed = true))
        val (a, b) = EditOps.split(d, 3 * S)!!.document.mainTrack
        // First on the timeline = end of the source window.
        assertThat(a.trimStartUs).isEqualTo(7 * S)
        assertThat(a.trimEndUs).isEqualTo(10 * S)
        assertThat(b.trimEndUs).isEqualTo(7 * S)
        assertThat(a.reversedUri).isNull()
    }

    @Test
    fun split_tooCloseToEdgeIsRejected() {
        assertThat(EditOps.split(doc(clip("a", 0, 10 * S)), 50_000)).isNull()
    }

    @Test
    fun split_image() {
        val img = VideoClip(id = "i", source = image(), stillDurationUs = 4 * S)
        val (a, b) = EditOps.split(doc(img), 1 * S)!!.document.mainTrack
        assertThat(a.stillDurationUs).isEqualTo(1 * S)
        assertThat(b.stillDurationUs).isEqualTo(3 * S)
    }

    @Test
    fun trimEnd_ripplesLaterCues() {
        val cue = SubtitleCue(id = "c", startUs = 6 * S, endUs = 7 * S, text = "hi")
        val d = doc(clip("a", 0, 5 * S), clip("b", 0, 5 * S), cues = listOf(cue))
        val out = EditOps.trimClip(d, "a", Edge.END, -2 * S)!!
        assertThat(out.mainTrack[0].trimEndUs).isEqualTo(3 * S)
        assertThat(out.subtitles.cues.single().startUs).isEqualTo(4 * S)
        assertThat(out.subtitles.cues.single().endUs).isEqualTo(5 * S)
    }

    @Test
    fun trimStart_movesHeadAndRemovesAttachedCue() {
        val inside = SubtitleCue(id = "in", startUs = 200_000, endUs = 800_000, text = "gone")
        val after = SubtitleCue(id = "after", startUs = 3 * S, endUs = 4 * S, text = "kept")
        val d = doc(clip("a", 0, 5 * S), cues = listOf(inside, after))
        val out = EditOps.trimClip(d, "a", Edge.START, 1 * S)!!
        assertThat(out.mainTrack[0].trimStartUs).isEqualTo(1 * S)
        assertThat(out.subtitles.cues.map { it.id }).containsExactly("after")
        assertThat(out.subtitles.cues.single().startUs).isEqualTo(2 * S)
    }

    @Test
    fun trim_isClampedToSource() {
        val d = doc(clip("a", 1 * S, 9 * S))
        val out = EditOps.trimClip(d, "a", Edge.END, 5 * S)!!
        assertThat(out.mainTrack[0].trimEndUs).isEqualTo(10 * S)
    }

    @Test
    fun delete_ripplesAndDropsItemsInside() {
        val text = OverlayItem.Text(id = "t", startUs = 1 * S, endUs = 2 * S, text = "x")
        val later = OverlayItem.Text(id = "l", startUs = 6 * S, endUs = 7 * S, text = "y")
        val d = doc(clip("a", 0, 4 * S), clip("b", 0, 4 * S)).let { EditOps.addOverlay(EditOps.addOverlay(it, text).document, later).document }
        val r = EditOps.deleteClip(d, "a")!!
        val items = r.document.overlayTracks.flatMap { it.items }
        assertThat(items.map { it.id }).containsExactly("l")
        assertThat(items.single().startUs).isEqualTo(2 * S)
        assertThat(r.selectId).isEqualTo("b")
    }

    @Test
    fun duplicate_insertsCopyAfterAndShiftsLaterItems() {
        val cue = SubtitleCue(startUs = 5 * S, endUs = 6 * S, text = "b")
        val d = doc(clip("a", 0, 4 * S), clip("b", 0, 4 * S), cues = listOf(cue))
        val r = EditOps.duplicateClip(d, "a")!!
        assertThat(r.document.mainTrack.size).isEqualTo(3)
        assertThat(r.document.mainTrack[1].id).isEqualTo(r.selectId)
        assertThat(r.document.subtitles.cues.single().startUs).isEqualTo(9 * S)
    }

    @Test
    fun move_reorders() {
        val d = doc(clip("a"), clip("b"), clip("c"))
        assertThat(EditOps.moveClip(d, "a", 2)!!.mainTrack.map { it.id }).containsExactly("b", "c", "a").inOrder()
    }

    @Test
    fun join_mergesAdjacentSplitPieces() {
        val split = EditOps.split(doc(clip("a", 0, 10 * S)), 4 * S)!!.document
        val first = split.mainTrack[0].id
        assertThat(EditOps.canJoinWithNext(split, first)).isTrue()
        val joined = EditOps.joinWithNext(split, first)!!.document
        assertThat(joined.mainTrack.single().trimStartUs).isEqualTo(0)
        assertThat(joined.mainTrack.single().trimEndUs).isEqualTo(10 * S)
    }

    @Test
    fun join_rejectsDifferentSources() {
        val d = doc(clip("a", 0, 4 * S, uri = "file:///x.mp4"), clip("b", 4 * S, 8 * S, uri = "file:///y.mp4"))
        assertThat(EditOps.canJoinWithNext(d, "a")).isFalse()
    }

    @Test
    fun speed_scalesItemsInsideClipAndShiftsLater() {
        val inside = SubtitleCue(id = "i", startUs = 2 * S, endUs = 4 * S, text = "a")
        val later = SubtitleCue(id = "l", startUs = 9 * S, endUs = 10 * S, text = "b")
        val d = doc(clip("a", 0, 8 * S), clip("b", 0, 4 * S), cues = listOf(inside, later))
        val out = EditOps.setSpeed(d, "a", 2f)!!
        val cues = out.subtitles.cues.associateBy { it.id }
        assertThat(cues["i"]!!.startUs).isEqualTo(1 * S)
        assertThat(cues["i"]!!.endUs).isEqualTo(2 * S)
        assertThat(cues["l"]!!.startUs).isEqualTo(5 * S)
    }

    @Test
    fun removeSourceRanges_splitsAndRipples() {
        val cue = SubtitleCue(id = "c", startUs = 8 * S, endUs = 9 * S, text = "end")
        val d = doc(clip("a", 0, 10 * S), cues = listOf(cue))
        val r = EditOps.removeSourceRanges(d, "a", listOf(2 * S..3 * S, 5 * S..6 * S))!!
        val clips = r.document.mainTrack
        assertThat(clips.map { it.trimStartUs to it.trimEndUs }).containsExactly(0L to 2 * S, 3 * S to 5 * S, 6 * S to 10 * S).inOrder()
        assertThat(r.document.durationUs).isEqualTo(8 * S)
        assertThat(r.document.subtitles.cues.single().startUs).isEqualTo(6 * S)
    }

    @Test
    fun cueSplit_usesWordTimings() {
        val cue = SubtitleCue(
            id = "c", startUs = 0, endUs = 2 * S, text = "سلام دنیا خوب",
            words = listOf(WordTiming("سلام", 0, 600_000), WordTiming("دنیا", 600_000, 1_200_000), WordTiming("خوب", 1_200_000, 2 * S)),
        )
        val (a, b) = EditOps.splitCueAt(cue, 1_300_000)!!
        assertThat(a.text).isEqualTo("سلام دنیا")
        assertThat(b.text).isEqualTo("خوب")
        assertThat(a.endUs).isEqualTo(b.startUs)
    }

    @Test
    fun cueMerge_joinsText() {
        val d = doc(clip("a"), cues = listOf(SubtitleCue(id = "1", startUs = 0, endUs = S, text = "a"), SubtitleCue(id = "2", startUs = S, endUs = 2 * S, text = "b")))
        val merged = EditOps.mergeCueWithNext(d, "1")!!.document.subtitles.cues.single()
        assertThat(merged.text).isEqualTo("a b")
        assertThat(merged.endUs).isEqualTo(2 * S)
    }

    @Test
    fun normalizeCue_dropsStaleWordTimings() {
        val cue = SubtitleCue(startUs = 0, endUs = S, text = "edited text", words = listOf(WordTiming("old", 0, S)))
        assertThat(EditOps.normalizeCue(cue).words).isEmpty()
    }

    @Test
    fun overlays_goToFirstFreeTrack() {
        val a = OverlayItem.Text(id = "a", startUs = 0, endUs = 2 * S, text = "a")
        val b = OverlayItem.Text(id = "b", startUs = 1 * S, endUs = 3 * S, text = "b")
        val c = OverlayItem.Text(id = "c", startUs = 2 * S, endUs = 4 * S, text = "c")
        var d = doc(clip("x"))
        d = EditOps.addOverlay(d, a).document
        d = EditOps.addOverlay(d, b).document
        d = EditOps.addOverlay(d, c).document
        assertThat(d.overlayTracks.size).isEqualTo(2)
        assertThat(d.overlayTracks[0].items.map { it.id }).containsExactly("a", "c").inOrder()
    }

    @Test
    fun detachAudio_mutesClipAndAddsAlignedAudio() {
        val d = doc(clip("a", 0, 4 * S), clip("b", 2 * S, 6 * S))
        val r = EditOps.detachAudio(d, "b")!!
        assertThat(r.document.mainTrack[1].muted).isTrue()
        val audio = r.document.audioTracks.single().clips.single()
        assertThat(audio.startUs).isEqualTo(4 * S)
        assertThat(audio.trimStartUs).isEqualTo(2 * S)
        assertThat(audio.durationUs).isEqualTo(4 * S)
    }
}
