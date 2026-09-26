package com.ravango.engine.ai.speech

import com.ravango.core.model.WordTiming
import com.ravango.engine.ai.api.Transcript
import com.ravango.engine.ai.api.TranscriptSegment
import com.ravango.engine.ai.json.LlmJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Whisper-compatible `verbose_json` response (OpenAI, the RavanGo gateway, faster-whisper servers…). */
@Serializable
internal data class WhisperVerboseJson(
    val text: String? = null,
    val language: String? = null,
    val duration: Double? = null,
    val segments: List<Segment> = emptyList(),
    val words: List<Word> = emptyList(),
) {
    @Serializable
    data class Segment(val start: Double = 0.0, val end: Double = 0.0, val text: String = "", @SerialName("no_speech_prob") val noSpeechProb: Double? = null)

    @Serializable
    data class Word(val word: String = "", val start: Double = 0.0, val end: Double = 0.0)
}

object WhisperParser {

    /** Parses a verbose_json body into a chunk-relative transcript (µs). Words are attached to their segments. */
    fun parse(body: String): Transcript? {
        val r = runCatching { LlmJson.json.decodeFromString(WhisperVerboseJson.serializer(), body) }.getOrNull() ?: return null
        val words = r.words.filter { it.word.isNotBlank() }.map { WordTiming(it.word.trim(), secToUs(it.start), secToUs(it.end).coerceAtLeast(secToUs(it.start))) }
        val segments = if (r.segments.isNotEmpty()) {
            r.segments.filter { it.text.isNotBlank() && (it.noSpeechProb ?: 0.0) < 0.9 }.map { s ->
                val start = secToUs(s.start)
                val end = secToUs(s.end)
                val inSeg = words.filter { w -> midpoint(w) in start..end }
                TranscriptSegment(start, end, s.text.trim(), inSeg.ifEmpty { estimateWords(s.text.trim(), start, end) })
            }
        } else if (words.isNotEmpty()) {
            groupWords(words)
        } else if (!r.text.isNullOrBlank()) {
            val end = secToUs(r.duration ?: 0.0)
            listOf(TranscriptSegment(0, end, r.text.trim(), estimateWords(r.text.trim(), 0, end)))
        } else {
            emptyList()
        }
        return Transcript(normalizeLanguage(r.language), segments)
    }

    /** Whisper reports full language names ("persian"); the app uses BCP-47 codes. */
    fun normalizeLanguage(language: String?): String? = when (language?.lowercase()?.trim()) {
        null, "" -> null
        "persian", "farsi" -> "fa"
        "english" -> "en"
        "arabic" -> "ar"
        "turkish" -> "tr"
        "german" -> "de"
        "french" -> "fr"
        "spanish" -> "es"
        "russian" -> "ru"
        else -> language.lowercase().trim()
    }

    private fun groupWords(words: List<WordTiming>): List<TranscriptSegment> {
        val out = mutableListOf<TranscriptSegment>()
        var current = mutableListOf<WordTiming>()
        for (w in words) {
            val gap = current.lastOrNull()?.let { w.startUs - it.endUs } ?: 0
            if (current.isNotEmpty() && (gap > 700_000 || current.size >= 18 || endsSentence(current.last().text))) {
                out += TranscriptSegment(current.first().startUs, current.last().endUs, current.joinToString(" ") { it.text }, current)
                current = mutableListOf()
            }
            current += w
        }
        if (current.isNotEmpty()) out += TranscriptSegment(current.first().startUs, current.last().endUs, current.joinToString(" ") { it.text }, current)
        return out
    }

    private fun endsSentence(word: String) = word.endsWith('.') || word.endsWith('?') || word.endsWith('!') || word.endsWith('؟')

    private fun midpoint(w: WordTiming) = (w.startUs + w.endUs) / 2

    internal fun secToUs(s: Double): Long = (s * 1_000_000).toLong()

    /**
     * Distributes a segment's duration over its words proportionally to their length (a good proxy for spoken
     * duration in both Persian and English). Used when a provider gives only segment-level timings.
     */
    fun estimateWords(text: String, startUs: Long, endUs: Long): List<WordTiming> {
        val tokens = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty() || endUs <= startUs) return emptyList()
        val weights = tokens.map { t -> t.count { it.isLetterOrDigit() }.coerceAtLeast(1) + 1.0 }
        val total = weights.sum()
        val span = (endUs - startUs).toDouble()
        var t = startUs.toDouble()
        return tokens.mapIndexed { i, tok ->
            val d = span * weights[i] / total
            val w = WordTiming(tok, t.toLong(), (t + d).toLong())
            t += d
            w
        }
    }
}

/**
 * Merges per-chunk transcripts (chunk-relative times) into one source-relative transcript. Chunks overlap by a few
 * seconds; each chunk "owns" its span minus half the overlap on shared edges and only words/segments whose midpoint
 * falls in the owned span are kept, so boundary words appear exactly once.
 */
object TranscriptMerger {

    data class ChunkResult(val startUs: Long, val durationUs: Long, val transcript: Transcript)

    fun merge(chunks: List<ChunkResult>): Transcript {
        val sorted = chunks.sortedBy { it.startUs }
        val segments = mutableListOf<TranscriptSegment>()
        for ((i, c) in sorted.withIndex()) {
            val prev = sorted.getOrNull(i - 1)
            val next = sorted.getOrNull(i + 1)
            val ownedStart = if (prev == null) Long.MIN_VALUE else (c.startUs + (prev.startUs + prev.durationUs)) / 2
            val ownedEnd = if (next == null) Long.MAX_VALUE else (next.startUs + (c.startUs + c.durationUs)) / 2
            for (s in c.transcript.segments) {
                val shifted = TranscriptSegment(
                    startUs = s.startUs + c.startUs,
                    endUs = s.endUs + c.startUs,
                    text = s.text,
                    words = s.words.map { it.copy(startUs = it.startUs + c.startUs, endUs = it.endUs + c.startUs) },
                )
                if (shifted.words.isNotEmpty()) {
                    val kept = shifted.words.filter { (it.startUs + it.endUs) / 2 in ownedStart until ownedEnd }
                    if (kept.isEmpty()) continue
                    val text = if (kept.size == shifted.words.size) shifted.text else kept.joinToString(" ") { it.text }
                    segments += TranscriptSegment(kept.first().startUs, kept.last().endUs, text, kept)
                } else {
                    val mid = (shifted.startUs + shifted.endUs) / 2
                    if (mid in ownedStart until ownedEnd) segments += shifted
                }
            }
        }
        val language = sorted.mapNotNull { it.transcript.language }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        return Transcript(language, segments.sortedBy { it.startUs })
    }
}
