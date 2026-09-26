package com.ravango.engine.ai.video

import com.ravango.core.media.dsp.TimeRange
import com.ravango.core.model.WordTiming
import com.ravango.engine.ai.api.CutKind
import com.ravango.engine.ai.api.CutSuggestion
import com.ravango.engine.ai.api.Transcript
import com.ravango.engine.ai.speech.WhisperParser

/**
 * Transcript-based cut suggestions (source time):
 * - **Hesitation sounds** (um, uh, erm, «اِم»، «اوم»، «ااا»…) — always fillers.
 * - **Discourse markers** («خب»، «یعنی»، «مثلاً»، «به هر حال»، like, you know, so, basically) — only when used as
 *   fillers: isolated by pauses/commas, at a phrase edge followed by a pause, or doubled. «مثلاً» / "like" used inside
 *   a fluent phrase ("for example", "I like it") are left alone.
 * - **Repetitions** — an n-gram (1–4 words) immediately repeated within [REPEAT_WINDOW_US]; the first copy is cut.
 * - **False starts** — a cut-off fragment ("pro- project", «رو- روان‌گو») or a token that is a strict prefix of the
 *   next word said right after it.
 */
object FillerDetector {

    const val REPEAT_WINDOW_US = 3_000_000L
    private const val PAUSE_US = 250_000L
    private const val EDGE_PAD_US = 60_000L

    private val HESITATIONS = setOf(
        "um", "umm", "uh", "uhh", "uhm", "erm", "er", "ah", "hmm", "mm", "mmm", "eh",
        "ام", "امم", "اممم", "اوم", "اومم", "ا", "اا", "ااا", "اه", "ممم", "مم", "هوم", "اِم",
    )

    /** Single-word discourse markers per language. */
    private val MARKERS = setOf("خب", "خوب", "یعنی", "مثلا", "like", "so", "basically", "actually", "literally", "well")

    /** Multi-word discourse markers, normalized. */
    private val PHRASE_MARKERS = listOf(listOf("به", "هر", "حال"), listOf("you", "know"), listOf("i", "mean"), listOf("kind", "of"), listOf("sort", "of"))

    /** Words that are commonly doubled for emphasis rather than by mistake. */
    private val EMPHATIC = setOf("very", "really", "no", "yes", "so", "ha", "خیلی", "نه", "آره", "بله", "هی", "آخ", "وای", "بدو")

    private val FOLLOWED_BY_CONTENT_EN = setOf("that", "what", "how", "why", "when", "where", "who", "the", "a", "an", "it", "this", "him", "her", "them", "me", "you")

    data class Token(val norm: String, val timing: WordTiming, val raw: String) {
        val clauseEnd: Boolean get() = raw.trimEnd().lastOrNull()?.let { it in ",،;؛:.!?؟…" } == true
    }

    fun detect(transcript: Transcript): List<CutSuggestion> {
        val tokens = tokens(transcript)
        if (tokens.isEmpty()) return emptyList()
        val cuts = mutableListOf<CutSuggestion>()
        val consumed = BooleanArray(tokens.size)

        // 1) Hesitations and discourse markers.
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.norm in HESITATIONS) {
                cuts += cut(tokens, i, i, CutKind.FILLER_WORD, 0.9f)
                consumed[i] = true
                i++
                continue
            }
            val phrase = PHRASE_MARKERS.firstOrNull { p -> p.indices.all { k -> tokens.getOrNull(i + k)?.norm == p[k] } }
            val span = phrase?.size ?: if (t.norm in MARKERS) 1 else 0
            if (span > 0 && isFillerUse(tokens, i, i + span - 1)) {
                cuts += cut(tokens, i, i + span - 1, CutKind.FILLER_WORD, if (span > 1) 0.7f else 0.6f)
                for (k in i until i + span) consumed[k] = true
                i += span
                continue
            }
            i++
        }

        // 2) False starts.
        for (k in 0 until tokens.size - 1) {
            if (consumed[k]) continue
            val t = tokens[k]
            val next = tokens[k + 1]
            val gap = next.timing.startUs - t.timing.endUs
            val cutOff = t.raw.trimEnd().let { it.endsWith("-") || it.endsWith("—") || it.endsWith("–") }
            val prefix = t.norm.length >= 2 && next.norm.length >= t.norm.length + 2 && next.norm.startsWith(t.norm) && gap < 700_000
            if ((cutOff && t.norm.isNotEmpty()) || prefix) {
                cuts += cut(tokens, k, k, CutKind.FALSE_START, if (cutOff) 0.85f else 0.6f)
                consumed[k] = true
            }
        }

        // 3) Repetitions (longest n-gram first).
        for (n in 4 downTo 1) {
            var k = 0
            while (k + 2 * n <= tokens.size) {
                if ((k until k + 2 * n).any { consumed[it] }) { k++; continue }
                val first = (0 until n).map { tokens[k + it].norm }
                val second = (0 until n).map { tokens[k + n + it].norm }
                val within = tokens[k + 2 * n - 1].timing.endUs - tokens[k].timing.startUs <= REPEAT_WINDOW_US
                val meaningful = first.all { it.isNotEmpty() } && !(n == 1 && first[0] in EMPHATIC)
                if (first == second && within && meaningful) {
                    cuts += cut(tokens, k, k + n - 1, CutKind.REPETITION, if (n >= 2) 0.8f else 0.6f)
                    for (m in k until k + n) consumed[m] = true
                    k += n
                } else {
                    k++
                }
            }
        }
        return cuts.sortedBy { it.range.startUs }
    }

    private fun isFillerUse(tokens: List<Token>, from: Int, to: Int): Boolean {
        val prev = tokens.getOrNull(from - 1)
        val next = tokens.getOrNull(to + 1)
        val gapBefore = prev?.let { tokens[from].timing.startUs - it.timing.endUs } ?: Long.MAX_VALUE
        val gapAfter = next?.let { it.timing.startUs - tokens[to].timing.endUs } ?: Long.MAX_VALUE
        val pauseBefore = gapBefore >= PAUSE_US || prev?.clauseEnd == true
        val pauseAfter = gapAfter >= PAUSE_US || tokens[to].clauseEnd
        val doubled = next != null && next.norm == tokens[to].norm
        val norm = tokens[from].norm
        return when {
            doubled -> true
            // "you know that…", "I mean what I say" carry meaning.
            norm == "you" || norm == "i" -> pauseAfter && (next == null || next.norm !in FOLLOWED_BY_CONTENT_EN)
            norm == "like" || norm == "مثلا" || norm == "so" -> pauseBefore && pauseAfter
            else -> (pauseBefore && pauseAfter) || (pauseBefore && gapAfter >= PAUSE_US * 2) || (gapBefore >= PAUSE_US * 2 && pauseAfter)
        }
    }

    private fun cut(tokens: List<Token>, from: Int, to: Int, kind: CutKind, confidence: Float): CutSuggestion {
        val start = tokens[from].timing.startUs
        val end = tokens[to].timing.endUs
        val prevEnd = tokens.getOrNull(from - 1)?.timing?.endUs ?: (start - EDGE_PAD_US)
        val nextStart = tokens.getOrNull(to + 1)?.timing?.startUs ?: (end + EDGE_PAD_US)
        val s = maxOf(start - EDGE_PAD_US, (prevEnd + start) / 2, 0)
        val e = minOf(end + EDGE_PAD_US, (end + nextStart) / 2).coerceAtLeast(end)
        val text = (from..to).joinToString(" ") { tokens[it].raw }
        return CutSuggestion(TimeRange(s, e), kind, text, confidence)
    }

    fun tokens(transcript: Transcript): List<Token> = transcript.segments.flatMap { s ->
        val words = s.words.ifEmpty { WhisperParser.estimateWords(s.text, s.startUs, s.endUs) }
        words.flatMap { w ->
            // Providers occasionally return multi-word tokens ("you know"); split evenly.
            val parts = w.text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.size <= 1) {
                listOf(Token(TextNormalizer.word(w.text), w, w.text.trim()))
            } else {
                WhisperParser.estimateWords(w.text, w.startUs, w.endUs).map { Token(TextNormalizer.word(it.text), it, it.text) }
            }
        }
    }.filter { it.raw.isNotEmpty() }.sortedBy { it.timing.startUs }
}
