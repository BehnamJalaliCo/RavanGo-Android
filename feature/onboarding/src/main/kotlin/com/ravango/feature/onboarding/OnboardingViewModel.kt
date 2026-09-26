package com.ravango.feature.onboarding

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ravango.core.common.log.RgLog
import com.ravango.core.datastore.PreferencesDataSource
import com.ravango.core.model.AppLanguage
import com.ravango.core.model.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the user creates; stored as keys that Home uses to order suggested templates. */
enum class CreatorFocus(val key: String) {
    REELS("reels"),
    YOUTUBE("youtube"),
    PODCAST("podcast"),
    BUSINESS("business"),
    EDUCATION("education"),
    VLOG("vlog"),
}

const val ONBOARDING_PAGE_COUNT = 4

/** Preference key read by Home (string contract: comma-separated [CreatorFocus.key] values). */
const val ONBOARDING_FOCUS_KEY = "onboarding_focus"

data class OnboardingUiState(
    val page: Int = 0,
    val language: AppLanguage = AppLanguage.PERSIAN,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val analyticsConsent: Boolean = false,
    val crashReportsConsent: Boolean = false,
    val focus: Set<CreatorFocus> = emptySet(),
    val finishing: Boolean = false,
)

sealed interface OnboardingEvent {
    data object Finished : OnboardingEvent
}

/**
 * Onboarding state lives here (and in [SavedStateHandle]) because choosing a language recreates the activity.
 * Language and theme apply immediately; consents and focus are committed only when the user finishes.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val preferences: PreferencesDataSource,
) : ViewModel() {

    private val page = savedStateHandle.getStateFlow(KEY_PAGE, 0)
    private val analytics = savedStateHandle.getStateFlow(KEY_ANALYTICS, false)
    private val crash = savedStateHandle.getStateFlow(KEY_CRASH, false)
    private val focus = savedStateHandle.getStateFlow(KEY_FOCUS, emptyList<String>())
    private val finishing = MutableStateFlow(false)

    private val events = Channel<OnboardingEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    private val choices = combine(page, analytics, crash, focus, finishing) { p, a, c, f, busy ->
        OnboardingUiState(
            page = p,
            analyticsConsent = a,
            crashReportsConsent = c,
            focus = f.mapNotNull { key -> CreatorFocus.entries.firstOrNull { it.key == key } }.toSet(),
            finishing = busy,
        )
    }

    val uiState: StateFlow<OnboardingUiState> = combine(choices, preferences.userPreferences) { s, prefs ->
        // A fresh install has PERSIAN as default; SYSTEM (never set by onboarding) is shown as Persian preselected.
        s.copy(language = if (prefs.language == AppLanguage.SYSTEM) AppLanguage.PERSIAN else prefs.language, themeMode = prefs.themeMode)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OnboardingUiState(page = page.value))

    fun setPage(value: Int) {
        savedStateHandle[KEY_PAGE] = value.coerceIn(0, ONBOARDING_PAGE_COUNT - 1)
    }

    /** Applies immediately; MainActivity switches the per-app locale (and may recreate). */
    fun selectLanguage(language: AppLanguage) {
        viewModelScope.launch { preferences.updateUserPreferences { it.copy(language = language) } }
    }

    /** Applies immediately so the user previews the choice. */
    fun selectTheme(mode: ThemeMode) {
        viewModelScope.launch { preferences.updateUserPreferences { it.copy(themeMode = mode) } }
    }

    fun setAnalyticsConsent(value: Boolean) { savedStateHandle[KEY_ANALYTICS] = value }
    fun setCrashReportsConsent(value: Boolean) { savedStateHandle[KEY_CRASH] = value }

    fun toggleFocus(item: CreatorFocus) {
        val current = focus.value
        savedStateHandle[KEY_FOCUS] = ArrayList(if (item.key in current) current - item.key else current + item.key)
    }

    /** Commits choices and marks onboarding as done. Skipping uses the privacy-preserving defaults (all off). */
    fun finish() {
        if (finishing.value) return
        finishing.value = true
        val state = uiState.value
        viewModelScope.launch {
            runCatching {
                // Keep the user's selection order stable: ordered by the enum, not by tap order.
                val focusValue = CreatorFocus.entries.filter { it in state.focus }.joinToString(",") { it.key }.ifEmpty { null }
                preferences.putString(ONBOARDING_FOCUS_KEY, focusValue)
                preferences.updateUserPreferences {
                    it.copy(
                        onboardingCompleted = true,
                        analyticsConsent = state.analyticsConsent,
                        crashReportsConsent = state.crashReportsConsent,
                        updatedAt = System.currentTimeMillis(),
                    )
                }
            }.onFailure { RgLog.e(TAG, "Could not save onboarding choices", it) }
            // Even if storage failed, never trap the user in onboarding.
            events.send(OnboardingEvent.Finished)
        }
    }

    private companion object {
        const val TAG = "Onboarding"
        const val KEY_PAGE = "page"
        const val KEY_ANALYTICS = "analytics"
        const val KEY_CRASH = "crash"
        const val KEY_FOCUS = "focus"
    }
}
