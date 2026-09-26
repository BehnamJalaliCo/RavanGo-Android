package com.ravango.feature.beauty

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ravango.core.common.log.RgLog
import com.ravango.core.data.repository.PresetRepository
import com.ravango.core.model.BeautyPreset
import com.ravango.core.model.BeautyState
import com.ravango.core.model.Entitlements
import com.ravango.core.model.MakeupFeature
import com.ravango.core.model.ProFeature
import com.ravango.core.model.service.EntitlementProvider
import com.ravango.engine.beauty.BeautyEngine
import com.ravango.engine.beauty.BeautyStatus
import com.ravango.engine.beauty.EyeColorSetting
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BeautyPresets(
    val builtIn: List<BeautyPreset> = emptyList(),
    val mine: List<BeautyPreset> = emptyList(),
    val loaded: Boolean = false,
)

data class BeautyUiState(
    val state: BeautyState = BeautyState(),
    val status: BeautyStatus = BeautyStatus(),
    val entitlements: Entitlements = Entitlements(),
    val tab: BeautyTab = BeautyTab.SKIN,
    val selected: BeautyItem? = null,
    val presets: BeautyPresets = BeautyPresets(),
    val activePresetId: String? = null,
    val comparing: Boolean = false,
    val eyeColor: EyeColorSetting = EyeColorSetting(),
) {
    /** Current value of any item, including the eye colour which lives outside [BeautyState]. */
    fun valueOf(item: BeautyItem): Int =
        if (item == BeautyItem.EyeColor) eyeColor.intensity else BeautyCatalog.value(state, item)

    /** The curated look the current makeup matches exactly, if any. */
    val activeLook: MakeupLook? get() = MakeupLook.activeIn(state)

    val hasMakeup: Boolean get() = MakeupLook.hasMakeup(state)

    fun isLocked(item: BeautyItem): Boolean = BeautyCatalog.requiredPro(item)?.let { !entitlements.has(it) } ?: false

    fun isPresetLocked(preset: BeautyPreset): Boolean = BeautyCatalog.missingEntitlement(preset.state, entitlements::has) != null

    /** Whether a new custom preset can be saved without upgrading. */
    val canSaveMorePresets: Boolean
        get() = entitlements.has(ProFeature.UNLIMITED_PRESETS) || presets.mine.size < entitlements.maxSavedPresets

    val unlimitedPresets: Boolean get() = entitlements.has(ProFeature.UNLIMITED_PRESETS)
}

sealed interface BeautyEvent {
    data class RequirePro(val feature: ProFeature) : BeautyEvent
    data class PresetSaved(val name: String) : BeautyEvent
    data class PresetApplied(val preset: BeautyPreset) : BeautyEvent
    data object PresetDeleted : BeautyEvent
    data object PresetRenamed : BeautyEvent
    data class LookApplied(val look: MakeupLook) : BeautyEvent
    data object SaveFailed : BeautyEvent
}

private data class LocalUi(
    val tab: BeautyTab = BeautyTab.SKIN,
    val selectedByTab: Map<BeautyTab, BeautyItem> = emptyMap(),
    val activePresetId: String? = null,
    val comparing: Boolean = false,
)

/**
 * Drives the beauty panel and the presets screen. The engine is the single source of truth for the live look;
 * this view model only translates user intents (with Pro gating) into engine state and preset operations.
 */
@HiltViewModel
class BeautyViewModel @Inject constructor(
    private val engine: BeautyEngine,
    private val presetRepository: PresetRepository,
    private val entitlementProvider: EntitlementProvider,
) : ViewModel() {

    private val local = MutableStateFlow(LocalUi())
    private val events = Channel<BeautyEvent>(Channel.BUFFERED)
    val eventFlow: Flow<BeautyEvent> = events.receiveAsFlow()
    private var transition: Job? = null

    private val presets: Flow<BeautyPresets> = presetRepository.observeBeautyPresets()
        .map { list ->
            val live = list.filter { it.deletedAt == null }
            BeautyPresets(
                builtIn = live.filter { it.builtIn },
                mine = live.filter { !it.builtIn }.sortedByDescending { it.updatedAt },
                loaded = true,
            )
        }
        .catch { e ->
            RgLog.w(TAG, "Could not load beauty presets", e)
            emit(BeautyPresets(loaded = true))
        }

    private val engineState = combine(engine.state, engine.eyeColor) { state, eye -> state to eye }

    val uiState: StateFlow<BeautyUiState> = combine(
        engineState,
        engine.status,
        entitlementProvider.entitlements,
        presets,
        local,
    ) { (state, eyeColor), status, entitlements, presets, ui ->
        val selected = ui.selectedByTab[ui.tab] ?: BeautyCatalog.items(ui.tab).firstOrNull()
        BeautyUiState(
            eyeColor = eyeColor,
            state = state,
            status = status,
            entitlements = entitlements,
            tab = ui.tab,
            selected = selected,
            presets = presets,
            activePresetId = ui.activePresetId,
            comparing = ui.comparing,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        BeautyUiState(
            state = engine.state.value,
            status = engine.status.value,
            entitlements = entitlementProvider.entitlements.value,
            eyeColor = engine.eyeColor.value,
        ),
    )

    fun selectTab(tab: BeautyTab) = local.update { it.copy(tab = tab) }

    fun select(item: BeautyItem) = local.update { it.copy(selectedByTab = it.selectedByTab + (it.tab to item)) }

    fun setEnabled(enabled: Boolean) {
        cancelTransition()
        engine.setState(engine.state.value.copy(enabled = enabled))
    }

    /** Applies a slider value. Returns false (and applies nothing) when the item needs Pro. */
    fun setValue(item: BeautyItem, value: Int): Boolean {
        if (isLocked(item)) return false
        if (item == BeautyItem.EyeColor) {
            engine.setEyeColor(engine.eyeColor.value.copy(intensity = value.coerceIn(0, 100)))
            if (value > 0 && !engine.state.value.enabled) engine.setState(engine.state.value.copy(enabled = true))
            return true
        }
        cancelTransition()
        val current = engine.state.value
        if (BeautyCatalog.value(current, item) == value && current.enabled) return true
        engine.setState(BeautyCatalog.withValue(current, item, value).copy(enabled = true))
        local.update { it.copy(activePresetId = null) }
        return true
    }

    fun setMakeupColor(feature: MakeupFeature, color: Long) {
        val item = BeautyItem.Makeup(feature)
        if (isLocked(item)) {
            requirePro(item)
            return
        }
        cancelTransition()
        engine.setState(BeautyCatalog.withColor(engine.state.value, feature, color).copy(enabled = true))
        local.update { it.copy(activePresetId = null) }
    }

    fun setEyeColor(color: Long) {
        if (isLocked(BeautyItem.EyeColor)) {
            requirePro(BeautyItem.EyeColor)
            return
        }
        val current = engine.eyeColor.value
        engine.setEyeColor(EyeColorSetting(intensity = if (current.intensity == 0) DEFAULT_EYE_COLOR_INTENSITY else current.intensity, color = color))
        if (!engine.state.value.enabled) engine.setState(engine.state.value.copy(enabled = true))
    }

    /** One-tap curated makeup look; tapping the active look again removes it. */
    fun applyLook(look: MakeupLook) {
        if (!entitlementProvider.has(ProFeature.MAKEUP)) {
            events.trySend(BeautyEvent.RequirePro(ProFeature.MAKEUP))
            return
        }
        val current = engine.state.value
        if (look.matches(current)) {
            clearMakeup()
            return
        }
        animateTo(look.applyTo(current))
        local.update { it.copy(activePresetId = null) }
        events.trySend(BeautyEvent.LookApplied(look))
    }

    fun clearMakeup() {
        animateTo(MakeupLook.cleared(engine.state.value))
        local.update { it.copy(activePresetId = null) }
    }

    fun reset(item: BeautyItem) {
        if (item == BeautyItem.EyeColor) {
            engine.setEyeColor(engine.eyeColor.value.copy(intensity = 0))
            return
        }
        cancelTransition()
        engine.setState(BeautyCatalog.withValue(engine.state.value, item, BeautyCatalog.neutral(item)))
        local.update { it.copy(activePresetId = null) }
    }

    /** Restores the default natural look (free features only). */
    fun resetAll() {
        animateTo(BeautyState())
        if (engine.eyeColor.value.active) engine.setEyeColor(engine.eyeColor.value.copy(intensity = 0))
        local.update { it.copy(activePresetId = null) }
    }

    fun requirePro(item: BeautyItem) {
        BeautyCatalog.requiredPro(item)?.let { events.trySend(BeautyEvent.RequirePro(it)) }
    }

    fun setComparing(showOriginal: Boolean) {
        engine.setCompareMode(showOriginal)
        local.update { it.copy(comparing = showOriginal) }
    }

    fun applyPreset(preset: BeautyPreset) {
        val missing = BeautyCatalog.missingEntitlement(preset.state, entitlementProvider::has)
        if (missing != null) {
            events.trySend(BeautyEvent.RequirePro(missing))
            return
        }
        animateTo(preset.state.copy(enabled = true))
        local.update { it.copy(activePresetId = preset.id) }
        events.trySend(BeautyEvent.PresetApplied(preset))
    }

    /** Saves the current look as a new custom preset, respecting the plan's preset limit. */
    fun saveCurrentAsPreset(name: String) = createPreset(name, engine.state.value)

    fun duplicatePreset(preset: BeautyPreset, name: String) = createPreset(name, preset.state)

    fun renamePreset(preset: BeautyPreset, name: String) {
        val clean = name.trim()
        if (preset.builtIn || clean.isEmpty()) return
        viewModelScope.launch {
            try {
                presetRepository.saveBeautyPreset(clean, preset.state, id = preset.id)
                events.send(BeautyEvent.PresetRenamed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RgLog.w(TAG, "Rename failed", e)
                events.send(BeautyEvent.SaveFailed)
            }
        }
    }

    fun deletePreset(preset: BeautyPreset) {
        if (preset.builtIn) return
        viewModelScope.launch {
            try {
                presetRepository.deleteBeautyPreset(preset.id)
                if (local.value.activePresetId == preset.id) local.update { it.copy(activePresetId = null) }
                events.send(BeautyEvent.PresetDeleted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RgLog.w(TAG, "Delete failed", e)
                events.send(BeautyEvent.SaveFailed)
            }
        }
    }

    private fun createPreset(name: String, state: BeautyState) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            try {
                val entitlements = entitlementProvider.entitlements.value
                if (!entitlements.has(ProFeature.UNLIMITED_PRESETS) &&
                    presetRepository.customBeautyPresetCount() >= entitlements.maxSavedPresets
                ) {
                    events.send(BeautyEvent.RequirePro(ProFeature.UNLIMITED_PRESETS))
                    return@launch
                }
                val saved = presetRepository.saveBeautyPreset(clean, state.copy(enabled = true))
                local.update { it.copy(activePresetId = saved.id) }
                events.send(BeautyEvent.PresetSaved(saved.name))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RgLog.w(TAG, "Save preset failed", e)
                events.send(BeautyEvent.SaveFailed)
            }
        }
    }

    private fun isLocked(item: BeautyItem): Boolean =
        BeautyCatalog.requiredPro(item)?.let { !entitlementProvider.has(it) } ?: false

    /** Smoothly interpolates every value toward [target] (~320 ms, ease-out) so the preview never jumps. */
    private fun animateTo(target: BeautyState) {
        cancelTransition()
        val from = engine.state.value
        transition = viewModelScope.launch {
            val steps = TRANSITION_STEPS
            for (i in 1..steps) {
                val t = i / steps.toFloat()
                val eased = 1f - (1f - t) * (1f - t) * (1f - t)
                engine.setState(BeautyCatalog.lerp(from, target, eased))
                delay(TRANSITION_FRAME_MS)
            }
            engine.setState(target)
        }
    }

    private fun cancelTransition() {
        transition?.cancel()
        transition = null
    }

    override fun onCleared() {
        if (local.value.comparing) engine.setCompareMode(false)
        super.onCleared()
    }

    private companion object {
        const val TAG = "BeautyVM"
        const val TRANSITION_STEPS = 20
        const val TRANSITION_FRAME_MS = 16L
        const val DEFAULT_EYE_COLOR_INTENSITY = 50
    }
}
