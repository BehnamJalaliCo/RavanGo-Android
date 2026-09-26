package com.ravango.engine.ai.json

import com.google.common.truth.Truth.assertThat
import com.ravango.core.model.WordTiming
import com.ravango.engine.ai.text.ListParser
import com.ravango.engine.ai.video.TranscriptUnit
import com.ravango.engine.ai.video.VideoLlmParsing
import org.junit.Test

class LlmOutputParsingTest {

    @Test
    fun `extracts JSON from fenced block with prose`() {
        val text = "Sure! Here you go:\n```json\n{\"highlights\": [{\"index\": 1, \"score\": 0.9}]}\n```\nHope it helps."
        assertThat(LlmJson.extract(text)).isEqualTo("{\"highlights\": [{\"index\": 1, \"score\": 0.9}]}")
    }

    @Test
    fun `extracts first balanced object with braces inside strings`() {
        val text = "prefix {\"a\": \"x } y\", \"b\": [1, {\"c\": 2}]} trailing {\"z\":1}"
        assertThat(LlmJson.extract(text)).isEqualTo("{\"a\": \"x } y\", \"b\": [1, {\"c\": 2}]}")
    }

    @Test
    fun `tolerates trailing commas`() {
        assertThat(LlmJson.parseElement("{\"a\": [1, 2,],}")).isNotNull()
    }

    @Test
    fun `returns null for no json`() {
        assertThat(LlmJson.parseElement("I could not do that.")).isNull()
    }

    private fun units(n: Int, secondsEach: Long = 5): List<TranscriptUnit> = (0 until n).map { i ->
        val s = i * secondsEach * 1_000_000
        val e = s + secondsEach * 1_000_000 - 100_000
        TranscriptUnit(i, s, e, "unit $i", listOf(WordTiming("unit", s, e)))
    }

    @Test
    fun `highlights drop invalid indices and normalize 0-10 scores`() {
        val parsed = VideoLlmParsing.parseHighlights("""```{"highlights":[{"index":2,"score":8,"title":" A ","reason":"r"},{"index":99,"score":1}]}```""", 5)!!
        assertThat(parsed).hasSize(1)
        assertThat(parsed[0].index).isEqualTo(2)
        assertThat(parsed[0].score).isWithin(1e-6).of(0.8)
        assertThat(parsed[0].title).isEqualTo("A")
    }

    @Test
    fun `shorts are validated, snapped to units and trimmed to target`() {
        val json = """
            Here are clips:
            {"shorts":[
              {"title":"T1","hook":"H","caption":"C","score":0.9,"units":[[6,6],[0,3]]},
              {"title":"bad","units":[[50,60]]},
              {"title":"overlap","units":[[1,2],[2,3]]}
            ]}
        """.trimIndent()
        val u = units(10)
        val shorts = VideoLlmParsing.parseShorts(json, u, maxDurationUs = 16_000_000)!!
        assertThat(shorts.map { it.title }).containsExactly("T1", "overlap").inOrder()
        val first = shorts[0]
        // Hook first (unit 6), then units 0..1 only because of the 16 s cap.
        assertThat(first.ranges[0].startUs).isEqualTo(u[6].startUs)
        assertThat(first.ranges[1].startUs).isEqualTo(u[0].startUs)
        assertThat(first.ranges[1].endUs).isEqualTo(u[1].endUs)
        assertThat(first.durationUs).isAtMost(16_000_000)
        // Overlapping second range is dropped.
        assertThat(shorts[1].ranges).hasSize(1)
    }

    @Test
    fun `shorts shorter than minimum are rejected`() {
        val shorts = VideoLlmParsing.parseShorts("""{"shorts":[{"title":"x","units":[[0,0]]}]}""", units(3, secondsEach = 2), maxDurationUs = 60_000_000)!!
        assertThat(shorts).isEmpty()
    }

    @Test
    fun `list parser handles Persian digits, multi-line items and preamble`() {
        val text = "حتماً! این‌ها پیشنهادها هستند:\n\n۱. «اولین قلاب»\n\n۲- دومین\nادامهٔ دومی\n\n3) سومی"
        assertThat(ListParser.parse(text)).containsExactly("اولین قلاب", "دومین\nادامهٔ دومی", "سومی").inOrder()
    }

    @Test
    fun `list parser falls back to paragraphs`() {
        assertThat(ListParser.parse("one\n\ntwo\n\n")).containsExactly("one", "two").inOrder()
    }

    @Test
    fun `list parser strips bold markers`() {
        assertThat(ListParser.parse("1. **Title one**\n2. **Title two**")).containsExactly("Title one", "Title two").inOrder()
    }
}
