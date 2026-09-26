package com.ravango.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ravango.core.common.crash.CrashGuard
import com.ravango.core.datastore.PreferencesDataSource
import com.ravango.core.model.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

sealed interface MainUiState {
    data object Loading : MainUiState
    data class Ready(val preferences: UserPreferences) : MainUiState
}

@HiltViewModel
class MainViewModel @Inject constructor(
    preferences: PreferencesDataSource,
    crashGuard: CrashGuard,
) : ViewModel() {

    val uiState: StateFlow<MainUiState> = preferences.userPreferences
        .map<UserPreferences, MainUiState> { MainUiState.Ready(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState.Loading)

    /** Non-null once if the previous process crashed; the UI shows a reassurance message. */
    val previousCrash: String? = crashGuard.consumePreviousCrash()
}
