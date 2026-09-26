package com.ravango.feature.teleprompter.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.ravango.core.common.di.ApplicationScope
import com.ravango.core.common.log.RgLog
import com.ravango.core.data.repository.ScriptRepository
import com.ravango.core.datastore.PreferencesDataSource
import com.ravango.core.model.ProFeature
import com.ravango.core.model.Script
import com.ravango.core.model.TeleprompterSettings
import com.ravango.core.model.service.EntitlementProvider
import com.ravango.core.navigation.TeleprompterRoute
import com.ravango.feature.teleprompter.data.LiveSettingsEditor
import com.ravango.feature.teleprompter.input.VolumeKeyMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface PrompterUiState {
    data object Loading : PrompterUiState
    data object NotFound : PrompterUiState
    data class Ready(
        val script: Script,
        val settings: TeleprompterSettings,
        /** Where the prompter starts (and returns to on stop). */
        val startOffset: Int,
        /** Offset saved from the previous session (0 = none), offered as "resume". */
        val resumeOffset: Int,
        val keepScreenOn: Boolean,
        val volumeKeyMode: VolumeKeyMode,
        val floatingAvailable: Boolean,
    ) : PrompterUiState
}

@HiltViewModel
class PrompterViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val scripts: ScriptRepository,
    private val prefs: PreferencesDataSource,
    private val entitlements: EntitlementProvider,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    val scriptId: String = savedStateHandle.toRoute<TeleprompterRoute>().scriptId

    private val editor = LiveSettingsEditor(viewModelScope, scripts, prefs)
    private val scriptFlow = scripts.observeScript(scriptId)

    private data class StartState(val start: Int, val resume: Int)

    /** Captured once on open so saving progress does not move the start point under the user. */
    private val startState = MutableStateFlow<StartState?>(null)
    private var loaded = false

    val uiState: StateFlow<PrompterUiState> = combine(
        scriptFlow,
        editor.effective(scriptFlow),
        startState,
        prefs.userPreferences,
        combine(prefs.observeString(VolumeKeyMode.PREF_KEY).map(VolumeKeyMode::fromPref), entitlements.entitlements) { a, b -> a to b },
    ) { script, settings, start, userPrefs, (volumeMode, _) ->
        when {
            script == null -> if (loaded) PrompterUiState.NotFound else PrompterUiState.Loading
            start == null -> PrompterUiState.Loading
            else -> PrompterUiState.Ready(
                script = script,
                settings = settings,
                startOffset = start.start.coerceIn(0, script.body.length),
                resumeOffset = start.resume,
                keepScreenOn = userPrefs.keepScreenOnWhilePrompting,
                volumeKeyMode = volumeMode,
                floatingAvailable = entitlements.has(ProFeature.FLOATING_PROMPTER),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PrompterUiState.Loading)

    init {
        viewModelScope.launch {
            val script = scripts.getScript(scriptId)
            loaded = true
            if (script == null) {
                startState.value = StartState(0, 0)
                return@launch
            }
            // Offer "resume" only when the saved point is meaningfully inside the script.
            val resume = script.startCharOffset.takeIf { it in 1 until (script.body.length - 20).coerceAtLeast(1) } ?: 0
            startState.value = StartState(start = 0, resume = resume)
            scripts.markOpened(scriptId)
        }
    }

    fun updateSettings(transform: (TeleprompterSettings) -> TeleprompterSettings) {
        val ready = uiState.value as? PrompterUiState.Ready ?: return
        editor.update(ready.settings, ready.script, transform)
    }

    fun setStartOffset(offset: Int) {
        startState.value = StartState(start = offset.coerceAtLeast(0), resume = 0)
    }

    fun consumeResumeOffer() {
        startState.value = startState.value?.copy(resume = 0)
    }

    /** Persists the reading position so the next session can resume. Survives leaving the screen. */
    fun saveReadingPosition(offset: Int) {
        appScope.launch {
            runCatching { scripts.saveStartOffset(scriptId, offset) }
                .onFailure { RgLog.w(TAG, "saving reading position failed", it) }
        }
    }

    suspend fun currentScript(): Script? = scriptFlow.first()

    private companion object {
        const val TAG = "Prompter"
    }
}
