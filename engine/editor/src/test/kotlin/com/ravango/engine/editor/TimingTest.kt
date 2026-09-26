package com.ravango.engine.editor

import com.google.common.truth.Truth.assertThat
import com.ravango.core.model.OverlayAnimation
import com.ravango.core.model.SubtitleAnimation
import com.ravango.core.model.SubtitleCue
import com.ravango.core.model.Transition
import com.ravango.core.model.TransitionType
import com.ravango.core.model.WordTiming
import com.ravango.engine.editor.composition.OverlayTiming
import com.ravango.engine.editor.composition.SubtitleTiming
import com.ravango.engine.editor.composition.TransitionTiming
import org.junit.Test

class TimingTest {
    @Test
    fun transitionWindow_isCenteredOnCut() {
        val a = clip("a", 0, 4 * S).copy(transitionOut = Transition(TransitionType.CROSS_ZOOM, 1 * S))
        val w = TransitionTiming.windows(doc(a, clip("b", 0, 4 * S))).single()
        assertThat(w.startUs).isEqualTo(3_500_000)
        assertThat(w.endUs).isEqualTo(4_500_000)
        assertThat(w.peakUs).isEqualTo(4 * S)
    }

    @Test
    fun transitionOnLastClipIsIgnored() {
        val a = clip("a", 0, 4 * S).copy(transitionOut = Transition(TransitionType.FADE_BLACK, S))
        assertThat(TransitionTiming.windows(doc(a))).isEmpty()
    }

    @Test
    fun transitionIsClampedForShortClips() {
        val a = clip("a", 0, 1 * S).copy(transitionOut = Transition(TransitionType.SPIN, 2 * S))
        val w = TransitionTiming.windows(doc(a, clip("b", 0, 4 * S))).single()
        assertThat(w.peakUs - w.startUs).isEqualTo(450_000)
    }

    @Test
    fun transitionAmount_peaksAtCut() {
        val a = clip("a", 0, 4 * S).copy(transitionOut = Transition(TransitionType.FADE_WHITE, 1 * S))
        val windows = TransitionTiming.windows(doc(a, clip("b", 0, 4 * S)))
        assertThat(TransitionTiming.stateAt(windows, 3 * S)).isNull()
        assertThat(TransitionTiming.stateAt(windows, 4 * S)!!.amount).isWithin(1e-3f).of(1f)
        val mid = TransitionTiming.stateAt(windows, 3_750_000)!!
        assertThat(mid.amount).isWithin(1e-3f).of(0.5f)
        assertThat(mid.incoming).isFalse()
        assertThat(TransitionTiming.stateAt(windows, 4_250_000)!!.incoming).isTrue()
    }

    @Test
    fun clipFades_areAnchoredAtEdges() {
        val a = clip("a", 0, 4 * S).copy(videoFadeInUs = 1 * S, videoFadeOutUs = 1 * S)
        val windows = TransitionTiming.windows(doc(a))
        assertThat(TransitionTiming.stateAt(windows, 0)!!.amount).isWithin(1e-3f).of(1f)
        assertThat(TransitionTiming.stateAt(windows, 2 * S)).isNull()
        assertThat(TransitionTiming.stateAt(windows, 3_999_999)!!.amount).isGreaterThan(0.99f)
    }

    @Test
    fun overlayAnimation_fadeInAndOut() {
        val s0 = OverlayTiming.state(0, 2 * S, OverlayAnimation.FADE, OverlayAnimation.FADE, 0)
        assertThat(s0.alpha).isWithin(1e-3f).of(0f)
        assertThat(OverlayTiming.state(0, 2 * S, OverlayAnimation.FADE, OverlayAnimation.FADE, 1 * S).alpha).isEqualTo(1f)
        assertThat(OverlayTiming.state(0, 2 * S, OverlayAnimation.FADE, OverlayAnimation.FADE, 2 * S).visible).isFalse()
    }

    @Test
    fun overlayAnimation_typewriterReveals() {
        val half = OverlayTiming.state(0, 10 * S, OverlayAnimation.TYPEWRITER, OverlayAnimation.NONE, 1 * S)
        assertThat(half.reveal).isWithin(1e-3f).of(0.5f)
    }

    @Test
    fun cueAt_findsActiveCue() {
        val cues = listOf(SubtitleCue(id = "a", startUs = 0, endUs = S, text = "a"), SubtitleCue(id = "b", startUs = 2 * S, endUs = 3 * S, text = "b"))
        assertThat(SubtitleTiming.cueAt(cues, 500_000)?.id).isEqualTo("a")
        assertThat(SubtitleTiming.cueAt(cues, 1_500_000)).isNull()
        assertThat(SubtitleTiming.cueAt(cues, 2_500_000)?.id).isEqualTo("b")
    }

    @Test
    fun karaoke_activeWordFollowsTimings() {
        val cue = SubtitleCue(startUs = 0, endUs = 3 * S, text = "one two three", words = listOf(WordTiming("one", 0, S), WordTiming("two", S, 2 * S), WordTiming("three", 2 * S, 3 * S)))
        assertThat(SubtitleTiming.state(cue, SubtitleAnimation.KARAOKE, 1_500_000).activeWord).isEqualTo(1)
        val wbw = SubtitleTiming.state(cue, SubtitleAnimation.WORD_BY_WORD, 2_500_000)
        assertThat(wbw.visibleWords).isEqualTo(3)
        assertThat(SubtitleTiming.state(cue, SubtitleAnimation.WORD_BY_WORD, 100_000).visibleWords).isEqualTo(1)
    }

    @Test
    fun syntheticWords_coverCue() {
        val words = SubtitleTiming.words(SubtitleCue(startUs = 0, endUs = 1_000_000, text = "ab abcd"))
        assertThat(words).hasSize(2)
        assertThat(words.first().startUs).isEqualTo(0)
        assertThat(words.last().endUs).isAtMost(1_000_000)
        assertThat(words[1].startUs).isGreaterThan(300_000)
    }
}
