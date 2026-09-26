package com.ravango.engine.ai.subtitle

import com.google.common.truth.Truth.assertThat
import com.ravango.core.model.SubtitleCue
import com.ravango.core.model.SubtitleStyle
import com.ravango.core.model.WordTiming
import com.ravango.engine.ai.api.Transcript
import com.ravango.engine.ai.api.TranscriptSegment
import org.junit.Test

class SubtitleBuilderTest {

    private val builder = DefaultSubtitleBuilder()

    /** Words spaced 400 ms apart, each 350 ms long. */
    private fun transcript(vararg words: String, startUs: Long = 0): Transcript {
        val timings = words.mapIndexed { i, w -> WordTiming(w, startUs + i * 400_000L, startUs + i * 400_000L + 350_000L) }
        return Transcript("fa", listOf(TranscriptSegment(timings.first().startUs, timings.last().endUs, words.joinToString(" "), timings)))
    }

    @Test
    fun `never splits words and respects max chars`() {
        val words = "این یک متن آزمایشی نسبتاً طولانی است که باید به چند زیرنویس خوانا و کوتاه تقسیم شود تا بیننده راحت بخواند".split(" ")
        val cues = builder.build(transcript(*words.toTypedArray()), SubtitleStyle(), maxCharsPerCue = 24)
        assertThat(cues.size).isGreaterThan(1)
        cues.forEach { assertThat(it.text.length).isAtMost(24) }
        assertThat(cues.joinToString(" ") { it.text }).isEqualTo(words.joinToString(" "))
        cues.forEach { cue -> assertThat(cue.words.joinToString(" ") { it.text }).isEqualTo(cue.text) }
    }

    @Test
    fun `breaks on Persian sentence and clause punctuation`() {
        val cues = builder.build(transcript("سلام", "دوستان.", "امروز", "می‌خواهیم", "دربارهٔ", "روان‌گو", "صحبت", "کنیم؟", "بله"), SubtitleStyle(), maxCharsPerCue = 60)
        assertThat(cues.map { it.text }).containsExactly("سلام دوستان.", "امروز می‌خواهیم دربارهٔ روان‌گو صحبت کنیم؟", "بله").inOrder()
    }

    @Test
    fun `Persian comma ends a cue once it is reasonably full`() {
        val cues = builder.build(transcript("اول", "از", "همه", "یک", "نکتهٔ", "مهم،", "بعد", "ادامه"), SubtitleStyle(), maxCharsPerCue = 30)
        assertThat(cues.first().text).endsWith("مهم،")
    }

    @Test
    fun `respects max cue duration`() {
        val words = (1..30).map { "w$it" }
        val cues = builder.build(transcript(*words.toTypedArray()), SubtitleStyle(), maxCharsPerCue = 200, maxCueDurationUs = 2_000_000)
        cues.forEach { assertThat(it.words.last().endUs - it.words.first().startUs).isAtMost(2_000_000) }
    }

    @Test
    fun `short cue is extended to minimum duration without overlapping next`() {
        val t = Transcript(
            "en",
            listOf(
                TranscriptSegment(0, 200_000, "Hi.", listOf(WordTiming("Hi.", 0, 200_000))),
                TranscriptSegment(2_000_000, 2_500_000, "Welcome back.", listOf(WordTiming("Welcome", 2_000_000, 2_200_000), WordTiming("back.", 2_200_000, 2_500_000))),
            ),
        )
        val cues = builder.build(t, SubtitleStyle())
        assertThat(cues).hasSize(2)
        assertThat(cues[0].endUs - cues[0].startUs).isAtLeast(DefaultSubtitleBuilder.MIN_CUE_US)
        assertThat(cues[0].endUs).isAtMost(cues[1].startUs)
    }

    @Test
    fun `pause starts a new cue`() {
        val t = Transcript(
            "en",
            listOf(
                TranscriptSegment(
                    0, 3_000_000, "one two three",
                    listOf(WordTiming("one", 0, 300_000), WordTiming("two", 350_000, 600_000), WordTiming("three", 2_000_000, 2_400_000)),
                ),
            ),
        )
        assertThat(builder.build(t, SubtitleStyle()).map { it.text }).containsExactly("one two", "three").inOrder()
    }

    @Test
    fun `srt round trip keeps times and text`() {
        val cues = listOf(
            SubtitleCue(startUs = 1_250_000, endUs = 3_000_000, text = "سلام دنیا"),
            SubtitleCue(startUs = 3_661_001_000, endUs = 3_662_500_000, text = "Line one\nLine two"),
        )
        val srt = builder.toSrt(cues)
        assertThat(srt).contains("00:00:01,250 --> 00:00:03,000")
        assertThat(srt).contains("01:01:01,001 --> 01:01:02,500")
        val parsed = builder.parseSrt(srt)
        assertThat(parsed.map { Triple(it.startUs, it.endUs, it.text) })
            .containsExactly(Triple(1_250_000L, 3_000_000L, "سلام دنیا"), Triple(3_661_001_000L, 3_662_500_000L, "Line one\nLine two")).inOrder()
    }

    @Test
    fun `srt adds RLM only for Persian lines starting with Latin text and parse strips it`() {
        val srt = builder.toSrt(listOf(SubtitleCue(startUs = 0, endUs = 1_000_000, text = "iPhone جدید رسید"), SubtitleCue(startUs = 1_000_000, endUs = 2_000_000, text = "سلام iPhone")))
        assertThat(srt).contains("‏iPhone جدید رسید")
        assertThat(srt).doesNotContain("‏سلام")
        assertThat(builder.parseSrt(srt).first().text).isEqualTo("iPhone جدید رسید")
    }

    @Test
    fun `parse tolerates BOM, CRLF, dots and tags`() {
        val srt = "﻿1\r\n00:00:00.500 --> 00:00:01.000\r\n<i>Hello</i>\r\n\r\n2\r\n00:00:02,000 --> 00:00:03,000\r\n{\\an8}World\r\n"
        val parsed = builder.parseSrt(srt)
        assertThat(parsed.map { it.text }).containsExactly("Hello", "World").inOrder()
        assertThat(parsed[0].startUs).isEqualTo(500_000)
    }

    @Test
    fun `timecode formatting`() {
        assertThat(DefaultSubtitleBuilder.timecode(0)).isEqualTo("00:00:00,000")
        assertThat(DefaultSubtitleBuilder.parseTime("00:01:02,3")).isEqualTo(62_300_000)
    }
}
