package com.ravango.engine.ai.text

/**
 * Splits a model's numbered/bulleted list into items. Handles ASCII and Persian/Arabic-Indic digits, "1." "1)" "۱-"
 * markers, bullets, multi-line items (continuation lines join the current item), stray bold markers and quotes.
 * Text before the first marker (a preamble the model was told not to write) is dropped when markers exist.
 */
object ListParser {

    private val NUMBERED = Regex("""^\s*(?:\*\*)?[(\[]?([0-9۰-۹٠-٩]{1,2})\s*[.)\-:،\]]\s*(?:\*\*)?\s*(.*)$""")
    private val BULLET = Regex("""^\s*[-•*–]\s+(.*)$""")

    fun parse(text: String): List<String> {
        val lines = text.replace("\r\n", "\n").lines()
        val numbered = lines.any { NUMBERED.matches(it) }
        val bulleted = !numbered && lines.any { BULLET.matches(it) }
        if (!numbered && !bulleted) {
            // No markers: one item per non-empty paragraph.
            return text.split(Regex("""\n\s*\n""")).map { clean(it) }.filter { it.isNotEmpty() }
        }
        val items = mutableListOf<StringBuilder>()
        for (raw in lines) {
            val m = if (numbered) NUMBERED.matchEntire(raw) else BULLET.matchEntire(raw)
            when {
                m != null -> items += StringBuilder(m.groupValues.last())
                items.isNotEmpty() && raw.isNotBlank() -> items.last().append('\n').append(raw.trim())
            }
        }
        return items.map { clean(it.toString()) }.filter { it.isNotEmpty() }
    }

    private fun clean(s: String): String {
        var t = s.trim()
        t = t.removeSurrounding("**").trim()
        if (t.length >= 2) {
            val pairs = listOf('"' to '"', '«' to '»', '“' to '”', '\'' to '\'')
            for ((a, b) in pairs) if (t.first() == a && t.last() == b && t.count { it == a || it == b } == 2) t = t.substring(1, t.length - 1).trim()
        }
        return t
    }
}
