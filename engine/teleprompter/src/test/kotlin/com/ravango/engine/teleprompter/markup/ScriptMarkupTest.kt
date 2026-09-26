package com.ravango.engine.teleprompter.markup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScriptMarkupTest {

    @Test
    fun `plain text is unchanged and fully mapped`() {
        val parsed = ScriptMarkup.parse("Hello world")
        assertThat(parsed.text).isEqualTo("Hello world")
        assertThat(parsed.spans).isEmpty()
        assertThat(parsed.totalWords).isEqualTo(2)
        for (i in 0..11) {
            assertThat(parsed.sourceToDisplay(i)).isEqualTo(i)
            assertThat(parsed.displayToSource(i)).isEqualTo(i)
        }
    }

    @Test
    fun `highlight and emphasis delimiters are removed and spans recorded`() {
        val parsed = ScriptMarkup.parse("Say ==this== and **that** now")
        assertThat(parsed.text).isEqualTo("Say this and that now")
        val highlight = parsed.spans.single { it.kind == MarkupKind.HIGHLIGHT }
        assertThat(parsed.text.substring(highlight.start, highlight.end)).isEqualTo("this")
        val emphasis = parsed.spans.single { it.kind == MarkupKind.EMPHASIS }
        assertThat(parsed.text.substring(emphasis.start, emphasis.end)).isEqualTo("that")
        assertThat(parsed.totalWords).isEqualTo(5)
    }

    @Test
    fun `unclosed markers stay literal`() {
        val parsed = ScriptMarkup.parse("2 ** 3 and a == b\nnext ** line**")
        assertThat(parsed.text).isEqualTo("2 ** 3 and a == b\nnext  line")
        assertThat(parsed.spans.single().kind).isEqualTo(MarkupKind.EMPHASIS)
    }

    @Test
    fun `empty markers stay literal`() {
        val parsed = ScriptMarkup.parse("a ==== b")
        assertThat(parsed.text).isEqualTo("a ==== b")
        assertThat(parsed.spans).isEmpty()
    }

    @Test
    fun `sections are detected at line start with titles and offsets`() {
        val source = "## مقدمه\nسلام دوستان\n  ### Part two\nText"
        val parsed = ScriptMarkup.parse(source)
        assertThat(parsed.text).isEqualTo("مقدمه\nسلام دوستان\nPart two\nText")
        assertThat(parsed.sections.map { it.title }).containsExactly("مقدمه", "Part two").inOrder()
        assertThat(parsed.sections[0].sourceOffset).isEqualTo(0)
        assertThat(parsed.sections[0].displayOffset).isEqualTo(0)
        assertThat(parsed.sections[1].sourceOffset).isEqualTo(source.indexOf("  ###"))
        assertThat(parsed.text.substring(parsed.sections[1].displayOffset)).startsWith("Part two")
        assertThat(parsed.spans.count { it.kind == MarkupKind.SECTION }).isEqualTo(2)
    }

    @Test
    fun `hash inside a line is not a section`() {
        val parsed = ScriptMarkup.parse("Use ## here")
        assertThat(parsed.sections).isEmpty()
        assertThat(parsed.text).isEqualTo("Use ## here")
    }

    @Test
    fun `pause tokens in English and Persian become a single glyph`() {
        val parsed = ScriptMarkup.parse("یک [مکث] دو [PAUSE] three")
        assertThat(parsed.text).isEqualTo("یک ${ScriptMarkup.PAUSE_CHAR} دو ${ScriptMarkup.PAUSE_CHAR} three")
        assertThat(parsed.spans.count { it.kind == MarkupKind.PAUSE }).isEqualTo(2)
        assertThat(parsed.totalPauses).isEqualTo(2)
        assertThat(parsed.totalWords).isEqualTo(3)
    }

    @Test
    fun `director notes are shown but excluded from word counts, even across lines`() {
        val parsed = ScriptMarkup.parse("Hello [[smile, look\nat camera]] world")
        assertThat(parsed.text).isEqualTo("Hello smile, look\nat camera world")
        val note = parsed.spans.single { it.kind == MarkupKind.NOTE }
        assertThat(parsed.text.substring(note.start, note.end)).isEqualTo("smile, look\nat camera")
        assertThat(parsed.totalWords).isEqualTo(2)
    }

    @Test
    fun `zwnj joined persian words count once and punctuation is not a word`() {
        assertThat(ScriptMarkup.countSpokenWords("من می‌خواهم — بروم.")).isEqualTo(3)
    }

    @Test
    fun `source to display mapping skips delimiters`() {
        val source = "ab **cd** ef"
        val parsed = ScriptMarkup.parse(source)
        // 'c' in source (index 5) maps to display index 3
        assertThat(parsed.sourceToDisplay(5)).isEqualTo(3)
        assertThat(parsed.displayToSource(3)).isEqualTo(5)
        // 'e' (source 10) → display 6
        assertThat(parsed.sourceToDisplay(source.indexOf('e'))).isEqualTo(parsed.text.indexOf('e'))
        assertThat(parsed.sourceToDisplay(source.length)).isEqualTo(parsed.text.length)
        assertThat(parsed.displayToSource(parsed.text.length)).isEqualTo(source.length)
    }

    @Test
    fun `paragraph ranges skip blank lines`() {
        val parsed = ScriptMarkup.parse("one\n\n two\n")
        assertThat(parsed.paragraphs().map { parsed.text.substring(it.first, it.last + 1) }).containsExactly("one", " two").inOrder()
    }

    @Test
    fun `plain text export removes notes and pauses`() {
        assertThat(ScriptMarkup.toPlainText("Hi [pause] there [[wave]] ==friend==")).isEqualTo("Hi there friend")
    }
}
