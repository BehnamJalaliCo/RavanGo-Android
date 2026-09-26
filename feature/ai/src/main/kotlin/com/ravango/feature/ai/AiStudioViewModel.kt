package com.ravango.feature.ai

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.ravango.core.common.log.RgLog
import com.ravango.core.common.result.ErrorKind
import com.ravango.core.data.repository.ScriptRepository
import com.ravango.core.datastore.PreferencesDataSource
import com.ravango.core.model.AiProviderId
import com.ravango.core.model.Entitlements
import com.ravango.core.model.newId
import com.ravango.core.model.service.EntitlementProvider
import com.ravango.core.navigation.AiStudioRoute
import com.ravango.engine.ai.api.AiAvailability
import com.ravango.engine.ai.api.AiStreamEvent
import com.ravango.engine.ai.api.AiTextService
import com.ravango.engine.ai.api.CapabilityState
import com.ravango.engine.ai.api.ContentPlatform
import com.ravango.engine.ai.api.EyeContactService
import com.ravango.engine.ai.api.TextRequest
import com.ravango.engine.ai.api.Tone
import com.ravango.engine.ai.api.isConsentRequired
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/** Options the user sets for a tool run. */
data class ToolOptions(
    val platform: ContentPlatform = ContentPlatform.INSTAGRAM,
    val tone: Tone? = Tone.FRIENDLY,
    val audience: String = "",
    val durationSec: Int = 60,
    val useDuration: Boolean = false,
    val outputLanguage: String = "fa",
    val variants: Int = 5,
    val extra: String = "",
)

sealed interface GenerationState {
    data object Idle : GenerationState
    data object Streaming : GenerationState
    data class Done(val creditsUsed: Int) : GenerationState
    /** Stopped by the user; partial text is kept. */
    data object Stopped : GenerationState
    data class Failed(val kind: ErrorKind, val code: String?) : GenerationState
}

data class HistoryItem(
    val id: String = newId(),
    val tool: AiTool,
    val input: String,
    val output: String,
    val variants: List<String>,
    val createdAt: Long = System.currentTimeMillis(),
)

data class AiStudioUiState(
    val availability: AiAvailability = AiAvailability(configured = false, provider = AiProviderId.RAVANGO_GATEWAY, consentRequired = true),
    val entitlements: Entitlements = Entitlements(),
    val eyeContactState: CapabilityState = CapabilityState.REQUIRES_SERVICE,
    val tool: AiTool? = null,
    val input: String = "",
    val sourceScriptId: String? = null,
    val sourceScriptTitle: String? = null,
    val options: ToolOptions = ToolOptions(),
    val generation: GenerationState = GenerationState.Idle,
    val output: String = "",
    val variants: List<String> = emptyList(),
    val selectedVariants: Set<Int> = emptySet(),
    val history: List<HistoryItem> = emptyList(),
    val showConsent: Boolean = false,
    val saving: Boolean = false,
) {
    val isStreaming: Boolean get() = generation == GenerationState.Streaming
    val canGenerate: Boolean get() = tool != null && input.isNotBlank() && !isStreaming && (tool.toneRequired.not() || options.tone != null)
    val hasResult: Boolean get() = output.isNotBlank() && !isStreaming
    val showsCredits: Boolean get() = availability.provider == AiProviderId.RAVANGO_GATEWAY

    /** Text the result actions (copy, share, save) apply to: selected variants, or the whole output. */
    val actionText: String
        get() = if (variants.isNotEmpty()) {
            val chosen = if (selectedVariants.isEmpty()) variants else variants.filterIndexed { i, _ -> i in selectedVariants }
            chosen.joinToString("\n\n")
        } else {
            output.trim()
        }
}

sealed interface AiStudioEvent {
    data class OpenTeleprompter(val scriptId: String) : AiStudioEvent
    data class Saved(val scriptId: String, val replaced: Boolean) : AiStudioEvent
    data object SaveFailed : AiStudioEvent
}

@HiltViewModel
class AiStudioViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val textService: AiTextService,
    private val entitlementProvider: EntitlementProvider,
    private val scripts: ScriptRepository,
    private val preferences: PreferencesDataSource,
    eyeContact: EyeContactService,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<AiStudioRoute>()
    private val local = MutableStateFlow(AiStudioUiState(tool = AiTool.fromArg(route.tool), sourceScriptId = route.scriptId))

    val uiState: StateFlow<AiStudioUiState> = combine(local, textService.availability, entitlementProvider.entitlements) { s, availability, ent ->
        s.copy(availability = availability, entitlements = ent)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    private val _events = Channel<AiStudioEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var generationJob: Job? = null
    /** Generation queued behind the consent dialog. */
    private var pendingAfterConsent = false
    /** (body, scriptId) of the last result saved as a new script. */
    private var lastSaved: Pair<String, String>? = null

    init {
        local.update { it.copy(eyeContactState = eyeContact.state) }
        viewModelScope.launch {
            val prefs = preferences.currentUserPreferences()
            val lang = prefs.language.tag ?: java.util.Locale.getDefault().language
            val tone = Tone.entries.firstOrNull { it.name.equals(prefs.ai.defaultTone, ignoreCase = true) } ?: Tone.FRIENDLY
            local.update { s ->
                s.copy(options = s.options.copy(outputLanguage = if (lang == "en") "en" else "fa", tone = tone))
                    .withToolDefaults(s.tool)
            }
            route.scriptId?.let { id ->
                val script = runCatching { scripts.getScript(id) }.getOrNull()
                if (script != null) local.update { it.copy(sourceScriptTitle = script.title, input = it.input.ifBlank { script.body }) }
            }
        }
    }

    fun openTool(tool: AiTool) {
        stop()
        local.update { s ->
            val keepInput = s.sourceScriptId != null || s.input.isNotBlank()
            s.copy(tool = tool, generation = GenerationState.Idle, output = "", variants = emptyList(), selectedVariants = emptySet(), input = if (keepInput) s.input else "")
                .withToolDefaults(tool)
        }
    }

    fun closeTool() {
        stop()
        local.update { it.copy(tool = null, generation = GenerationState.Idle, output = "", variants = emptyList(), selectedVariants = emptySet()) }
    }

    private fun AiStudioUiState.withToolDefaults(tool: AiTool?): AiStudioUiState {
        tool ?: return this
        val o = options
        val translateTarget = if (tool == AiTool.TRANSLATE) { if (o.outputLanguage == "fa") "en" else o.outputLanguage } else o.outputLanguage
        return copy(
            options = o.copy(
                useDuration = tool.durationMode == AiTool.DurationMode.REQUIRED || (o.useDuration && tool.durationMode != AiTool.DurationMode.NONE),
                variants = o.variants.coerceIn(1, tool.maxVariants).let { if (tool == AiTool.CAPTION && it > 3) 3 else it },
                tone = if (tool.toneRequired && o.tone == null) Tone.PROFESSIONAL else o.tone,
                outputLanguage = translateTarget,
            ),
        )
    }

    fun setInput(text: String) = local.update { it.copy(input = text) }
    fun updateOptions(transform: (ToolOptions) -> ToolOptions) = local.update { it.copy(options = transform(it.options)) }

    fun generate() {
        val s = uiState.value
        val tool = s.tool ?: return
        if (!s.canGenerate) return
        if (s.availability.consentRequired) {
            pendingAfterConsent = true
            local.update { it.copy(showConsent = true) }
            return
        }
        val o = s.options
        val request = TextRequest(
            task = tool.task,
            input = s.input.trim(),
            outputLanguage = o.outputLanguage,
            tone = if (tool.usesTone) o.tone else null,
            platform = if (tool.usesPlatform) o.platform else ContentPlatform.GENERAL,
            targetDurationSec = if (tool.durationMode != AiTool.DurationMode.NONE && o.useDuration) o.durationSec else null,
            audience = o.audience.takeIf { tool.usesAudience && it.isNotBlank() },
            extraInstructions = o.extra.takeIf { it.isNotBlank() },
            variants = if (tool.isList) o.variants else 1,
        )
        generationJob?.cancel()
        local.update { it.copy(generation = GenerationState.Streaming, output = "", variants = emptyList(), selectedVariants = emptySet()) }
        generationJob = viewModelScope.launch {
            try {
                textService.run(request).collect { event ->
                    when (event) {
                        is AiStreamEvent.Delta -> local.update { it.copy(output = it.output + event.text) }
                        is AiStreamEvent.Completed -> {
                            val variants = if (tool.isList) textService.parseList(event.text) else emptyList()
                            local.update {
                                it.copy(
                                    output = event.text,
                                    variants = variants,
                                    generation = GenerationState.Done(event.creditsUsed),
                                    history = (listOf(HistoryItem(tool = tool, input = request.input, output = event.text, variants = variants)) + it.history).take(MAX_HISTORY),
                                )
                            }
                        }
                        is AiStreamEvent.Failed -> {
                            if (event.isConsentRequired) {
                                pendingAfterConsent = true
                                local.update { it.copy(generation = GenerationState.Idle, showConsent = true) }
                            } else {
                                local.update { it.copy(generation = GenerationState.Failed(event.kind, event.message)) }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RgLog.e(TAG, "generation crashed", e)
                local.update { it.copy(generation = GenerationState.Failed(ErrorKind.UNKNOWN, null)) }
            }
        }
    }

    fun stop() {
        val job = generationJob ?: return
        generationJob = null
        job.cancel()
        local.update { if (it.generation == GenerationState.Streaming) it.copy(generation = GenerationState.Stopped) else it }
    }

    fun toggleVariant(index: Int) = local.update {
        it.copy(selectedVariants = if (index in it.selectedVariants) it.selectedVariants - index else it.selectedVariants + index)
    }

    fun restore(item: HistoryItem) {
        stop()
        local.update {
            it.copy(tool = item.tool, input = item.input, output = item.output, variants = item.variants, selectedVariants = emptySet(), generation = GenerationState.Done(0))
        }
    }

    fun grantConsent() {
        viewModelScope.launch {
            preferences.updateUserPreferences { p -> p.copy(ai = p.ai.copy(cloudProcessingConsent = true)) }
            local.update { it.copy(showConsent = false) }
            if (pendingAfterConsent) {
                pendingAfterConsent = false
                // Wait for the availability flow to observe the new preference before retrying.
                textService.availability.first { !it.consentRequired }
                generate()
            }
        }
    }

    fun dismissConsent() {
        pendingAfterConsent = false
        local.update { it.copy(showConsent = false) }
    }

    /** Saves the result as a new script; optionally opens it in the teleprompter. */
    fun saveAsNewScript(defaultTitle: String, openPrompter: Boolean) {
        val s = uiState.value
        val body = s.actionText.ifBlank { return }
        viewModelScope.launch {
            local.update { it.copy(saving = true) }
            try {
                // Re-use the script saved from this exact result instead of creating duplicates.
                val existing = lastSaved?.takeIf { it.first == body }?.second?.takeIf { scripts.getScript(it) != null }
                val id = existing ?: scripts.create(title = titleFrom(body).ifBlank { defaultTitle }, body = body).id
                lastSaved = body to id
                _events.send(if (openPrompter) AiStudioEvent.OpenTeleprompter(id) else AiStudioEvent.Saved(id, replaced = false))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RgLog.e(TAG, "save failed", e)
                _events.send(AiStudioEvent.SaveFailed)
            } finally {
                local.update { it.copy(saving = false) }
            }
        }
    }

    /** Replaces the body of the script the studio was opened with. */
    fun replaceSourceScript() {
        val s = uiState.value
        val id = s.sourceScriptId ?: return
        val body = s.actionText.ifBlank { return }
        viewModelScope.launch {
            local.update { it.copy(saving = true) }
            try {
                val script = scripts.getScript(id)
                if (script == null) {
                    _events.send(AiStudioEvent.SaveFailed)
                } else {
                    scripts.save(script.copy(body = body))
                    _events.send(AiStudioEvent.Saved(id, replaced = true))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RgLog.e(TAG, "replace failed", e)
                _events.send(AiStudioEvent.SaveFailed)
            } finally {
                local.update { it.copy(saving = false) }
            }
        }
    }

    /** Title from the first section header or the first line, markup stripped. */
    private fun titleFrom(body: String): String {
        val section = Regex("(?m)^##\\s*(.+)$").find(body)?.groupValues?.get(1)
        val line = section ?: body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        val clean = line.replace("==", "").replace("**", "").replace(Regex("\\[\\[.*?]]|\\[(pause|مکث)]"), "").trim()
        return if (clean.length > 60) clean.take(57).trimEnd() + "…" else clean
    }

    override fun onCleared() {
        generationJob?.cancel()
    }

    private companion object {
        const val TAG = "AiStudio"
        const val MAX_HISTORY = 20
    }
}
