package com.ravango.engine.ai.video

import com.google.common.truth.Truth.assertThat
import com.ravango.core.model.WordTiming
import com.ravango.engine.ai.api.CutKind
import com.ravango.engine.ai.api.Transcript
import com.ravango.engine.ai.api.TranscriptSegment
import org.junit.Test

class FillerDetectorTest {

    /** Builds a transcript from (word, gapBeforeMs) pairs; each word lasts 300 ms. */
    private fun t(vararg words: Pair<String, Int>): Transcript {
        var clock = 0L
        val timings = words.map { (w, gap) ->
            clock += gap * 1000L
            val wt = WordTiming(w, clock, clock + 300_000)
            clock += 300_000
            wt
        }
        return Transcript(null, listOf(TranscriptSegment(timings.first().startUs, timings.last().endUs, timings.joinToString(" ") { it.text }, timings)))
    }

    private fun words(text: String, gapMs: Int = 50) = text.split(" ").map { it to gapMs }.toTypedArray()

    @Test
    fun `hesitation sounds are always fillers`() {
        val cuts = FillerDetector.detect(t(*words("so um I think uh this works")))
        val fillers = cuts.filter { it.kind == CutKind.FILLER_WORD }.map { it.text }
        assertThat(fillers).containsAtLeast("um", "uh")
    }

    @Test
    fun `Persian hesitation with kasra is detected`() {
        val cuts = FillerDetector.detect(t(*words("سلام اِم امروز اوم می‌خواهیم")))
        assertThat(cuts.filter { it.kind == CutKind.FILLER_WORD }.map { it.text }).containsExactly("اِم", "اوم")
    }

    @Test
    fun `discourse marker isolated by pauses is a filler but fluent use is not`() {
        val isolated = FillerDetector.detect(t("سلام" to 0, "یعنی" to 400, "امروز" to 400, "آمدیم" to 50))
        assertThat(isolated.map { it.text }).contains("یعنی")

        val fluent = FillerDetector.detect(t(*words("این یعنی همه چیز درست است")))
        assertThat(fluent.map { it.text }).doesNotContain("یعنی")
    }

    @Test
    fun `like as a verb is kept, like between pauses is cut`() {
        assertThat(FillerDetector.detect(t(*words("I like this app"))).map { it.text }).doesNotContain("like")
        val cut = FillerDetector.detect(t("it" to 0, "was," to 50, "like" to 300, "amazing" to 400))
        assertThat(cut.map { it.text }).contains("like")
    }

    @Test
    fun `multi word marker به هر حال`() {
        val cuts = FillerDetector.detect(t("خب" to 0, "به" to 500, "هر" to 30, "حال" to 30, "بریم" to 500, "سراغ" to 30, "نکته" to 30))
        assertThat(cuts.map { it.text }).contains("به هر حال")
    }

    @Test
    fun `repeated bigram cuts the first copy`() {
        val cuts = FillerDetector.detect(t(*words("I want to I want to show you")))
        val rep = cuts.single { it.kind == CutKind.REPETITION }
        assertThat(rep.text).isEqualTo("I want to")
        assertThat(rep.range.startUs).isAtMost(50_000)
    }

    @Test
    fun `emphatic doubling is not a repetition`() {
        val cuts = FillerDetector.detect(t(*words("this is very very good")))
        assertThat(cuts.none { it.kind == CutKind.REPETITION }).isTrue()
        val fa = FillerDetector.detect(t(*words("خیلی خیلی ممنون")))
        assertThat(fa.none { it.kind == CutKind.REPETITION }).isTrue()
    }

    @Test
    fun `repetition outside the window is ignored`() {
        val cuts = FillerDetector.detect(t("project" to 0, "project" to 3500))
        assertThat(cuts.none { it.kind == CutKind.REPETITION }).isTrue()
    }

    @Test
    fun `false starts from cut-off fragments and prefixes`() {
        val cuts = FillerDetector.detect(t(*words("the pro- project is pres presentation ready")))
        assertThat(cuts.filter { it.kind == CutKind.FALSE_START }.map { it.text }).containsExactly("pro-", "pres").inOrder()
    }

    @Test
    fun `cut ranges stay between neighbouring words`() {
        val transcript = t("hello" to 0, "um" to 200, "world" to 200)
        val cut = FillerDetector.detect(transcript).single()
        val words = transcript.segments.single().words
        assertThat(cut.range.startUs).isAtLeast(words[0].endUs)
        assertThat(cut.range.endUs).isAtMost(words[2].startUs)
    }
}
