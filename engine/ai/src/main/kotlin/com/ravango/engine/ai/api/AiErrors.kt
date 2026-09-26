package com.ravango.engine.ai.api

import com.ravango.core.common.result.ErrorKind

/**
 * Why an AI provider cannot be used right now. UIs map these to localized copy and a fix-it action
 * (open Settings, sign in, …). Reported through [AiAvailability.issue] / [SpeechAvailability.issue].
 */
enum class AiSetupIssue {
    /** This build has no `RAVANGO_AI_GATEWAY_URL`; only bring-your-own-key providers can work. */
    GATEWAY_NOT_CONFIGURED,

    /** The RavanGo gateway needs a signed-in account (the user's session token authorizes and meters requests). */
    SIGN_IN_REQUIRED,

    /** The selected BYOK provider has no API key stored. */
    API_KEY_MISSING,

    /** OPENAI_COMPATIBLE is selected but base URL or model is missing. */
    CUSTOM_ENDPOINT_MISSING,

    /** On-device speech recognition is not available on this device / Android version. */
    ON_DEVICE_UNSUPPORTED,
}

/**
 * Stable codes carried in [AiStreamEvent.Failed.message] and [com.ravango.core.common.result.Outcome.Failure.message]
 * so the UI can show a specific explanation. Anything else in `message` is diagnostic text only.
 *
 * **Consent contract:** when the user has not granted `cloudProcessingConsent`, [AiTextService.run] emits
 * `Failed(ErrorKind.PERMISSION, AiErrors.CONSENT_REQUIRED)` (and speech/video calls return the same failure)
 * without contacting any server. The UI should show its consent dialog and retry after the user agrees.
 */
object AiErrors {
    const val CONSENT_REQUIRED = "ai:consent_required"
    const val SIGN_IN_REQUIRED = "ai:sign_in_required"
    const val API_KEY_MISSING = "ai:api_key_missing"
    const val INVALID_API_KEY = "ai:invalid_api_key"
    const val GATEWAY_NOT_CONFIGURED = "ai:gateway_not_configured"
    const val CUSTOM_ENDPOINT_MISSING = "ai:custom_endpoint_missing"
    const val NO_CREDITS = "ai:no_credits"
    const val RATE_LIMITED = "ai:rate_limited"
    const val OVERLOADED = "ai:overloaded"
    const val REFUSED = "ai:refused"
    const val EMPTY_RESPONSE = "ai:empty_response"
    const val INVALID_OUTPUT = "ai:invalid_output"
    const val NO_AUDIO = "ai:no_audio"
    const val NO_SPEECH = "ai:no_speech"
    const val ON_DEVICE_UNSUPPORTED = "ai:on_device_unsupported"
    const val SERVICE_NOT_AVAILABLE = "ai:service_not_available"

    fun isConsentRequired(kind: ErrorKind, message: String?): Boolean = kind == ErrorKind.PERMISSION && message == CONSENT_REQUIRED
}

/** True when this failure means "ask the user for AI cloud-processing consent, then retry". */
val AiStreamEvent.Failed.isConsentRequired: Boolean get() = AiErrors.isConsentRequired(kind, message)

/**
 * [HighlightSuggestion.reason] values produced by on-device scoring. When an LLM pass ran, `reason` (and `title`)
 * are instead free text written by the model in the transcript's language.
 */
object HighlightReasons {
    const val LOUDNESS = "highlight:loudness"
    const val PACE = "highlight:pace"
    const val ENERGY_AND_PACE = "highlight:energy_pace"
}

/**
 * [AutoEditPlan.notes] entries are stable codes, optionally followed by `|<clipId>`, for the UI to localize:
 * e.g. `"autoedit:transcription_unavailable|<clipId>"`.
 */
object AutoEditNotes {
    const val TRANSCRIPTION_UNAVAILABLE = "autoedit:transcription_unavailable"
    const val TRANSCRIPTION_FAILED = "autoedit:transcription_failed"
    const val CONSENT_REQUIRED = "autoedit:consent_required"
    const val NO_AUDIO = "autoedit:no_audio"
    const val REVERSED_CLIP_SKIPPED = "autoedit:reversed_clip_no_subtitles"
    const val SILENCE_DETECTION_FAILED = "autoedit:silence_detection_failed"
    const val HIGHLIGHTS_ON_DEVICE_ONLY = "autoedit:highlights_on_device_only"
    const val NO_SPEECH = "autoedit:no_speech"

    /** Subtitles and highlights are in timeline time *before* any suggested cut is applied. */
    const val TIMES_BEFORE_CUTS = "autoedit:times_before_cuts"

    fun forClip(code: String, clipId: String) = "$code|$clipId"
}
