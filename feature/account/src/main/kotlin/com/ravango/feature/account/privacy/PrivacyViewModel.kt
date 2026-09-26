package com.ravango.feature.account.privacy

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ravango.core.common.AppConfig
import com.ravango.core.common.di.IoDispatcher
import com.ravango.core.common.log.RgLog
import com.ravango.core.data.repository.ProjectRepository
import com.ravango.core.data.repository.ScriptRepository
import com.ravango.core.datastore.PreferencesDataSource
import com.ravango.core.model.MediaAsset
import com.ravango.core.model.Project
import com.ravango.core.model.Script
import com.ravango.core.model.ScriptFolder
import com.ravango.core.model.UserPreferences
import com.ravango.core.model.service.AuthState
import com.ravango.platform.auth.AuthError
import com.ravango.platform.auth.AuthRepository
import com.ravango.platform.auth.AuthResult
import com.ravango.platform.cloud.LocalDataManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject

/** The personal data export (portable JSON; media files themselves stay in the gallery/app storage). */
@Serializable
internal data class DataExport(
    val format: String = "ravango-export",
    val version: Int = 1,
    val exportedAt: Long,
    val appVersion: String,
    val account: ExportedAccount?,
    val preferences: UserPreferences,
    val folders: List<ScriptFolder>,
    val scripts: List<Script>,
    val projects: List<Project>,
    val mediaAssets: List<MediaAsset>,
)

@Serializable
internal data class ExportedAccount(val id: String, val email: String?, val phone: String?, val displayName: String?)

data class PrivacyUiState(
    val analyticsConsent: Boolean = false,
    val crashReportsConsent: Boolean = false,
    val signedIn: Boolean = false,
    val busy: Boolean = false,
    val privacyUrl: String = "",
)

sealed interface PrivacyEvent {
    data object Exported : PrivacyEvent
    data object ExportFailed : PrivacyEvent
    data object LocalDataDeleted : PrivacyEvent
    data object AccountDeleted : PrivacyEvent
    data class Error(val error: AuthError) : PrivacyEvent
}

@HiltViewModel
class PrivacyViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: PreferencesDataSource,
    private val scripts: ScriptRepository,
    private val projects: ProjectRepository,
    private val localData: LocalDataManager,
    private val auth: AuthRepository,
    private val appConfig: AppConfig,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    private val busy = MutableStateFlow(false)
    private val _events = Channel<PrivacyEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val state: StateFlow<PrivacyUiState> = combine(preferences.userPreferences, auth.authState, busy) { p, a, b ->
        PrivacyUiState(p.analyticsConsent, p.crashReportsConsent, a is AuthState.SignedIn, b, appConfig.privacyPolicyUrl)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PrivacyUiState(privacyUrl = appConfig.privacyPolicyUrl))

    fun setAnalytics(enabled: Boolean) = viewModelScope.launch { preferences.updateUserPreferences { it.copy(analyticsConsent = enabled) } }
    fun setCrashReports(enabled: Boolean) = viewModelScope.launch { preferences.updateUserPreferences { it.copy(crashReportsConsent = enabled) } }

    fun exportTo(uri: Uri) = work {
        try {
            val user = (auth.authState.value as? AuthState.SignedIn)?.user
            val export = DataExport(
                exportedAt = System.currentTimeMillis(),
                appVersion = appConfig.versionName,
                account = user?.let { ExportedAccount(it.id, it.email, it.phone, it.displayName) },
                preferences = preferences.currentUserPreferences(),
                folders = scripts.observeFolders().first(),
                scripts = scripts.observeScripts().first(),
                projects = projects.observeProjects().first(),
                mediaAssets = projects.observeAllAssets().first(),
            )
            val text = json.encodeToString(DataExport.serializer(), export)
            withContext(io) {
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: throw IOException("cannot open $uri")
            }
            _events.send(PrivacyEvent.Exported)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RgLog.e("Privacy", "export failed", e)
            _events.send(PrivacyEvent.ExportFailed)
        }
    }

    fun deleteLocalData() = work {
        localData.wipeLocalData()
        _events.send(PrivacyEvent.LocalDataDeleted)
    }

    fun deleteAccount(wipeLocal: Boolean) = work {
        when (val r = auth.deleteAccount(wipeLocal)) {
            is AuthResult.Success -> _events.send(PrivacyEvent.AccountDeleted)
            is AuthResult.Failure -> _events.send(PrivacyEvent.Error(r.error))
        }
    }

    private fun work(block: suspend () -> Unit) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            try {
                block()
            } finally {
                busy.value = false
            }
        }
    }

    private companion object {
        val json = Json { prettyPrint = true; encodeDefaults = true }
    }
}
