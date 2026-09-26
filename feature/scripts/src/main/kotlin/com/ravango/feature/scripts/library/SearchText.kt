package com.ravango.feature.scripts.library

/**
 * Persian-aware, accent-insensitive search. Arabic/Persian letter variants (ي/ی, ك/ک, ة/ه, أ/إ/آ→ا), digits
 * (۱/١/1), case, ZWNJ, tatweel and diacritics are normalised so "میخواهم" finds "می‌خواهم" and "كتاب" finds "کتاب".
 * Match ranges refer to the original text so they can be highlighted.
 */
object SearchText {

    /** Normalised text plus, for each normalised char, its index in the original. */
    class Normalized(val text: String, private val origin: IntArray) {
        fun originalIndex(i: Int): Int = origin[i]
    }

    fun normalize(source: String): Normalized {
        val sb = StringBuilder(source.length)
        val origin = IntArray(source.length + 1)
        for ((i, raw) in source.withIndex()) {
            val mapped = map(raw) ?: continue
            origin[sb.length] = i
            sb.append(mapped)
        }
        origin[sb.length] = source.length
        return Normalized(sb.toString(), origin.copyOf(sb.length + 1))
    }

    /** Normalised form of a single char, or null when it should be ignored. */
    private fun map(c: Char): Char? = when (c) {
        '‌', '‍', '‎', '‏', 'ـ' -> null // ZWNJ/ZWJ, bidi marks, tatweel
        in 'ً'..'ٟ', 'ٰ' -> null // harakat
        'ي', 'ى', 'ئ' -> 'ی'
        'ك' -> 'ک'
        'ة' -> 'ه'
        'أ', 'إ', 'آ', 'ٱ' -> 'ا'
        'ؤ' -> 'و'
        in '۰'..'۹' -> '0' + (c - '۰')
        in '٠'..'٩' -> '0' + (c - '٠')
        else -> c.lowercaseChar()
    }

    /** Query terms (whitespace separated, normalised, non-empty). */
    fun terms(query: String): List<String> =
        query.split(Regex("\\s+")).map { normalize(it).text }.filter { it.isNotEmpty() }.distinct()

    /** True when every term occurs in at least one of [fields]. */
    fun matchesAll(terms: List<String>, vararg fields: String): Boolean {
        if (terms.isEmpty()) return true
        val normalized = fields.map { normalize(it).text }
        return terms.all { term -> normalized.any { it.contains(term) } }
    }

    /** Ranges (in [text]) of all occurrences of any of [terms], merged and sorted. */
    fun matchRanges(text: String, terms: List<String>): List<IntRange> {
        if (terms.isEmpty() || text.isEmpty()) return emptyList()
        val n = normalize(text)
        val ranges = ArrayList<IntRange>()
        for (term in terms) {
            var from = 0
            while (true) {
                val idx = n.text.indexOf(term, from)
                if (idx < 0) break
                val start = n.originalIndex(idx)
                val end = n.originalIndex(idx + term.length - 1)
                ranges += start..end
                from = idx + term.length
            }
        }
        if (ranges.isEmpty()) return ranges
        ranges.sortBy { it.first }
        val merged = ArrayList<IntRange>()
        var current = ranges[0]
        for (r in ranges.drop(1)) {
            current = if (r.first <= current.last + 1) current.first..maxOf(current.last, r.last) else { merged += current; r }
        }
        merged += current
        return merged
    }

    /**
     * A short excerpt of [text] around the first match (or the start), on whole-word boundaries, with an ellipsis
     * where it was cut.
     */
    fun snippet(text: String, terms: List<String>, maxLength: Int = 140): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        if (flat.length <= maxLength) return flat
        val first = matchRanges(flat, terms).firstOrNull()?.first ?: 0
        var start = (first - maxLength / 3).coerceAtLeast(0)
        if (start > 0) {
            val space = flat.indexOf(' ', start)
            if (space in start until first) start = space + 1
        }
        var end = (start + maxLength).coerceAtMost(flat.length)
        if (end < flat.length) {
            val space = flat.lastIndexOf(' ', end)
            if (space > start + maxLength / 2) end = space
        }
        return (if (start > 0) "…" else "") + flat.substring(start, end) + (if (end < flat.length) "…" else "")
    }
}
