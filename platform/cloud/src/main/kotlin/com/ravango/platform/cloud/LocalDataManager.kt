package com.ravango.platform.cloud

import android.content.Context
import com.ravango.core.common.di.IoDispatcher
import com.ravango.core.common.log.RgLog
import com.ravango.core.data.mapper.toEntity
import com.ravango.core.data.seed.BuiltInPresets
import com.ravango.core.database.RavanGoDatabase
import com.ravango.core.datastore.PreferencesDataSource
import com.ravango.core.model.SyncStatus
import com.ravango.platform.cloud.settings.SettingsSync
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device data management for Settings/Privacy: cache size & clearing, and erasing everything the user created on
 * this device (scripts, projects, recordings in app storage, presets, sync state). App preferences (language,
 * theme, consents) are kept so the app stays usable after the wipe.
 */
@Singleton
class LocalDataManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: RavanGoDatabase,
    private val preferences: PreferencesDataSource,
    private val settingsSync: SettingsSync,
    private val status: SyncStatusStore,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    suspend fun cacheSizeBytes(): Long = withContext(io) {
        listOfNotNull(context.cacheDir, context.externalCacheDir).sumOf { dirSize(it) }
    }

    /** Deletes temporary files (thumbnails, previews, intermediate exports). Returns bytes freed. */
    suspend fun clearCaches(): Long = withContext(io) {
        var freed = 0L
        listOfNotNull(context.cacheDir, context.externalCacheDir).forEach { dir ->
            dir.listFiles()?.forEach { child ->
                val size = dirSize(child)
                if (child.deleteRecursively()) freed += size
            }
        }
        freed
    }

    /** Irreversibly erases all user content stored on this device. */
    suspend fun wipeLocalData() = withContext(io) {
        val assets = database.mediaAssetDao().observeAll(null).first()
        val privateRoots = listOfNotNull(context.filesDir, context.getExternalFilesDir(null), context.cacheDir).map { it.canonicalPath }
        assets.forEach { asset ->
            val path = asset.uri.removePrefix("file://").removePrefix("file:")
            if (path.startsWith("/")) {
                val file = File(path)
                // Never touch gallery or imported originals: only files inside app-private storage.
                if (privateRoots.any { runCatching { file.canonicalPath.startsWith(it) }.getOrDefault(false) }) file.delete()
            }
        }
        database.clearAllTables()
        // Built-in presets are part of the app, not user data.
        database.beautyPresetDao().upsertAll(BuiltInPresets.beauty.map { it.copy(syncStatus = SyncStatus.SYNCED).toEntity() })
        database.prompterPresetDao().upsertAll(BuiltInPresets.prompter.map { it.copy(syncStatus = SyncStatus.SYNCED).toEntity() })
        File(context.filesDir, "media").deleteRecursively()
        clearCaches()
        settingsSync.reset()
        status.reset()
        preferences.putString(KEY_LAST_USER, null)
        RgLog.i("LocalData", "local data erased")
    }

    private fun dirSize(file: File): Long = if (file.isFile) file.length() else file.listFiles()?.sumOf { dirSize(it) } ?: 0L

    internal companion object {
        const val KEY_LAST_USER = "cloud.lastUserId"
    }
}
