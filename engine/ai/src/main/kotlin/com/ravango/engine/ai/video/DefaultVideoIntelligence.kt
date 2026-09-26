package com.ravango.engine.ai.video

import com.ravango.core.common.log.RgLog
import com.ravango.core.common.result.ErrorKind
import com.ravango.core.common.result.Outcome
import com.ravango.core.common.result.outcomeOf
import com.ravango.core.media.PcmDecoder
import com.ravango.core.media.dsp.SilenceDetector
import com.ravango.core.media.dsp.SilenceOptions
import com.ravango.core.media.dsp.TimeRange
import com.ravango.core.model.AiOperation
import com.ravango.core.model.EditorDocument
import com.ravango.core.model.MediaKind
import com.ravango.core.model.SubtitleCue
import com.ravango.engine.ai.api.AiErrors
import com.ravango.engine.ai.api.AutoEditNotes
import com.ravango.engine.ai.api.AutoEditPlan
import com.ravango.engine.ai.api.CutKind
import com.ravango.engine.ai.api.CutSuggestion
import com.ravango.engine.ai.api.HighlightReasons
import com.ravango.engine.ai.api.HighlightSuggestion
import com.ravango.engine.ai.api.ShortSuggestion
import com.ravango.engine.ai.api.SpeechToTextService
import com.ravango.engine.ai.api.SubtitleBuilder
import com.ravango.engine.ai.api.TextRequest
import com.ravango.engine.ai.api.TextTask
import com.ravango.engine.ai.api.Transcript
import com.ravango.engine.ai.api.VideoIntelligence
import com.ravango.engine.ai.llm.EffortHint
import com.ravango.engine.ai.llm.LlmMessage
import com.ravango.engine.ai.llm.LlmRequest
import com.ravango.engine.ai.llm.LlmRole
import com.ravango.engine.ai.llm.LlmTaskRunner
import com.ravango.engine.ai.prompts.PromptLibrary
import com.ravango.engine.ai.text.ListParser
import kotlinx.coroutines.ensureActive
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Video intelligence: on-device silence/loudness analysis (core:media [SilenceDetector]), transcript heuristics
 * ([FillerDetector]) and LLM passes (highlight scoring, shorts, title) that run only when the user consented and a
 * provider is configured. All LLM outputs are strict JSON validated against the transcript before use.
 */
@Singleton
class DefaultVideoIntelligence @Inject constructor(
    pcmDecoder: PcmDecoder,
    private val speech: SpeechToTextService,
    private val subtitleBuilder: SubtitleBuilder,
    private val llm: LlmTaskRunner,
) : VideoIntelligence {

    private val silenceDetector = SilenceDetector(pcmDecoder)

    override suspend fun detectSilences(uri: String, startUs: Long, endUs: Long): List<TimeRange> =
        silenceDetector.detect(uri, SilenceOptions(), startUs, endUs)

    override suspend fun suggestCuts(uri: String, transcript: Transcript?): List<CutSuggestion> {
        val silences = detectSilences(uri).map { CutSuggestion(it, CutKind.SILENCE, confidence = 0.9f) }
        val speechCuts = transcript?.let { FillerDetector.detect(it) }.orEmpty()
        return mergeCuts(silences, speechCuts)
    }

    override suspend fun findHighlights(uri: String, transcript: Transcript?, maxResults: Int): Outcome<List<HighlightSuggestion>> = outcomeOf {
        val envelope = silenceDetector.loudnessEnvelope(uri, ENVELOPE_MS)
        val units = transcript?.let { TranscriptUnits.split(it) }.orEmpty()
        val candidates = if (units.isNotEmpty()) HighlightScorer.fromUnits(units, envelope, ENVELOPE_MS) else HighlightScorer.fromEnvelope(envelope, ENVELOPE_MS)
        if (units.isNotEmpty() && llm.isReady()) applyLlmScores(candidates, units, transcript?.language)
        HighlightScorer.select(candidates, maxResults.coerceAtLeast(1)).map { it.toSuggestion() }
    }

    override suspend fun suggestShorts(transcript: Transcript, targetDurationSec: Int, maxResults: Int): Outcome<List<ShortSuggestion>> {
        val units = TranscriptUnits.split(transcript)
        if (units.isEmpty()) return Outcome.Failure(ErrorKind.INVALID_INPUT, AiErrors.NO_SPEECH)
        val target = targetDurationSec.coerceIn(10, 180)
        val count = maxResults.coerceIn(1, 10)
        val system = """
            You are a senior short-form video editor. From the timestamped transcript of a long recording, propose up to $count self-contained vertical clips (Reels/Shorts/TikTok).
            Rules:
            - Each clip must make sense on its own without the rest of the video, and open with a strong hook in its first seconds. You may put a later, punchier sentence first as the hook, followed by the context.
            - Total duration of each clip must not exceed $target seconds. Use whole transcript units only; refer to them by their [index].
            - Prefer complete thoughts, surprising insights, strong opinions, practical tips, emotional moments and quotable lines. Avoid clips that start mid-sentence or rely on earlier context.
            - Clips must not overlap each other in content.
            - "title", "hook" (the on-screen hook text, ≤ 12 words) and "caption" (a short post caption with 2–4 hashtags) are written in the transcript's language${languageHint(transcript.language)}.
            - "score" is 0..1: how likely the clip is to perform well.
            Respond with JSON only, no prose and no code fences, exactly in this shape:
            {"shorts":[{"title":"…","hook":"…","caption":"…","score":0.8,"units":[[startIndex,endIndex],[startIndex,endIndex]]}]}
            "units" lists inclusive index ranges in playback order.
        """.trimIndent()
        val request = LlmRequest(
            system = system,
            messages = listOf(LlmMessage(LlmRole.USER, "Transcript units:\n" + TranscriptUnits.describe(units.take(MAX_UNITS)))),
            maxTokens = 16_000,
            effort = EffortHint.MEDIUM,
        )
        return when (val r = llm.complete(request, AiOperation.VIDEO_ANALYSIS)) {
            is Outcome.Failure -> r
            is Outcome.Success -> {
                val parsed = VideoLlmParsing.parseShorts(r.value, units, maxDurationUs = target * 1_000_000L)
                when {
                    parsed == null -> Outcome.Failure(ErrorKind.UNKNOWN, AiErrors.INVALID_OUTPUT)
                    else -> Outcome.Success(parsed.sortedByDescending { it.score }.take(count))
                }
            }
        }
    }

    override suspend fun planAutoEdit(document: EditorDocument, onProgress: (Float) -> Unit): Outcome<AutoEditPlan> = outcomeOf {
        val clips = document.mainTrack
        val notes = mutableListOf<String>()
        val cuts = mutableMapOf<String, List<CutSuggestion>>()
        val subtitles = mutableListOf<SubtitleCue>()
        val timelineUnits = mutableListOf<TranscriptUnit>()
        val candidates = mutableListOf<HighlightScorer.Candidate>()
        var language: String? = null
        val speechAvailability = speech.availability.value
        val canTranscribe = speechAvailability.configured && !speechAvailability.consentRequired
        if (!speechAvailability.configured) notes += AutoEditNotes.TRANSCRIPTION_UNAVAILABLE
        else if (speechAvailability.consentRequired) notes += AutoEditNotes.CONSENT_REQUIRED
        val totalUs = clips.sumOf { it.sourceDurationUs }.coerceAtLeast(1)
        var doneUs = 0L

        for (clip in clips) {
            coroutineContext.ensureActive()
            val clipStart = document.clipStartUs(clip.id)
            val share = clip.sourceDurationUs.toFloat() / totalUs * CLIPS_SHARE
            val base = doneUs.toFloat() / totalUs * CLIPS_SHARE
            doneUs += clip.sourceDurationUs
            if (clip.source.kind == MediaKind.IMAGE || !clip.source.hasAudio) {
                onProgress(base + share)
                continue
            }
            val uri = clip.source.uri
            val trimEnd = clip.trimStartUs + clip.sourceDurationUs
            val mapper = TimelineMapper(clip, clipStart)

            // 1) Transcript (source time within the trim window).
            var transcript: Transcript? = null
            if (canTranscribe && !clip.muted) {
                when (val t = speech.transcribe(uri, null, clip.trimStartUs, trimEnd) { p -> onProgress(base + share * 0.7f * p) }) {
                    is Outcome.Success -> transcript = t.value
                    is Outcome.Failure -> {
                        if (t.kind == ErrorKind.CANCELLED) throw CancellationException()
                        notes += AutoEditNotes.forClip(if (t.message == AiErrors.NO_SPEECH) AutoEditNotes.NO_SPEECH else AutoEditNotes.TRANSCRIPTION_FAILED, clip.id)
                        RgLog.w(TAG, "transcription failed for clip: ${t.kind} ${t.message}")
                    }
                }
            }
            if (language == null) language = transcript?.language

            // 2) Cuts (source time) — silences within the trim window + transcript fillers.
            val silences = try {
                detectSilences(uri, clip.trimStartUs, trimEnd).map { CutSuggestion(it, CutKind.SILENCE, confidence = 0.9f) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RgLog.w(TAG, "silence detection failed", e)
                notes += AutoEditNotes.forClip(AutoEditNotes.SILENCE_DETECTION_FAILED, clip.id)
                emptyList()
            }
            val speechCuts = transcript?.let { FillerDetector.detect(it) }.orEmpty()
            val clipCuts = mergeCuts(silences, speechCuts).mapNotNull { c ->
                val s = c.range.startUs.coerceAtLeast(clip.trimStartUs)
                val e = c.range.endUs.coerceAtMost(trimEnd)
                if (e - s > 0) c.copy(range = TimeRange(s, e)) else null
            }
            if (clipCuts.isNotEmpty()) cuts[clip.id] = clipCuts
            onProgress(base + share * 0.8f)

            // 3) Subtitles in timeline time.
            if (transcript != null) {
                if (clip.reversed) {
                    notes += AutoEditNotes.forClip(AutoEditNotes.REVERSED_CLIP_SKIPPED, clip.id)
                } else {
                    val cues = subtitleBuilder.build(transcript, document.subtitles.style)
                    subtitles += cues.mapNotNull { mapper.toTimeline(it) }
                }
            }

            // 4) Highlight candidates, mapped to the timeline.
            val envelope = runCatching { silenceDetector.loudnessEnvelope(uri, ENVELOPE_MS) }.getOrElse {
                if (it is CancellationException) throw it
                FloatArray(0)
            }
            if (!clip.reversed) {
                val units = transcript?.let { TranscriptUnits.split(it) }.orEmpty()
                val offset = timelineUnits.size
                val local = if (units.isNotEmpty()) HighlightScorer.fromUnits(units, envelope, ENVELOPE_MS) else trimmedEnvelopeCandidates(envelope, clip.trimStartUs, trimEnd)
                for (c in local) {
                    val range = mapper.toTimeline(c.range) ?: continue
                    candidates += c.copy(
                        range = range,
                        firstUnit = if (c.firstUnit >= 0) c.firstUnit + offset else -1,
                        lastUnit = if (c.lastUnit >= 0) c.lastUnit + offset else -1,
                    )
                }
                units.forEach { u ->
                    val r = mapper.toTimeline(TimeRange(u.startUs, u.endUs)) ?: return@forEach
                    timelineUnits += u.copy(index = timelineUnits.size, startUs = r.startUs, endUs = r.endUs)
                }
            }
            onProgress(base + share)
        }

        // 5) Document-level highlights (+ one LLM pass across all clips) and a title.
        var suggestedTitle: String? = null
        if (timelineUnits.isNotEmpty() && llm.isReady()) {
            applyLlmScores(candidates, timelineUnits, language)
            onProgress(CLIPS_SHARE + 0.07f)
            suggestedTitle = suggestTitle(timelineUnits, language)
        } else if (candidates.isNotEmpty()) {
            notes += AutoEditNotes.HIGHLIGHTS_ON_DEVICE_ONLY
        }
        val highlights = HighlightScorer.select(candidates, MAX_HIGHLIGHTS).map { it.toSuggestion() }.sortedBy { it.range.startUs }
        if (subtitles.isNotEmpty() || highlights.isNotEmpty()) notes += AutoEditNotes.TIMES_BEFORE_CUTS
        onProgress(1f)
        AutoEditPlan(
            cuts = cuts,
            subtitles = subtitles.sortedBy { it.startUs },
            highlights = highlights,
            suggestedTitle = suggestedTitle,
            notes = notes.distinct(),
        )
    }

    private fun trimmedEnvelopeCandidates(envelope: FloatArray, trimStartUs: Long, trimEndUs: Long): List<HighlightScorer.Candidate> =
        HighlightScorer.fromEnvelope(envelope, ENVELOPE_MS).filter { it.range.startUs >= trimStartUs && it.range.endUs <= trimEndUs }

    /** Scores transcript units with the LLM and folds the scores into [candidates] (best unit per window). */
    private suspend fun applyLlmScores(candidates: List<HighlightScorer.Candidate>, units: List<TranscriptUnit>, language: String?) {
        val system = """
            You are a video editor finding the most engaging and quotable moments of a recording.
            Rate transcript units (each has an [index]) for how engaging, surprising, emotional, useful or quotable they are as a stand-alone moment. Return only the best ${MAX_LLM_HIGHLIGHTS} units.
            "title" is a short label (≤ 6 words) and "reason" one short sentence, both written in the transcript's language${languageHint(language)}.
            Respond with JSON only, no prose and no code fences:
            {"highlights":[{"index":12,"score":0.9,"title":"…","reason":"…"}]}
            "score" is between 0 and 1.
        """.trimIndent()
        val request = LlmRequest(
            system = system,
            messages = listOf(LlmMessage(LlmRole.USER, "Transcript units:\n" + TranscriptUnits.describe(units.take(MAX_UNITS)))),
            maxTokens = 8_000,
            effort = EffortHint.LOW,
        )
        val result = llm.complete(request, AiOperation.VIDEO_ANALYSIS)
        val scores = (result as? Outcome.Success)?.value?.let { VideoLlmParsing.parseHighlights(it, units.size) }
        if (scores == null) {
            RgLog.w(TAG, "LLM highlight pass skipped: ${(result as? Outcome.Failure)?.message ?: "unparseable output"}")
            return
        }
        val byIndex = scores.associateBy { it.index }
        for (c in candidates) {
            if (c.firstUnit < 0) continue
            val best = (c.firstUnit..c.lastUnit).mapNotNull { byIndex[it] }.maxByOrNull { it.score }
            c.llm = best?.score?.toFloat() ?: 0f
            if (best != null) {
                c.llmTitle = best.title.takeIf { it.isNotBlank() }
                c.llmReason = best.reason.takeIf { it.isNotBlank() }
            }
        }
    }

    private suspend fun suggestTitle(units: List<TranscriptUnit>, language: String?): String? {
        val text = units.joinToString(" ") { it.text }.take(6_000)
        val spec = PromptLibrary.build(TextRequest(TextTask.TITLES, text, outputLanguage = language ?: "fa", variants = 1))
        val request = LlmRequest(spec.system, listOf(LlmMessage(LlmRole.USER, spec.user)), spec.maxTokens, spec.effort)
        val r = llm.complete(request, spec.operation)
        return (r as? Outcome.Success)?.value?.let { ListParser.parse(it).firstOrNull() }
    }

    private fun HighlightScorer.Candidate.toSuggestion(): HighlightSuggestion = HighlightSuggestion(
        range = range,
        score = score.coerceIn(0f, 1f),
        title = llmTitle ?: text?.let { firstWords(it, 7) } ?: "",
        reason = llmReason ?: when {
            energy >= pace && energy > 0f -> HighlightReasons.LOUDNESS
            pace > 0f -> HighlightReasons.PACE
            else -> HighlightReasons.ENERGY_AND_PACE
        },
    )

    private fun firstWords(text: String, n: Int): String {
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        return if (words.size <= n) words.joinToString(" ") else words.take(n).joinToString(" ") + "…"
    }

    private fun languageHint(language: String?): String = when (language?.substringBefore('-')) {
        "fa" -> " (Persian — fluent, natural Persian with correct zero-width non-joiners)"
        "en" -> " (English)"
        null -> ""
        else -> " (${PromptLibrary.languageName(language)})"
    }

    companion object {
        private const val TAG = "VideoAI"
        private const val ENVELOPE_MS = 100
        private const val MAX_UNITS = 1_500
        private const val MAX_HIGHLIGHTS = 5
        private const val MAX_LLM_HIGHLIGHTS = 12
        private const val CLIPS_SHARE = 0.85f

        /** Silences and speech cuts combined; speech cuts fully inside a silence are redundant. */
        fun mergeCuts(silences: List<CutSuggestion>, speechCuts: List<CutSuggestion>): List<CutSuggestion> {
            val filtered = speechCuts.filter { c -> silences.none { s -> c.range.startUs >= s.range.startUs && c.range.endUs <= s.range.endUs } }
            return (silences + filtered).sortedBy { it.range.startUs }
        }
    }
}
