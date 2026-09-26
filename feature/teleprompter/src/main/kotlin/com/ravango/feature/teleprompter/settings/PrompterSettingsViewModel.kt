package com.ravango.feature.teleprompter.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.ravango.core.common.di.ApplicationScope
import com.ravango.core.common.log.RgLog
import com.ravango.core.data.repository.PresetRepository
import com.ravango.core.data.repository.ScriptRepository
import com.ravango.core.datastore.PreferencesDataSource
import com.ravango.core.model.Script
import com.ravango.core.model.TeleprompterPreset
import com.ravango.core.model.TeleprompterSettings
import com.ravango.core.navigation.TeleprompterSettingsRoute
import com.ravango.feature.teleprompter.input.VolumeKeyMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PrompterSettingsUiState(
    val loading: Boolean = true,
    val settings: TeleprompterSettings = TeleprompterSettings(),
    /** Non-null when the screen was opened for a specific script. */
    val scriptTitle: String? = null,
    val scriptBody: String? = null,
    /** True: edits apply to this script only; false: edits change the defaults for all scripts. */
    val scriptOnly: Boolean = false,
    val presets: List<TeleprompterPreset> = emptyList(),
    val volumeKeyMode: VolumeKeyMode = VolumeKeyMode.SPEED,
)

/** What the settings screen can ask for; implemented by [PrompterSettingsViewModel]. */
interface PrompterSettingsActions {
    fun update(transform: (TeleprompterSettings) -> TeleprompterSettings)
    fun setScriptOnly(only: Boolean)
    fun applyPreset(preset: TeleprompterPreset)
    fun resetToDefaults()
    fun savePreset(name: String)
    fun deletePreset(preset: TeleprompterPreset)
    fun setVolumeKeyMode(mode: VolumeKeyMode)
}

@HiltViewModel
class PrompterSettingsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val scripts: ScriptRepository,
    private val presets: PresetRepository,
    private val prefs: PreferencesDataSource,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel(), PrompterSettingsActions {

    private val scriptId: String? = savedStateHandle.toRoute<TeleprompterSettingsRoute>().scriptId

    private val draft = MutableStateFlow<TeleprompterSettings?>(null)
    private val scriptOnly = MutableStateFlow(false)
    private val script = MutableStateFlow<Script?>(null)
    private var persistJob: Job? = null

    val uiState: StateFlow<PrompterSettingsUiState> = combine(
        draft,
        scriptOnly,
        script,
        presets.observePrompterPresets().catch { e ->
            RgLog.w(TAG, "presets unavailable", e)
            emit(emptyList())
        },
        prefs.observeString(VolumeKeyMode.PREF_KEY).map(VolumeKeyMode::fromPref),
    ) { settings, only, s, presetList, volumeMode ->
        PrompterSettingsUiState(
            loading = settings == null,
            settings = settings ?: TeleprompterSettings(),
            scriptTitle = s?.title,
            scriptBody = s?.body,
            scriptOnly = only,
            presets = presetList.sortedWith(compareByDescending<TeleprompterPreset> { it.builtIn }.thenBy { it.name }),
            volumeKeyMode = volumeMode,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PrompterSettingsUiState())

    init {
        viewModelScope.launch {
            val s = scriptId?.let { scripts.getScript(it) }
            script.value = s
            val defaults = prefs.prompterDefaults.first()
            scriptOnly.value = s?.prompterSettings != null
            draft.value = s?.prompterSettings ?: defaults
        }
    }

    override fun update(transform: (TeleprompterSettings) -> TeleprompterSettings) {
        val current = draft.value ?: return
        val next = transform(current)
        if (next == current) return
        draft.value = next
        schedulePersist()
    }

    private fun schedulePersist() {
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            delay(250)
            persistNow()
        }
    }

    private suspend fun persistNow() {
        val settings = draft.value ?: return
        val s = script.value
        if (scriptOnly.value && s != null) {
            scripts.savePrompterSettings(s.id, settings)
        } else {
            prefs.updatePrompterDefaults { settings }
        }
    }

    /** Switches between "this script only" (stores an override) and "defaults for all scripts". */
    override fun setScriptOnly(only: Boolean) {
        val s = script.value ?: return
        if (only == scriptOnly.value) return
        persistJob?.cancel()
        scriptOnly.value = only
        viewModelScope.launch {
            if (only) {
                // Start the override from what the user sees now.
                scripts.savePrompterSettings(s.id, draft.value ?: TeleprompterSettings())
            } else {
                scripts.savePrompterSettings(s.id, null)
                draft.value = prefs.prompterDefaults.first()
            }
        }
    }

    override fun applyPreset(preset: TeleprompterPreset) = update { preset.settings }

    override fun resetToDefaults() = update { TeleprompterSettings() }

    override fun savePreset(name: String) {
        val settings = draft.value ?: return
        viewModelScope.launch { presets.savePrompterPreset(name.trim(), settings) }
    }

    override fun deletePreset(preset: TeleprompterPreset) {
        if (preset.builtIn) return
        viewModelScope.launch { presets.deletePrompterPreset(preset.id) }
    }

    override fun setVolumeKeyMode(mode: VolumeKeyMode) {
        viewModelScope.launch { prefs.putString(VolumeKeyMode.PREF_KEY, mode.name) }
    }

    override fun onCleared() {
        // Make sure the last edit is not lost when leaving within the debounce window.
        if (persistJob?.isActive == true) {
            persistJob?.cancel()
            appScope.launch { runCatching { persistNow() } }
        }
        super.onCleared()
    }

    private companion object {
        const val TAG = "PrompterSettings"
    }
}
