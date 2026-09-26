package com.ravango.engine.ai.subtitle

import com.ravango.core.model.SubtitleCue
import com.ravango.core.model.SubtitleStyle
import com.ravango.core.model.WordTiming
import com.ravango.engine.ai.api.SubtitleBuilder
import com.ravango.engine.ai.api.Transcript
import com.ravango.engine.ai.speech.WhisperParser
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Readable cues from word-timed transcripts.
 *
 * Rules: never split a word; a cue ends at sentence punctuation (`.` `!` `?` `؟` `…`), preferably at clause
 * punctuation (`,` `،` `;` `؛` `:`) once reasonably full, at pauses longer than [PAUSE_BREAK_US], at segment
 * boundaries, and before exceeding the character or duration limit (backing up to the last clause break in the
 * second half of the cue when there is one). Cues last at least [MIN_CUE_US] when the next cue leaves room.
 * Word timings are kept on every cue for karaoke / word-by-word rendering.
 *
 * SRT and RTL: cue text is stored without bidi controls. [toSrt] prefixes U+200F (RLM) only to cues that contain
 * right-to-left letters but *begin* with a left-to-right word or a number (e.g. «iPhone جدید…»); players that pick the
 * paragraph direction from the first strong character would otherwise lay the whole Persian line out left-to-right.
 * [parseSrt] strips RLM/LRM/ALM marks again.
 */
@Singleton
class DefaultSubtitleBuilder @Inject constructor() : SubtitleBuilder {

    override fun build(transcript: Transcript, style: SubtitleStyle, maxCharsPerCue: Int, maxCueDurationUs: Long): List<SubtitleCue> {
        // Larger text fits fewer characters on screen; the defaults assume the default 26sp.
        val maxChars = (maxCharsPerCue * (DEFAULT_SIZE_SP / style.sizeSp.coerceAtLeast(8f))).toInt().coerceIn(MIN_CHARS, maxCharsPerCue.coerceAtLeast(MIN_CHARS))
        val maxDur = maxCueDurationUs.coerceAtLeast(MIN_CUE_US)

        data class W(val timing: WordTiming, val segmentStart: Boolean)

        val words = buildList {
            for (s in transcript.segments) {
                val ws = s.words.ifEmpty { WhisperParser.estimateWords(s.text, s.startUs, s.endUs) }
                ws.filter { it.text.isNotBlank() }.forEachIndexed { i, w -> add(W(w.copy(text = w.text.trim()), i == 0)) }
            }
        }.sortedBy { it.timing.startUs }
        if (words.isEmpty()) return emptyList()

        val groups = mutableListOf<List<WordTiming>>()
        var current = mutableListOf<WordTiming>()

        fun length(ws: List<WordTiming>) = ws.sumOf { it.text.length } + (ws.size - 1).coerceAtLeast(0)

        fun flush() {
            if (current.isNotEmpty()) groups += current
            current = mutableListOf()
        }

        for (w in words) {
            val t = w.timing
            if (current.isNotEmpty()) {
                val last = current.last()
                val gap = t.startUs - last.endUs
                val wouldLen = length(current) + 1 + t.text.length
                val wouldDur = t.endUs - current.first().startUs
                when {
                    gap > PAUSE_BREAK_US -> flush()
                    w.segmentStart && length(current) >= maxChars / 3 -> flush()
                    wouldLen > maxChars || wouldDur > maxDur -> {
                        // Back up to a clause break in the second half so lines end naturally.
                        val breakAt = current.indices.lastOrNull { i -> i >= current.size / 2 && i < current.size - 1 && isClauseEnd(current[i].text) }
                        if (breakAt != null) {
                            val rest = current.subList(breakAt + 1, current.size).toMutableList()
                            current = current.subList(0, breakAt + 1).toMutableList()
                            flush()
                            current = rest
                            if (length(current) + 1 + t.text.length > maxChars || t.endUs - current.first().startUs > maxDur) flush()
                        } else {
                            flush()
                        }
                    }
                }
            }
            current += t
            val len = length(current)
            when {
                isSentenceEnd(t.text) && (current.size >= 2 || len >= maxChars / 3) -> flush()
                isClauseEnd(t.text) && len >= maxChars * 0.6 -> flush()
            }
        }
        flush()

        // Timing pass: min duration without overlapping the next cue.
        val cues = mutableListOf<SubtitleCue>()
        for ((i, g) in groups.withIndex()) {
            val start = g.first().startUs
            val nextStart = groups.getOrNull(i + 1)?.first()?.startUs ?: Long.MAX_VALUE
            var end = maxOf(g.last().endUs, start + MIN_CUE_US)
            if (end > nextStart) end = maxOf(g.last().endUs, nextStart)
            cues += SubtitleCue(startUs = start, endUs = end, text = g.joinToString(" ") { it.text }, words = g)
        }
        return cues
    }

    override fun toSrt(cues: List<SubtitleCue>): String = buildString {
        cues.sortedBy { it.startUs }.forEachIndexed { i, cue ->
            append(i + 1).append('\n')
            append(timecode(cue.startUs)).append(" --> ").append(timecode(cue.endUs)).append('\n')
            val lines = cue.text.replace("\r", "").split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            for (line in lines) append(bidiSafe(line)).append('\n')
            append('\n')
        }
    }

    override fun parseSrt(srt: String): List<SubtitleCue> {
        val normalized = srt.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        val blocks = normalized.split(Regex("\n\\s*\n"))
        val cues = mutableListOf<SubtitleCue>()
        for (block in blocks) {
            val lines = block.lines().map { it.trimEnd() }.dropWhile { it.isBlank() }
            val timeIndex = lines.indexOfFirst { TIME_LINE.containsMatchIn(it) }
            if (timeIndex < 0 || timeIndex > 1) continue
            val m = TIME_LINE.find(lines[timeIndex]) ?: continue
            val start = parseTime(m.groupValues[1]) ?: continue
            val end = parseTime(m.groupValues[2]) ?: continue
            val text = lines.drop(timeIndex + 1)
                .map { stripMarkup(it).trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n")
            if (text.isEmpty() || end < start) continue
            cues += SubtitleCue(startUs = start, endUs = end, text = text)
        }
        return cues.sortedBy { it.startUs }
    }

    companion object {
        const val MIN_CUE_US = 700_000L
        const val PAUSE_BREAK_US = 800_000L
        private const val MIN_CHARS = 16
        private const val DEFAULT_SIZE_SP = 26f
        private const val RLM = '‏'

        private val TIME_LINE = Regex("""(\d{1,2}:\d{2}:\d{2}[,.]\d{1,3})\s*-->\s*(\d{1,2}:\d{2}:\d{2}[,.]\d{1,3})""")
        private val TAGS = Regex("""<[^>]+>|\{\\[^}]*}""")
        private val BIDI_MARKS = Regex("[‎‏؜‪-‮⁦-⁩]")

        private val SENTENCE_END = charArrayOf('.', '!', '?', '؟', '…', '۔')
        private val CLAUSE_END = charArrayOf(',', '،', ';', '؛', ':')
        private val TRAILING = charArrayOf('"', '\'', '»', '”', ')', ']', '«')

        fun isSentenceEnd(word: String): Boolean = word.trimEnd(*TRAILING).lastOrNull()?.let { it in SENTENCE_END } == true
        fun isClauseEnd(word: String): Boolean = word.trimEnd(*TRAILING).lastOrNull()?.let { it in CLAUSE_END || it in SENTENCE_END } == true

        fun timecode(us: Long): String {
            val ms = us.coerceAtLeast(0) / 1000
            return String.format(Locale.US, "%02d:%02d:%02d,%03d", ms / 3_600_000, (ms / 60_000) % 60, (ms / 1000) % 60, ms % 1000)
        }

        fun parseTime(s: String): Long? {
            val parts = s.replace('.', ',').split(':', ',')
            if (parts.size != 4) return null
            val h = parts[0].toLongOrNull() ?: return null
            val m = parts[1].toLongOrNull() ?: return null
            val sec = parts[2].toLongOrNull() ?: return null
            val msText = parts[3].padEnd(3, '0').take(3)
            val ms = msText.toLongOrNull() ?: return null
            return ((h * 3600 + m * 60 + sec) * 1000 + ms) * 1000
        }

        private fun stripMarkup(line: String): String = line.replace(TAGS, "").replace(BIDI_MARKS, "")

        /** Adds RLM when a line containing RTL letters starts with an LTR word or digits. */
        fun bidiSafe(line: String): String {
            val hasRtl = line.any { isRtl(it) }
            if (!hasRtl) return line
            val firstStrongOrDigit = line.firstOrNull { isRtl(it) || isLtr(it) || it.isDigit() } ?: return line
            return if (isRtl(firstStrongOrDigit)) line else RLM + line
        }

        private fun isRtl(c: Char): Boolean = when (Character.getDirectionality(c)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> true
            else -> false
        }

        private fun isLtr(c: Char): Boolean = Character.getDirectionality(c) == Character.DIRECTIONALITY_LEFT_TO_RIGHT
    }
}
