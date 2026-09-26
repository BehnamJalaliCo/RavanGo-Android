package com.ravango.feature.scripts.importing

import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Subtitles (.srt / .vtt): keeps the spoken lines, drops indices, timecodes, cue settings and styling tags. */
object SubtitleText {
    private val TIMECODE = Regex("""^\s*(\d{1,2}:)?\d{1,2}:\d{2}[.,]\d{1,3}\s*-->\s*(\d{1,2}:)?\d{1,2}:\d{2}[.,]\d{1,3}.*$""")
    private val TAGS = Regex("""<[^>]+>|\{\\[^}]*}""")

    fun extract(text: String): String {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').lines()
        val out = StringBuilder()
        var i = 0
        var inCue = false
        var skipBlock = false
        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trim()
            when {
                i == 0 && line.startsWith("WEBVTT") -> skipBlock = true
                line.isEmpty() -> {
                    if (inCue) out.append('\n')
                    inCue = false
                    skipBlock = false
                }
                skipBlock -> Unit
                TIMECODE.matches(line) -> inCue = true
                inCue -> {
                    val clean = TAGS.replace(line, "").trim()
                    if (clean.isNotEmpty()) {
                        if (out.isNotEmpty() && out.last() != '\n') out.append(' ')
                        out.append(clean)
                    }
                }
                line.startsWith("NOTE") || line == "STYLE" || line == "REGION" -> skipBlock = true
                // Anything else outside a cue is a cue number (SRT) or identifier (VTT).
                else -> Unit
            }
            i++
        }
        // Each cue ended with '\n'; merge cues into flowing paragraphs, separated where a cue ends a sentence.
        val cues = out.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val result = StringBuilder()
        for (cue in cues) {
            if (result.isNotEmpty()) {
                val endsSentence = result.last() in ".!?؟…"
                result.append(if (endsSentence) "\n" else " ")
            }
            result.append(cue)
        }
        return result.toString()
    }
}

/** Basic HTML → text: block elements become line breaks, <b>/<strong> become `**emphasis**`, entities decoded. */
object HtmlText {
    private val DROP = Regex("""(?is)<(script|style|head|title|noscript)\b.*?</\1\s*>""")
    private val COMMENT = Regex("""(?s)<!--.*?-->""")
    private val BREAK = Regex("""(?i)<br\s*/?>""")
    private val BLOCK_END = Regex("""(?i)</(p|div|h[1-6]|li|tr|blockquote|section|article|header|footer|ul|ol|table)\s*>""")
    private val BLOCK_START = Regex("""(?i)<(p|div|h[1-6]|li|tr|blockquote|section|article|header|footer)\b[^>]*>""")
    private val BOLD = Regex("""(?is)<(b|strong)\b[^>]*>(.*?)</\1\s*>""")
    private val TAG = Regex("""<[^>]*>""")

    fun extract(html: String): String {
        var s = COMMENT.replace(html, "")
        s = DROP.replace(s, "")
        s = s.replace(Regex("""[\r\n\t]+"""), " ")
        s = BOLD.replace(s) { m -> val inner = m.groupValues[2]; if (TAG.replace(inner, "").isBlank()) inner else "**$inner**" }
        s = BREAK.replace(s, "\n")
        s = BLOCK_START.replace(s, "\n")
        s = BLOCK_END.replace(s, "\n")
        s = TAG.replace(s, "")
        s = decodeEntities(s)
        return s.lines().joinToString("\n") { it.replace(Regex(" {2,}"), " ").trim() }
    }

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "zwnj" to "‌", "zwj" to "‍", "rlm" to "‏", "lrm" to "‎",
        "hellip" to "…", "mdash" to "—", "ndash" to "–", "laquo" to "«", "raquo" to "»",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
    )
    private val ENTITY = Regex("""&(#x[0-9a-fA-F]+|#\d+|[a-zA-Z]+);""")

    fun decodeEntities(text: String): String = ENTITY.replace(text) { m ->
        val body = m.groupValues[1]
        when {
            body.startsWith("#x") || body.startsWith("#X") -> body.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            body.startsWith("#") -> body.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            else -> NAMED[body.lowercase()] ?: m.value
        }
    }
}

/**
 * Minimal RTF reader: strips control words and ignorable destinations (font/colour tables, pictures, metadata),
 * maps `\par`/`\line`/`\tab`, decodes `\'hh` (in the document's ANSI code page) and `\uN` escapes, and keeps bold
 * as `**emphasis**`.
 */
object RtfText {
    private val SKIP_DESTINATIONS = setOf(
        "fonttbl", "colortbl", "stylesheet", "info", "pict", "object", "header", "footer", "headerl", "headerr",
        "footerl", "footerr", "footnote", "listtable", "listoverridetable", "rsidtbl", "generator", "xmlnstbl",
        "themedata", "colorschememapping", "latentstyles", "datastore", "filetbl", "revtbl", "pgdsctbl",
    )

    private class Group(var skip: Boolean, var bold: Boolean, var ucSkip: Int)

    fun extract(rtf: String): String {
        val out = StringBuilder()
        val stack = ArrayDeque<Group>()
        var group = Group(skip = false, bold = false, ucSkip = 1)
        var codePage: java.nio.charset.Charset = charset(1252)
        var pendingSkip = 0
        val hexBytes = java.io.ByteArrayOutputStream()

        fun flushHex() {
            if (hexBytes.size() == 0) return
            if (!group.skip) out.append(String(hexBytes.toByteArray(), codePage))
            hexBytes.reset()
        }

        fun setBold(on: Boolean) {
            if (group.skip || group.bold == on) { group.bold = on; return }
            // Close/open emphasis around bold runs; skip empty runs.
            if (on) out.append("**") else {
                if (out.endsWith("**")) out.setLength(out.length - 2) else out.append("**")
            }
            group.bold = on
        }

        var i = 0
        val n = rtf.length
        while (i < n) {
            val c = rtf[i]
            when (c) {
                '{' -> {
                    flushHex()
                    stack.addLast(Group(group.skip, group.bold, group.ucSkip))
                    i++
                    // "{\*\dest" marks an ignorable destination.
                    if (rtf.startsWith("\\*", i)) group.skip = true
                }
                '}' -> {
                    flushHex()
                    val wasBold = group.bold
                    group = stack.removeLastOrNull() ?: Group(false, false, 1)
                    if (wasBold && !group.bold && !group.skip) {
                        if (out.endsWith("**")) out.setLength(out.length - 2) else out.append("**")
                    }
                    i++
                }
                '\\' -> {
                    if (i + 1 >= n) { i++; continue }
                    val next = rtf[i + 1]
                    when {
                        next == '\'' && i + 3 < n -> {
                            val hex = rtf.substring(i + 2, i + 4).toIntOrNull(16)
                            if (pendingSkip > 0) pendingSkip-- else if (hex != null) hexBytes.write(hex)
                            i += 4
                        }
                        next == '\\' || next == '{' || next == '}' -> {
                            flushHex(); if (!group.skip) out.append(next); i += 2
                        }
                        next == '~' -> { flushHex(); if (!group.skip) out.append(' '); i += 2 }
                        next == '-' || next == '_' -> { i += 2 }
                        next == '\n' || next == '\r' -> { flushHex(); if (!group.skip) out.append('\n'); i += 2 }
                        next.isLetter() -> {
                            flushHex()
                            var j = i + 1
                            while (j < n && rtf[j].isLetter()) j++
                            val word = rtf.substring(i + 1, j)
                            var k = j
                            if (k < n && (rtf[k] == '-' || rtf[k].isDigit())) {
                                k++
                                while (k < n && rtf[k].isDigit()) k++
                            }
                            val param = rtf.substring(j, k).toIntOrNull()
                            if (k < n && rtf[k] == ' ') k++ // delimiter space belongs to the control word
                            i = k
                            when (word) {
                                "par", "line", "sect", "page" -> if (!group.skip) out.append('\n')
                                "tab" -> if (!group.skip) out.append('\t')
                                "b" -> setBold(param == null || param != 0)
                                "plain" -> setBold(false)
                                "u" -> if (param != null && !group.skip) {
                                    out.append((if (param < 0) param + 65536 else param).toChar())
                                    pendingSkip = group.ucSkip
                                }
                                "uc" -> group.ucSkip = param ?: 1
                                "ansicpg" -> codePage = charset(param ?: 1252)
                                "emdash" -> if (!group.skip) out.append('—')
                                "endash" -> if (!group.skip) out.append('–')
                                "lquote" -> if (!group.skip) out.append('‘')
                                "rquote" -> if (!group.skip) out.append('’')
                                "ldblquote" -> if (!group.skip) out.append('“')
                                "rdblquote" -> if (!group.skip) out.append('”')
                                "bullet" -> if (!group.skip) out.append('•')
                                else -> if (word in SKIP_DESTINATIONS) group.skip = true
                            }
                        }
                        else -> i += 2
                    }
                }
                '\r', '\n' -> i++
                else -> {
                    flushHex()
                    if (pendingSkip > 0) pendingSkip-- else if (!group.skip) out.append(c)
                    i++
                }
            }
        }
        flushHex()
        return out.toString()
    }

    private fun charset(codePage: Int): java.nio.charset.Charset =
        runCatching { java.nio.charset.Charset.forName("windows-$codePage") }.getOrNull()
            ?: runCatching { java.nio.charset.Charset.forName("cp$codePage") }.getOrNull()
            ?: Charsets.ISO_8859_1
}

/**
 * .docx reader: unzips `word/document.xml` and walks paragraphs/runs with an [XmlPullParser], keeping bold runs as
 * `**emphasis**`, headings (Heading/Title styles) as `## sections`, tabs and line breaks.
 */
class DocxText(private val newParser: () -> XmlPullParser) {

    fun extract(docx: InputStream): String {
        val xml = readEntry(docx, "word/document.xml") ?: throw IllegalArgumentException("Not a Word document")
        return extractDocumentXml(xml)
    }

    fun extractDocumentXml(xml: ByteArray): String {
        val parser = newParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(xml), "UTF-8")
        val out = StringBuilder()
        val paragraph = StringBuilder()
        var runText = StringBuilder()
        var inRun = false
        var inRunProps = false
        var runBold = false
        var inText = false
        var heading = false
        var inParaProps = false

        fun flushRun() {
            val text = runText.toString()
            if (text.isNotEmpty()) {
                if (runBold && text.isNotBlank() && !heading) {
                    // Merge adjacent bold runs: "**a****b**" → "**ab**".
                    if (paragraph.endsWith("**")) {
                        paragraph.setLength(paragraph.length - 2)
                        paragraph.append(text).append("**")
                    } else {
                        val lead = text.takeWhile { it == ' ' }
                        val trail = text.takeLastWhile { it == ' ' }
                        paragraph.append(lead).append("**").append(text.trim()).append("**").append(trail)
                    }
                } else {
                    paragraph.append(text)
                }
            }
            runText = StringBuilder()
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val name = parser.name
            when (event) {
                XmlPullParser.START_TAG -> when (name) {
                    "w:p" -> { paragraph.setLength(0); heading = false }
                    "w:pPr" -> inParaProps = true
                    "w:pStyle" -> if (inParaProps) {
                        val style = parser.getAttributeValue(null, "w:val").orEmpty().lowercase()
                        if (style.startsWith("heading") || style == "title" || style.startsWith("titre") || style.startsWith("berschrift")) heading = true
                    }
                    "w:r" -> { inRun = true; runBold = false; runText = StringBuilder() }
                    "w:rPr" -> inRunProps = true
                    "w:b" -> if (inRunProps) {
                        val v = parser.getAttributeValue(null, "w:val")
                        runBold = v == null || (v != "0" && v != "false")
                    }
                    "w:t" -> inText = true
                    "w:tab" -> if (inRun && !inRunProps) runText.append('\t')
                    "w:br", "w:cr" -> if (inRun) runText.append('\n')
                }
                XmlPullParser.TEXT -> if (inText) runText.append(parser.text)
                XmlPullParser.END_TAG -> when (name) {
                    "w:t" -> inText = false
                    "w:rPr" -> inRunProps = false
                    "w:pPr" -> inParaProps = false
                    "w:r" -> { flushRun(); inRun = false }
                    "w:p" -> {
                        val text = paragraph.toString()
                        if (heading && text.isNotBlank()) out.append("## ").append(text.trim()) else out.append(text)
                        out.append('\n')
                        paragraph.setLength(0)
                    }
                }
            }
            event = parser.next()
        }
        return out.toString()
    }

    companion object {
        fun readEntry(zip: InputStream, entryName: String): ByteArray? {
            ZipInputStream(zip).use { stream ->
                while (true) {
                    val entry = stream.nextEntry ?: return null
                    if (entry.name == entryName) return stream.readBytes()
                }
            }
        }
    }
}
