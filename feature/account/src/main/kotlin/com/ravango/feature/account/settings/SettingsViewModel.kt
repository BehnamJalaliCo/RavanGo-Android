package com.ravango.feature.account.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ravango.core.common.AppConfig
import com.ravango.core.data.repository.ProjectRepository
import com.ravango.core.datastore.PreferencesDataSource
import com.ravango.core.model.AiProviderId
import com.ravango.core.model.AppLanguage
import com.ravango.core.model.Entitlements
import com.ravango.core.model.ExportSettings
import com.ravango.core.model.ProFeature
import com.ravango.core.model.SpeechProviderId
import com.ravango.core.model.ThemeMode
import com.ravango.core.model.UserPreferences
import com.ravango.core.model.VideoCodec
import com.ravango.core.model.service.EntitlementProvider
import com.ravango.engine.ai.api.AiCredentialStore
import com.ravango.platform.cloud.LocalDataManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Export presets offered in settings; Pro ones are gated by the plan. */
enum class ExportQuality(val shortSide: Int, val fps: Int, val feature: ProFeature?) {
    HD_720(720, 30, null),
    FHD_1080(1080, 30, null),
    FHD_1080_60(1080, 60, ProFeature.EXPORT_60FPS),
    UHD_4K(2160, 30, ProFeature.EXPORT_4K),
    UHD_4K_60(2160, 60, ProFeature.EXPORT_4K),
    ;

    companion object {
        fun of(settings: ExportSettings): ExportQuality =
            entries.firstOrNull { it.shortSide == settings.resolutionShortSide && it.fps == settings.frameRate } ?: FHD_1080
    }
}

data class KeyStatus(val anthropic: Boolean = false, val openAi: Boolean = false, val speech: Boolean = false)

data class SettingsUiState(
    val prefs: UserPreferences = UserPreferences(),
    val entitlements: Entitlements = Entitlements(),
    val keys: KeyStatus = KeyStatus(),
    val aiGatewayConfigured: Boolean = false,
    val mediaBytes: Long = 0,
    val cacheBytes: Long = 0,
    val clearingCache: Boolean = false,
)

sealed interface SettingsEvent {
    data class RequirePro(val feature: ProFeature) : SettingsEvent
    data class CacheCleared(val bytes: Long) : SettingsEvent
    data object KeySaved : SettingsEvent
    data object KeyRemoved : SettingsEvent
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: PreferencesDataSource,
    private val entitlementProvider: EntitlementProvider,
    private val credentials: AiCredentialStore,
    private val localData: LocalDataManager,
    projects: ProjectRepository,
    appConfig: AppConfig,
) : ViewModel() {

    private val keys = MutableStateFlow(readKeys())
    private val cache = MutableStateFlow(0L to false)
    private val _events = Channel<SettingsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val state: StateFlow<SettingsUiState> = combine(
        preferences.userPreferences,
        entitlementProvider.entitlements,
        keys,
        projects.observeStorageBytes(),
        cache,
    ) { prefs, ent, k, media, (cacheBytes, clearing) ->
        SettingsUiState(prefs, ent, k, appConfig.isAiGatewayConfigured, media, cacheBytes, clearing)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState(aiGatewayConfigured = appConfig.isAiGatewayConfigured))

    init {
        refreshCacheSize()
    }

    private fun readKeys() = runCatching {
        KeyStatus(credentials.hasKey(AiProviderId.ANTHROPIC_DIRECT), credentials.hasKey(AiProviderId.OPENAI_COMPATIBLE), credentials.hasSpeechKey())
    }.getOrDefault(KeyStatus())

    private fun update(transform: (UserPreferences) -> UserPreferences) {
        viewModelScope.launch { preferences.updateUserPreferences { transform(it).copy(updatedAt = System.currentTimeMillis()) } }
    }

    fun setLanguage(language: AppLanguage) = update { it.copy(language = language) }
    fun setTheme(mode: ThemeMode) = update { it.copy(themeMode = mode) }
    fun setHaptics(enabled: Boolean) = update { it.copy(hapticsEnabled = enabled) }
    fun setReduceMotion(enabled: Boolean) = update { it.copy(reduceMotion = enabled) }
    fun setSaveToGallery(enabled: Boolean) = update { it.copy(saveToGallery = enabled) }
    fun setKeepScreenOn(enabled: Boolean) = update { it.copy(keepScreenOnWhilePrompting = enabled) }

    fun setExportQuality(quality: ExportQuality) {
        val feature = quality.feature
        if (feature != null && !entitlementProvider.has(feature)) {
            _events.trySend(SettingsEvent.RequirePro(feature))
            return
        }
        update { it.copy(defaultExport = it.defaultExport.copy(resolutionShortSide = quality.shortSide, frameRate = quality.fps)) }
    }

    fun setHevc(enabled: Boolean) {
        if (enabled && !entitlementProvider.has(ProFeature.RECORD_HEVC)) {
            _events.trySend(SettingsEvent.RequirePro(ProFeature.RECORD_HEVC))
            return
        }
        update { it.copy(defaultExport = it.defaultExport.copy(codec = if (enabled) VideoCodec.HEVC else VideoCodec.H264)) }
    }

    fun setTextProvider(provider: AiProviderId) = update { it.copy(ai = it.ai.copy(textProvider = provider)) }
    fun setSpeechProvider(provider: SpeechProviderId) = update { it.copy(ai = it.ai.copy(speechProvider = provider)) }
    fun setConsent(enabled: Boolean) = update { it.copy(ai = it.ai.copy(cloudProcessingConsent = enabled)) }

    fun setCustomEndpoint(baseUrl: String, model: String) = update {
        it.copy(ai = it.ai.copy(customBaseUrl = baseUrl.trim().trimEnd('/').ifBlank { null }, customModel = model.trim().ifBlank { null }))
    }

    fun saveKey(provider: AiProviderId, key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return
        credentials.setKey(provider, trimmed)
        keys.value = readKeys()
        _events.trySend(SettingsEvent.KeySaved)
    }

    fun removeKey(provider: AiProviderId) {
        credentials.setKey(provider, null)
        keys.value = readKeys()
        _events.trySend(SettingsEvent.KeyRemoved)
    }

    fun saveSpeechKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return
        credentials.setSpeechKey(trimmed)
        keys.value = readKeys()
        _events.trySend(SettingsEvent.KeySaved)
    }

    fun removeSpeechKey() {
        credentials.setSpeechKey(null)
        keys.value = readKeys()
        _events.trySend(SettingsEvent.KeyRemoved)
    }

    fun refreshCacheSize() {
        viewModelScope.launch { cache.update { localData.cacheSizeBytes() to it.second } }
    }

    fun clearCache() {
        if (cache.value.second) return
        viewModelScope.launch {
            cache.update { it.first to true }
            val freed = localData.clearCaches()
            cache.value = localData.cacheSizeBytes() to false
            _events.send(SettingsEvent.CacheCleared(freed))
        }
    }
}
