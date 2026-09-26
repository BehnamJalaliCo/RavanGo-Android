package com.ravango.feature.scripts.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Pure text transformations behind the editor's markup toolbar. Every function keeps a sensible selection. */
object MarkupEdits {

    /**
     * Wraps the selection in [prefix]/[suffix]. With an empty selection it inserts `prefix + placeholder + suffix`
     * and selects the placeholder. If the selection is already wrapped, the markers are removed (toggle).
     */
    fun wrap(value: TextFieldValue, prefix: String, suffix: String, placeholder: String): TextFieldValue {
        val text = value.text
        val sel = value.selection
        val start = sel.min
        val end = sel.max
        // Toggle off: markers just outside the selection.
        if (start >= prefix.length && end + suffix.length <= text.length &&
            text.regionMatches(start - prefix.length, prefix, 0, prefix.length) &&
            text.regionMatches(end, suffix, 0, suffix.length) && end > start
        ) {
            val newText = text.removeRange(end, end + suffix.length).removeRange(start - prefix.length, start)
            return TextFieldValue(newText, TextRange(start - prefix.length, end - prefix.length))
        }
        // Toggle off: markers included in the selection.
        val selected = text.substring(start, end)
        if (selected.length >= prefix.length + suffix.length + 1 && selected.startsWith(prefix) && selected.endsWith(suffix)) {
            val inner = selected.substring(prefix.length, selected.length - suffix.length)
            val newText = text.replaceRange(start, end, inner)
            return TextFieldValue(newText, TextRange(start, start + inner.length))
        }
        val inner = if (start == end) placeholder else selected
        // Keep surrounding whitespace outside the markers ("**word** " not "**word **").
        val lead = inner.takeWhile { it.isWhitespace() }
        val trail = inner.drop(lead.length).takeLastWhile { it.isWhitespace() }
        val core = inner.substring(lead.length, inner.length - trail.length).ifEmpty { placeholder }
        val replacement = lead + prefix + core + suffix + trail
        val newText = text.replaceRange(start, end, replacement)
        val coreStart = start + lead.length + prefix.length
        return TextFieldValue(newText, TextRange(coreStart, coreStart + core.length))
    }

    /** Turns the current line into a `## section` heading (or removes the marker when already one). */
    fun toggleSection(value: TextFieldValue, placeholder: String): TextFieldValue {
        val text = value.text
        val caret = value.selection.min
        val lineStart = text.lastIndexOf('\n', (caret - 1).coerceAtLeast(0)).let { if (caret == 0) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', caret).let { if (it < 0) text.length else it }
        val line = text.substring(lineStart, lineEnd)
        val marker = Regex("^\\s*#{2,}\\s*").find(line)
        if (marker != null) {
            val removed = marker.value.length
            val newText = text.removeRange(lineStart, lineStart + removed)
            val shift = { i: Int -> if (i <= lineStart) i else (i - removed).coerceAtLeast(lineStart) }
            return TextFieldValue(newText, TextRange(shift(value.selection.start), shift(value.selection.end)))
        }
        if (line.isBlank()) {
            val insert = "## $placeholder"
            val newText = text.replaceRange(lineStart, lineEnd, insert)
            return TextFieldValue(newText, TextRange(lineStart + 3, lineStart + insert.length))
        }
        val newText = text.substring(0, lineStart) + "## " + text.substring(lineStart)
        return TextFieldValue(newText, TextRange(value.selection.start + 3, value.selection.end + 3))
    }

    /** Inserts [token] (e.g. `[pause]`) at the selection end with sensible spacing. */
    fun insertToken(value: TextFieldValue, token: String): TextFieldValue {
        val text = value.text
        val at = value.selection.max
        val before = if (at > 0 && !text[at - 1].isWhitespace()) " " else ""
        val after = if (at < text.length && !text[at].isWhitespace()) " " else ""
        val insert = before + token + after
        val newText = text.substring(0, at) + insert + text.substring(at)
        val caret = at + insert.length
        return TextFieldValue(newText, TextRange(caret))
    }

    /** Replaces [range] with [replacement] and selects the inserted text. */
    fun replace(value: TextFieldValue, range: TextRange, replacement: String): TextFieldValue {
        val start = range.min.coerceIn(0, value.text.length)
        val end = range.max.coerceIn(start, value.text.length)
        val newText = value.text.replaceRange(start, end, replacement)
        return TextFieldValue(newText, TextRange(start, start + replacement.length))
    }

    /** Inserts [addition] as a new paragraph after [range]'s end (and after the end of that line). */
    fun insertBelow(value: TextFieldValue, range: TextRange, addition: String): TextFieldValue {
        val text = value.text
        val lineEnd = text.indexOf('\n', range.max.coerceIn(0, text.length)).let { if (it < 0) text.length else it }
        val insert = (if (lineEnd == 0) "" else "\n\n") + addition.trim()
        val newText = text.substring(0, lineEnd) + insert + text.substring(lineEnd)
        val startSel = lineEnd + insert.length - addition.trim().length
        return TextFieldValue(newText, TextRange(startSel, lineEnd + insert.length))
    }
}
