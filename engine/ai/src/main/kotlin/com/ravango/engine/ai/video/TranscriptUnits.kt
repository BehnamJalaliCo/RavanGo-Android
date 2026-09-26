package com.ravango.engine.ai.video

import com.ravango.core.model.WordTiming
import com.ravango.engine.ai.api.Transcript
import com.ravango.engine.ai.speech.WhisperParser
import com.ravango.engine.ai.subtitle.DefaultSubtitleBuilder
import java.util.Locale

/**
 * Sentence-level units of a transcript (split at sentence punctuation, pauses and a max length), each starting and
 * ending on a word boundary. LLM passes refer to units by index, which keeps every proposed range snapped to words.
 */
data class TranscriptUnit(val index: Int, val startUs: Long, val endUs: Long, val text: String, val words: List<WordTiming>)

object TranscriptUnits {

    fun split(transcript: Transcript, maxUnitUs: Long = 12_000_000, pauseUs: Long = 650_000): List<TranscriptUnit> {
        val words = transcript.segments.flatMap { s -> s.words.ifEmpty { WhisperParser.estimateWords(s.text, s.startUs, s.endUs) } }
            .filter { it.text.isNotBlank() }
            .sortedBy { it.startUs }
        val units = mutableListOf<TranscriptUnit>()
        var current = mutableListOf<WordTiming>()
        fun flush() {
            if (current.isEmpty()) return
            units += TranscriptUnit(units.size, current.first().startUs, current.last().endUs, current.joinToString(" ") { it.text.trim() }, current)
            current = mutableListOf()
        }
        for (w in words) {
            val last = current.lastOrNull()
            if (last != null && (w.startUs - last.endUs > pauseUs || w.endUs - current.first().startUs > maxUnitUs)) flush()
            current += w
            if (DefaultSubtitleBuilder.isSentenceEnd(w.text) && current.size >= 3) flush()
        }
        flush()
        return units
    }

    /** "[12] 01:05–01:09 text" lines for LLM prompts. */
    fun describe(units: List<TranscriptUnit>): String = units.joinToString("\n") { u ->
        "[${u.index}] ${clock(u.startUs)}–${clock(u.endUs)} ${u.text}"
    }

    private fun clock(us: Long): String {
        val s = us / 1_000_000
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
    }
}
