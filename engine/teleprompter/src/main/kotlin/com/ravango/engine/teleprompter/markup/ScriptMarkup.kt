package com.ravango.engine.teleprompter.markup

/** Kinds of markup recognised in a script body (see [com.ravango.core.model.Script]). */
enum class MarkupKind { SECTION, HIGHLIGHT, EMPHASIS, NOTE, PAUSE }

/** A styled range in the *display* text: `[start, end)`. */
data class MarkupSpan(val start: Int, val end: Int, val kind: MarkupKind)

/** A `## Section` heading. Offsets are given both in display text and in the original source. */
data class ParsedSection(val index: Int, val title: String, val displayOffset: Int, val sourceOffset: Int)

/**
 * Result of parsing a script body.
 *
 * - [text] is the display text: markup delimiters are removed, `[pause]` tokens become a single [PAUSE_CHAR],
 *   director notes keep their content (styled differently) and section headings keep their title.
 * - Offsets are convertible in both directions between source and display text, so positions persisted on a
 *   [com.ravango.core.model.Script] (which are source offsets) survive re-parsing and markup changes.
 * - [words] indexes spoken words and pauses for timing; notes are excluded.
 */
class ParsedScript internal constructor(
    val source: String,
    val text: String,
    val spans: List<MarkupSpan>,
    val sections: List<ParsedSection>,
    private val displayToSourceMap: IntArray,
    private val sourceToDisplayMap: IntArray,
    val words: WordIndex,
) {
    val totalWords: Int get() = words.totalWords
    val totalPauses: Int get() = words.totalPauses

    /** Maps a source offset (0..source.length) to the display offset of the same logical position. */
    fun sourceToDisplay(sourceOffset: Int): Int = sourceToDisplayMap[sourceOffset.coerceIn(0, source.length)]

    /** Maps a display offset (0..text.length) back to the source offset. */
    fun displayToSource(displayOffset: Int): Int = displayToSourceMap[displayOffset.coerceIn(0, text.length)]

    /** Start offsets (display) of every non-blank paragraph, used for "start from this paragraph". */
    fun paragraphs(): List<IntRange> {
        val result = ArrayList<IntRange>()
        var start = 0
        while (start <= text.length) {
            val nl = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            if (text.substring(start, nl).isNotBlank()) result += start until nl
            start = nl + 1
        }
        return result
    }

    companion object {
        val Empty: ParsedScript = ScriptMarkup.parse("")
    }
}

/**
 * Cumulative spoken-word and pause counts over the display text.
 * `wordsBefore[i]` = number of spoken words that start strictly before display offset `i`.
 */
class WordIndex internal constructor(
    private val wordsBefore: IntArray,
    private val pausesBefore: IntArray,
) {
    val length: Int get() = wordsBefore.size - 1
    val totalWords: Int get() = wordsBefore[length]
    val totalPauses: Int get() = pausesBefore[length]

    fun wordsBefore(offset: Int): Int = wordsBefore[offset.coerceIn(0, length)]
    fun pausesBefore(offset: Int): Int = pausesBefore[offset.coerceIn(0, length)]

    /** Spoken words starting in `[start, end)`. */
    fun wordsIn(start: Int, end: Int): Int = wordsBefore(end) - wordsBefore(start)
    fun pausesIn(start: Int, end: Int): Int = pausesBefore(end) - pausesBefore(start)
}

/** Parser for RavanGo's lightweight teleprompter markup. Pure Kotlin; safe to call on any thread. */
object ScriptMarkup {
    /** Glyph that stands in for a `[pause]` cue in the display text (rendered as a chip by the view). */
    const val PAUSE_CHAR: Char = '⏸'

    private val PAUSE_TOKENS = listOf("[pause]", "[مکث]")

    fun parse(source: String): ParsedScript {
        val len = source.length
        val out = StringBuilder(len)
        val d2s = IntArray(len + 1)
        val s2d = IntArray(len + 1)
        val spans = ArrayList<MarkupSpan>()
        val sections = ArrayList<ParsedSection>()

        var i = 0
        var atLineStart = true
        var sectionStart = -1
        var sectionSource = -1
        var highlightStart = -1
        var emphasisStart = -1

        fun emit(sourceIndex: Int, ch: Char) {
            s2d[sourceIndex] = out.length
            d2s[out.length] = sourceIndex
            out.append(ch)
        }

        fun skip(from: Int, to: Int) {
            for (p in from until to) s2d[p] = out.length
        }

        fun closeInlineAtLineEnd() {
            // Openers are only accepted when a closer exists on the same line, so nothing is left open here;
            // guard anyway so malformed input can never produce an inverted span.
            if (highlightStart >= 0) { spans += MarkupSpan(highlightStart, out.length, MarkupKind.HIGHLIGHT); highlightStart = -1 }
            if (emphasisStart >= 0) { spans += MarkupSpan(emphasisStart, out.length, MarkupKind.EMPHASIS); emphasisStart = -1 }
        }

        fun closeSection() {
            if (sectionStart < 0) return
            val end = out.length
            val title = out.substring(sectionStart, end).trim()
            if (end > sectionStart) spans += MarkupSpan(sectionStart, end, MarkupKind.SECTION)
            sections += ParsedSection(sections.size, title, sectionStart, sectionSource)
            sectionStart = -1
        }

        while (i < len) {
            if (atLineStart) {
                atLineStart = false
                var j = i
                while (j < len && (source[j] == ' ' || source[j] == '\t')) j++
                if (source.startsWith("##", j)) {
                    var k = j + 2
                    while (k < len && source[k] == '#') k++
                    while (k < len && (source[k] == ' ' || source[k] == '\t')) k++
                    skip(i, k)
                    sectionStart = out.length
                    sectionSource = i
                    i = k
                    continue
                }
            }
            val c = source[i]
            when {
                c == '\n' -> {
                    closeInlineAtLineEnd()
                    closeSection()
                    emit(i, c)
                    atLineStart = true
                    i++
                }
                c == '[' && source.startsWith("[[", i) -> {
                    val close = source.indexOf("]]", i + 2)
                    if (close < 0) {
                        emit(i, c); i++
                    } else {
                        skip(i, i + 2)
                        val noteStart = out.length
                        for (p in i + 2 until close) emit(p, source[p])
                        skip(close, close + 2)
                        if (out.length > noteStart) spans += MarkupSpan(noteStart, out.length, MarkupKind.NOTE)
                        i = close + 2
                    }
                }
                c == '[' && pauseTokenAt(source, i) > 0 -> {
                    val tokenLen = pauseTokenAt(source, i)
                    val at = out.length
                    s2d[i] = at
                    d2s[at] = i
                    out.append(PAUSE_CHAR)
                    for (p in i + 1 until i + tokenLen) s2d[p] = at + 1
                    spans += MarkupSpan(at, at + 1, MarkupKind.PAUSE)
                    i += tokenLen
                }
                c == '=' && source.startsWith("==", i) -> {
                    if (highlightStart >= 0) {
                        skip(i, i + 2)
                        if (out.length > highlightStart) spans += MarkupSpan(highlightStart, out.length, MarkupKind.HIGHLIGHT)
                        highlightStart = -1
                        i += 2
                    } else if (hasCloserOnLine(source, i + 2, "==")) {
                        skip(i, i + 2)
                        highlightStart = out.length
                        i += 2
                    } else {
                        emit(i, c); i++
                    }
                }
                c == '*' && source.startsWith("**", i) -> {
                    if (emphasisStart >= 0) {
                        skip(i, i + 2)
                        if (out.length > emphasisStart) spans += MarkupSpan(emphasisStart, out.length, MarkupKind.EMPHASIS)
                        emphasisStart = -1
                        i += 2
                    } else if (hasCloserOnLine(source, i + 2, "**")) {
                        skip(i, i + 2)
                        emphasisStart = out.length
                        i += 2
                    } else {
                        emit(i, c); i++
                    }
                }
                else -> { emit(i, c); i++ }
            }
        }
        closeInlineAtLineEnd()
        closeSection()
        s2d[len] = out.length
        d2s[out.length] = len

        val display = out.toString()
        val displayToSource = d2s.copyOf(display.length + 1)
        spans.sortBy { it.start }
        return ParsedScript(
            source = source,
            text = display,
            spans = spans,
            sections = sections,
            displayToSourceMap = displayToSource,
            sourceToDisplayMap = s2d,
            words = buildWordIndex(display, spans),
        )
    }

    /** Spoken word count of a raw script body (notes and markup excluded). ZWNJ-joined Persian words count once. */
    fun countSpokenWords(source: String): Int = parse(source).totalWords

    /** Plain text as it should be read (markup and notes removed), e.g. for AI tools or exports. */
    fun toPlainText(source: String): String {
        val parsed = parse(source)
        val noteOrPause = parsed.spans.filter { it.kind == MarkupKind.NOTE || it.kind == MarkupKind.PAUSE }
        if (noteOrPause.isEmpty()) return parsed.text
        val sb = StringBuilder()
        var last = 0
        for (span in noteOrPause) {
            if (span.start > last) sb.append(parsed.text, last, span.start)
            last = maxOf(last, span.end)
        }
        if (last < parsed.text.length) sb.append(parsed.text, last, parsed.text.length)
        return sb.toString().replace(Regex("[ \\t]{2,}"), " ")
    }

    private fun pauseTokenAt(source: String, index: Int): Int {
        for (token in PAUSE_TOKENS) if (source.regionMatches(index, token, 0, token.length, ignoreCase = true)) return token.length
        return 0
    }

    private fun hasCloserOnLine(source: String, from: Int, marker: String): Boolean {
        val lineEnd = source.indexOf('\n', from).let { if (it < 0) source.length else it }
        val idx = source.indexOf(marker, from)
        // The marked text must be non-empty ("====" and "****" stay literal).
        return idx > from && idx < lineEnd
    }

    /** Word separators: whitespace and bidi marks. ZWNJ is *not* a separator (it joins Persian morphemes). */
    internal fun isSeparator(ch: Char): Boolean = ch.isWhitespace() || ch == '‏' || ch == '‎' || ch == ' '

    internal fun buildWordIndex(text: String, spans: List<MarkupSpan>): WordIndex {
        val n = text.length
        val excluded = BooleanArray(n)
        val pauseAt = BooleanArray(n)
        for (span in spans) {
            when (span.kind) {
                MarkupKind.NOTE -> for (p in span.start until span.end) excluded[p] = true
                MarkupKind.PAUSE -> for (p in span.start until span.end) { excluded[p] = true; pauseAt[p] = true }
                else -> Unit
            }
        }
        val wordsBefore = IntArray(n + 1)
        val pausesBefore = IntArray(n + 1)
        var words = 0
        var pauses = 0
        var i = 0
        while (i < n) {
            val sep = excluded[i] || isSeparator(text[i])
            if (sep) {
                wordsBefore[i] = words
                pausesBefore[i] = pauses
                if (pauseAt[i]) pauses++
                i++
                continue
            }
            // Token [i, j)
            var j = i
            var hasLetter = false
            while (j < n && !excluded[j] && !isSeparator(text[j])) {
                if (text[j].isLetterOrDigit()) hasLetter = true
                j++
            }
            for (p in i until j) {
                wordsBefore[p] = words
                pausesBefore[p] = pauses
                if (p == i && hasLetter) words++
            }
            i = j
        }
        wordsBefore[n] = words
        pausesBefore[n] = pauses
        return WordIndex(wordsBefore, pausesBefore)
    }
}
