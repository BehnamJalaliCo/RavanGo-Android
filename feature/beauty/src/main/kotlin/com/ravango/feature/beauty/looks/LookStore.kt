package com.ravango.feature.beauty.looks

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.ravango.core.common.di.ApplicationScope
import com.ravango.core.common.di.IoDispatcher
import com.ravango.core.common.log.RgLog
import com.ravango.core.model.BeautyState
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.plus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Singleton

/** Everything the looks feature persists (one JSON document, written atomically). */
@Serializable
data class LookPrefs(
    /** Favourite carousel items («look:<id>», «lens:<id>», «filter:<id>»), newest first. */
    val favourites: List<String> = emptyList(),
    /** Recently used carousel items, newest first, at most [MAX_RECENTS]. */
    val recents: List<String> = emptyList(),
    /** The look currently applied (null = none) and its intensity. */
    val activeId: String? = null,
    val intensity: Int = 100,
    /** The user's own settings before the look was applied (restored by "Clear"). */
    val base: BeautyState? = null,
    val baseEyeColor: Long? = null,
    val baseEyeIntensity: Int = 0,
    /** Looks the user saved. */
    val custom: List<CustomLook> = emptyList(),
) {
    companion object {
        const val MAX_RECENTS = 12
        const val MAX_CUSTOM = 30
    }
}

/** Persistence of favourites, recents, the active look and custom looks. */
interface LookStore {
    val prefs: Flow<LookPrefs>
    suspend fun update(transform: (LookPrefs) -> LookPrefs)
}

/** [LookStore] on a Preferences DataStore (`ravango_looks`), JSON-encoded. Corrupt data reads as defaults. */
class DataStoreLookStore(private val dataStore: DataStore<Preferences>) : LookStore {

    override val prefs: Flow<LookPrefs> = dataStore.data
        .catch { e ->
            RgLog.w(TAG, "Could not read looks", e)
            emit(emptyPreferences())
        }
        .map { p -> decode(p[KEY]) }

    override suspend fun update(transform: (LookPrefs) -> LookPrefs) {
        dataStore.edit { p -> p[KEY] = json.encodeToString(LookPrefs.serializer(), transform(decode(p[KEY]))) }
    }

    private fun decode(raw: String?): LookPrefs = if (raw.isNullOrBlank()) {
        LookPrefs()
    } else {
        try {
            json.decodeFromString(LookPrefs.serializer(), raw)
        } catch (e: Exception) {
            RgLog.w(TAG, "Looks data unreadable; starting fresh", e)
            LookPrefs()
        }
    }

    companion object {
        private const val TAG = "LookStore"
        private val KEY = stringPreferencesKey("looks_v1")
        internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        fun create(scope: CoroutineScope, produceFile: () -> java.io.File): DataStoreLookStore =
            DataStoreLookStore(
                PreferenceDataStoreFactory.create(
                    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                    scope = scope,
                    produceFile = produceFile,
                ),
            )
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal object LookStoreModule {
    @Provides
    @Singleton
    fun lookStore(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
        @IoDispatcher io: CoroutineDispatcher,
    ): LookStore = DataStoreLookStore.create(scope + io) { context.preferencesDataStoreFile("ravango_looks") }
}
