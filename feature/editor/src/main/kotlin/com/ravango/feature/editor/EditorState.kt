package com.ravango.feature.editor

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.ravango.core.common.result.ErrorKind
import com.ravango.core.model.EditorDocument
import com.ravango.core.model.Entitlements
import com.ravango.core.model.ProFeature
import com.ravango.core.media.dsp.TimeRange
import com.ravango.engine.ai.api.AutoEditPlan
import com.ravango.engine.ai.api.CapabilityState
import com.ravango.engine.ai.api.HighlightSuggestion
import com.ravango.engine.ai.api.ShortSuggestion
import com.ravango.engine.editor.export.EditorError

/** What is selected on the timeline / preview. */
sealed interface Selection {
    data object None : Selection
    data class Clip(val id: String) : Selection
    data class Overlay(val id: String) : Selection
    data class Audio(val id: String) : Selection
    data class Cue(val id: String) : Selection

    val itemId: String? get() = when (this) {
        None -> null
        is Clip -> id
        is Overlay -> id
        is Audio -> id
        is Cue -> id
    }
}

/** Bottom tool rail entries. */
enum class EditorTool { EDIT, CANVAS, FILTERS, ADJUST, TEXT, STICKERS, OVERLAY, LOGO, MUSIC, VOICEOVER, AUDIO, CAPTIONS, AI }

/** A long-running operation shown with progress over the editor. */
@Immutable
data class BusyTask(@StringRes val label: Int, val progress: Float? = null, val cancellable: Boolean = false)

/** One-shot message for the snackbar. */
@Immutable
data class UiMessage(
    @StringRes val text: Int? = null,
    val errorKind: ErrorKind? = null,
    val editorError: EditorError? = null,
    val detail: String? = null,
    val id: Long = System.nanoTime(),
)

/** Voice-over recording state. */
@Immutable
data class VoiceOverState(val recording: Boolean = false, val startUs: Long = 0, val elapsedUs: Long = 0, val level: Float = 0f)

/** AI panel results (each shown for review before applying). */
@Immutable
data class AiResults(
    val silences: Map<String, List<TimeRange>>? = null,
    val highlights: List<HighlightSuggestion>? = null,
    val highlightClipId: String? = null,
    val shorts: List<ShortSuggestion>? = null,
    val shortsClipId: String? = null,
    val createdShorts: Set<Int> = emptySet(),
    val plan: AutoEditPlan? = null,
) {
    val silenceTotalUs: Long get() = silences?.values?.flatten()?.sumOf { it.durationUs } ?: 0L
}

/** Availability of cloud services, surfaced honestly in the UI. */
@Immutable
data class ServiceStatus(
    val speechConfigured: Boolean = false,
    val speechConsentRequired: Boolean = false,
    val speechDetail: String? = null,
    val aiConfigured: Boolean = false,
    val aiDetail: String? = null,
    val eyeContact: CapabilityState = CapabilityState.UNSUPPORTED,
    val eyeContactService: String = "",
)

@Immutable
data class EditorUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val projectId: String = "",
    val projectTitle: String = "",
    val document: EditorDocument = EditorDocument(),
    val selection: Selection = Selection.None,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val tool: EditorTool? = null,
    val entitlements: Entitlements = Entitlements(),
    val busy: BusyTask? = null,
    val message: UiMessage? = null,
    val reversing: Set<String> = emptySet(),
    val voiceOver: VoiceOverState = VoiceOverState(),
    val ai: AiResults = AiResults(),
    val services: ServiceStatus = ServiceStatus(),
    /** Normalized (w, h) of overlay items on the canvas, for selection handles. */
    val overlaySizes: Map<String, Pair<Float, Float>> = emptyMap(),
    val previewBuilding: Boolean = false,
    val previewError: Boolean = false,
) {
    fun has(feature: ProFeature): Boolean = entitlements.has(feature)
    val isEmpty: Boolean get() = !loading && document.mainTrack.isEmpty()
}
