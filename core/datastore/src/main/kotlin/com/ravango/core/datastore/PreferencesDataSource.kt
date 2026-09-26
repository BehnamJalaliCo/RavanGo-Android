package com.ravango.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ravango.core.common.log.RgLog
import com.ravango.core.model.AudioSettings
import com.ravango.core.model.BeautyState
import com.ravango.core.model.CameraSettings
import com.ravango.core.model.TeleprompterSettings
import com.ravango.core.model.UserPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "ravango_settings")

/** JSON codec shared by persistence layers; tolerant to added/removed fields across app versions. */
val PersistenceJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    coerceInputValues = true
}

/**
 * Typed, observable settings. Each value is a serialized model under one key so models can evolve without
 * migrations (unknown fields ignored, missing fields defaulted).
 */
@Singleton
class PreferencesDataSource @Inject constructor(@ApplicationContext context: Context) {

    private val store = context.settingsStore

    private class Entry<T>(val key: Preferences.Key<String>, val serializer: KSerializer<T>, val default: () -> T)

    private val user = Entry(stringPreferencesKey("user_prefs"), UserPreferences.serializer()) { UserPreferences() }
    private val prompter = Entry(stringPreferencesKey("prompter_defaults"), TeleprompterSettings.serializer()) { TeleprompterSettings() }
    private val camera = Entry(stringPreferencesKey("camera_settings"), CameraSettings.serializer()) { CameraSettings() }
    private val audio = Entry(stringPreferencesKey("audio_settings"), AudioSettings.serializer()) { AudioSettings() }
    private val beauty = Entry(stringPreferencesKey("beauty_state"), BeautyState.serializer()) { BeautyState() }

    val userPreferences: Flow<UserPreferences> = observe(user)
    val prompterDefaults: Flow<TeleprompterSettings> = observe(prompter)
    val cameraSettings: Flow<CameraSettings> = observe(camera)
    val audioSettings: Flow<AudioSettings> = observe(audio)
    val beautyState: Flow<BeautyState> = observe(beauty)

    suspend fun updateUserPreferences(transform: (UserPreferences) -> UserPreferences) = update(user, transform)
    suspend fun updatePrompterDefaults(transform: (TeleprompterSettings) -> TeleprompterSettings) = update(prompter, transform)
    suspend fun updateCameraSettings(transform: (CameraSettings) -> CameraSettings) = update(camera, transform)
    suspend fun updateAudioSettings(transform: (AudioSettings) -> AudioSettings) = update(audio, transform)
    suspend fun updateBeautyState(transform: (BeautyState) -> BeautyState) = update(beauty, transform)

    suspend fun currentUserPreferences(): UserPreferences = userPreferences.first()

    /** Generic string values for small flags owned by other modules (e.g. "last_sync_at"). */
    fun observeString(name: String): Flow<String?> = store.data.safe().map { it[stringPreferencesKey(name)] }.distinctUntilChanged()

    suspend fun putString(name: String, value: String?) {
        store.edit { prefs -> if (value == null) prefs.remove(stringPreferencesKey(name)) else prefs[stringPreferencesKey(name)] = value }
    }

    private fun <T> observe(entry: Entry<T>): Flow<T> = store.data.safe()
        .map { prefs -> decode(entry, prefs[entry.key]) }
        .distinctUntilChanged()

    private suspend fun <T> update(entry: Entry<T>, transform: (T) -> T) {
        store.edit { prefs ->
            val current = decode(entry, prefs[entry.key])
            prefs[entry.key] = PersistenceJson.encodeToString(entry.serializer, transform(current))
        }
    }

    private fun <T> decode(entry: Entry<T>, raw: String?): T =
        raw?.let { runCatching { PersistenceJson.decodeFromString(entry.serializer, it) }.getOrNull() } ?: entry.default()

    private fun Flow<Preferences>.safe(): Flow<Preferences> = catch { e ->
        if (e is IOException) {
            RgLog.w("Prefs", "read failed, using defaults", e)
            emit(emptyPreferences())
        } else {
            throw e
        }
    }
}
