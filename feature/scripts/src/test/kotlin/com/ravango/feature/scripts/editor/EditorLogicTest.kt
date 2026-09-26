package com.ravango.feature.scripts.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EditorLogicTest {

    @Test
    fun `wrap selection and toggle it off again`() {
        val start = TextFieldValue("say hello now", TextRange(4, 9))
        val wrapped = MarkupEdits.wrap(start, "**", "**", "text")
        assertThat(wrapped.text).isEqualTo("say **hello** now")
        assertThat(wrapped.selection).isEqualTo(TextRange(6, 11))
        val unwrapped = MarkupEdits.wrap(wrapped, "**", "**", "text")
        assertThat(unwrapped.text).isEqualTo("say hello now")
        assertThat(unwrapped.selection).isEqualTo(TextRange(4, 9))
    }

    @Test
    fun `wrap keeps surrounding whitespace outside markers`() {
        val value = TextFieldValue("a word b", TextRange(1, 7))
        assertThat(MarkupEdits.wrap(value, "==", "==", "x").text).isEqualTo("a ==word== b")
    }

    @Test
    fun `wrap with empty selection inserts a selected placeholder`() {
        val value = TextFieldValue("ab", TextRange(1))
        val result = MarkupEdits.wrap(value, "[[", "]]", "note")
        assertThat(result.text).isEqualTo("a[[note]]b")
        assertThat(result.selection).isEqualTo(TextRange(3, 7))
    }

    @Test
    fun `section toggles on the current line`() {
        val value = TextFieldValue("one\ntwo\nthree", TextRange(5))
        val on = MarkupEdits.toggleSection(value, "Title")
        assertThat(on.text).isEqualTo("one\n## two\nthree")
        val off = MarkupEdits.toggleSection(on, "Title")
        assertThat(off.text).isEqualTo("one\ntwo\nthree")
        val blank = MarkupEdits.toggleSection(TextFieldValue("a\n\nb", TextRange(2)), "Title")
        assertThat(blank.text).isEqualTo("a\n## Title\nb")
    }

    @Test
    fun `pause token gets spacing`() {
        val result = MarkupEdits.insertToken(TextFieldValue("ab", TextRange(1)), "[pause]")
        assertThat(result.text).isEqualTo("a [pause] b")
    }

    @Test
    fun `insert below adds a paragraph after the line`() {
        val value = TextFieldValue("first line\nsecond", TextRange(2, 4))
        val result = MarkupEdits.insertBelow(value, value.selection, "NEW")
        assertThat(result.text).isEqualTo("first line\n\nNEW\nsecond")
    }

    @Test
    fun `undo coalesces typing and separates discrete edits`() {
        val stack = UndoStack()
        val v0 = TextFieldValue("")
        val v1 = TextFieldValue("a")
        val v2 = TextFieldValue("ab")
        val v3 = TextFieldValue("**ab**")
        stack.record(v0, v1, now = 0)
        stack.record(v1, v2, now = 300)
        stack.record(v2, v3, now = 400, discrete = true)
        assertThat(stack.undo(v3)).isEqualTo(v2)
        assertThat(stack.undo(v2)).isEqualTo(v0) // typing merged into one step
        assertThat(stack.canUndo).isFalse()
        assertThat(stack.redo(v0)).isEqualTo(v2)
        assertThat(stack.redo(v2)).isEqualTo(v3)
    }

    @Test
    fun `new edit clears redo`() {
        val stack = UndoStack()
        stack.record(TextFieldValue("a"), TextFieldValue("ab"), now = 0)
        stack.undo(TextFieldValue("ab"))
        assertThat(stack.canRedo).isTrue()
        stack.record(TextFieldValue("a"), TextFieldValue("ac"), now = 5_000)
        assertThat(stack.canRedo).isFalse()
    }
}
