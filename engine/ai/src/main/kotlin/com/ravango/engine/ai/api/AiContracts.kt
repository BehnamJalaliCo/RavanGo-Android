package com.ravango.engine.ai.api

import com.ravango.core.common.result.ErrorKind
import com.ravango.core.common.result.Outcome
import com.ravango.core.media.dsp.TimeRange
import com.ravango.core.model.AiProviderId
import com.ravango.core.model.EditorDocument
import com.ravango.core.model.SpeechProviderId
import com.ravango.core.model.SubtitleCue
import com.ravango.core.model.SubtitleStyle
import com.ravango.core.model.WordTiming
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/*
 * CONTRACT — AI subsystem public API. Providers are swappable (RavanGo gateway, Anthropic direct, OpenAI-compatible)
 * behind these interfaces. Every cloud call checks user consent and plan credits first.
 */

enum class TextTask {
    GENERATE_SCRIPT, REWRITE, SHORTEN, LENGTHEN, CHANGE_TONE, FIX_GRAMMAR,
    HOOKS, CTA, TITLES, CAPTION, DESCRIPTION, IDEAS,
    TRANSLATE, SUMMARIZE, BULLETS_TO_SCRIPT, FORMAL_TO_CONVERSATIONAL,
}

enum class ContentPlatform { INSTAGRAM, YOUTUBE, YOUTUBE_SHORTS, TIKTOK, LINKEDIN, TELEGRAM, PODCAST, GENERAL }

enum class Tone { FRIENDLY, PROFESSIONAL, ENERGETIC, HUMOROUS, INSPIRATIONAL, CALM, PERSUASIVE, EDUCATIONAL }

data class TextRequest(
    val task: TextTask,
    val input: String,
    /** BCP-47 language of the desired output ("fa", "en"). For TRANSLATE this is the target language. */
    val outputLanguage: String = "fa",
    val tone: Tone? = null,
    val platform: ContentPlatform = ContentPlatform.GENERAL,
    val targetDurationSec: Int? = null,
    val audience: String? = null,
    val extraInstructions: String? = null,
    /** Number of alternatives for list-style tasks (hooks, titles, CTAs, ideas). */
    val variants: Int = 5,
)

sealed interface AiStreamEvent {
    data class Delta(val text: String) : AiStreamEvent
    data class Completed(val text: String, val creditsUsed: Int) : AiStreamEvent
    data class Failed(val kind: ErrorKind, val message: String? = null) : AiStreamEvent
}

data class AiAvailability(
    val configured: Boolean,
    val provider: AiProviderId,
    /** The user must grant consent before content is sent to a cloud provider. */
    val consentRequired: Boolean,
    /** Human-readable reason when not configured (e.g. which service/key is missing). */
    val detail: String? = null,
)

interface AiTextService {
    val availability: StateFlow<AiAvailability>

    /** Streams the model output. Cancel the collection to abort; credits are refunded on failure. */
    fun run(request: TextRequest): Flow<AiStreamEvent>

    /** Splits list-style results (hooks, titles…) into individual items. */
    fun parseList(text: String): List<String>
}

// ---------- Speech ----------

data class TranscriptSegment(
    val startUs: Long,
    val endUs: Long,
    val text: String,
    val words: List<WordTiming> = emptyList(),
)

data class Transcript(
    val language: String?,
    val segments: List<TranscriptSegment>,
) {
    val text: String get() = segments.joinToString(" ") { it.text.trim() }
}

data class SpeechAvailability(val configured: Boolean, val provider: SpeechProviderId, val consentRequired: Boolean, val detail: String? = null)

interface SpeechToTextService {
    val availability: StateFlow<SpeechAvailability>

    /**
     * Transcribes the audio of [uri] within the source range. Times in the result are source-relative (µs).
     * [onProgress] receives 0..1.
     */
    suspend fun transcribe(
        uri: String,
        language: String? = null,
        startUs: Long = 0,
        endUs: Long = Long.MAX_VALUE,
        onProgress: (Float) -> Unit = {},
    ): Outcome<Transcript>
}

/** Builds readable subtitle cues from a transcript (line length, duration and punctuation aware; RTL-safe). */
interface SubtitleBuilder {
    fun build(transcript: Transcript, style: SubtitleStyle, maxCharsPerCue: Int = 42, maxCueDurationUs: Long = 3_500_000): List<SubtitleCue>
    fun toSrt(cues: List<SubtitleCue>): String
    fun parseSrt(srt: String): List<SubtitleCue>
}

// ---------- Video intelligence ----------

enum class CutKind { SILENCE, FILLER_WORD, REPETITION, FALSE_START }

data class CutSuggestion(val range: TimeRange, val kind: CutKind, val text: String? = null, val confidence: Float = 1f)

data class HighlightSuggestion(val range: TimeRange, val score: Float, val title: String, val reason: String)

data class ShortSuggestion(
    val title: String,
    val hook: String,
    val ranges: List<TimeRange>,
    val score: Float,
    val caption: String,
) {
    val durationUs: Long get() = ranges.sumOf { it.durationUs }
}

/** Edits proposed for a document. Applying is up to the editor (user reviews first). */
data class AutoEditPlan(
    /** Source ranges to remove, keyed by main-track clip id. */
    val cuts: Map<String, List<CutSuggestion>>,
    val subtitles: List<SubtitleCue>,
    val highlights: List<HighlightSuggestion>,
    val suggestedTitle: String?,
    val notes: List<String>,
)

interface VideoIntelligence {
    /** On-device: silent ranges in source time. */
    suspend fun detectSilences(uri: String, startUs: Long = 0, endUs: Long = Long.MAX_VALUE): List<TimeRange>

    /** On-device silences + transcript-based filler/repetition detection. */
    suspend fun suggestCuts(uri: String, transcript: Transcript?): List<CutSuggestion>

    /** Loudness/energy analysis combined with an LLM pass over the transcript (when available). */
    suspend fun findHighlights(uri: String, transcript: Transcript?, maxResults: Int = 5): Outcome<List<HighlightSuggestion>>

    /** Proposes self-contained short clips (Reels/Shorts) from a long recording. Requires a transcript. */
    suspend fun suggestShorts(transcript: Transcript, targetDurationSec: Int = 45, maxResults: Int = 3): Outcome<List<ShortSuggestion>>

    /** Full automatic edit proposal for the document's main track. */
    suspend fun planAutoEdit(document: EditorDocument, onProgress: (Float) -> Unit = {}): Outcome<AutoEditPlan>
}

/** On-device offline audio cleanup (noise reduction + voice enhancement + loudness normalization) to a new file. */
interface AudioCleanupService {
    suspend fun cleanup(inputUri: String, output: File, strength: Float = 0.6f, onProgress: (Float) -> Unit = {}): Outcome<File>
}

enum class CapabilityState { AVAILABLE, REQUIRES_SERVICE, UNSUPPORTED }

/**
 * Gaze/eye-contact correction needs a specialised model that is not available on-device across Android devices.
 * This seam exists so a provider (e.g. a server-side gaze-redirection model) can be integrated without UI changes.
 */
interface EyeContactService {
    val state: CapabilityState
    /** Name of the external service required when [state] is REQUIRES_SERVICE. */
    val requiredService: String
    suspend fun correct(inputUri: String, output: File, onProgress: (Float) -> Unit = {}): Outcome<File>
}
