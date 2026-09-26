package com.ravango.platform.cloud.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.ravango.core.common.log.RgLog
import com.ravango.core.data.repository.LocalChangeBus
import com.ravango.core.data.repository.SyncCollection
import com.ravango.core.database.dao.MediaAssetDao
import com.ravango.core.database.entity.MediaAssetEntity
import com.ravango.core.datastore.PreferencesDataSource
import com.ravango.core.model.ProFeature
import com.ravango.core.model.SyncStatus
import com.ravango.core.model.service.AuthState
import com.ravango.core.model.service.EntitlementProvider
import com.ravango.platform.auth.AuthRepository
import com.ravango.platform.auth.supabase.SupabaseEndpoints
import com.ravango.platform.auth.supabase.SupabaseHttpException
import com.ravango.platform.cloud.MediaBackupProgress
import com.ravango.platform.cloud.SyncIssue
import com.ravango.platform.cloud.SyncStatusStore
import com.ravango.platform.cloud.local.CLOUD_URI_PREFIX
import com.ravango.platform.cloud.remote.ResumableUploadStore
import com.ravango.platform.cloud.remote.StorageClient
import com.ravango.platform.cloud.remote.UploadSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface MediaBackupResult {
    data class Done(val uploaded: Int, val restored: Int) : MediaBackupResult
    data class Skipped(val issue: SyncIssue?) : MediaBackupResult
    data class Failed(val issue: SyncIssue, val retryable: Boolean) : MediaBackupResult
}

/** Pure quota check used by the backup loop (and tests). */
object MediaQuota {
    /** Assets (in upload order) that fit into [quotaBytes] given [usedBytes] already stored. */
    fun <T> fitting(candidates: List<T>, sizeOf: (T) -> Long, usedBytes: Long, quotaBytes: Long): Pair<List<T>, List<T>> {
        var used = usedBytes
        val fit = mutableListOf<T>()
        val over = mutableListOf<T>()
        for (c in candidates) {
            val size = sizeOf(c)
            if (used + size <= quotaBytes) {
                fit += c
                used += size
            } else {
                over += c
            }
        }
        return fit to over
    }
}

/**
 * Pro media backup: uploads recordings/imports to `media/<user_id>/<asset_id>.<ext>` and restores backed-up
 * files onto devices where only the metadata exists. Opt-in (`UserPreferences.backupMedia`), gated by
 * [ProFeature.CLOUD_MEDIA_BACKUP], limited by the plan's cloud quota. Network constraints (Wi-Fi only) are
 * enforced by the WorkManager request.
 */
@Singleton
class MediaBackupEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val endpoints: SupabaseEndpoints,
    private val auth: AuthRepository,
    private val preferences: PreferencesDataSource,
    private val entitlements: EntitlementProvider,
    private val dao: MediaAssetDao,
    private val storage: StorageClient,
    private val changes: LocalChangeBus,
    private val status: SyncStatusStore,
) {
    private val mutex = Mutex()

    private val resumeStore = object : ResumableUploadStore {
        override suspend fun get(key: String): String? = preferences.observeString("cloud.tus.$key").first()
        override suspend fun put(key: String, uploadUrl: String?) = preferences.putString("cloud.tus.$key", uploadUrl)
    }

    suspend fun run(): MediaBackupResult = mutex.withLock {
        val prefs = preferences.currentUserPreferences()
        if (!endpoints.isConfigured) return@withLock MediaBackupResult.Skipped(SyncIssue.NOT_CONFIGURED)
        if (!prefs.cloudSyncEnabled || !prefs.backupMedia || !entitlements.has(ProFeature.CLOUD_MEDIA_BACKUP)) return@withLock MediaBackupResult.Skipped(null)
        val user = (auth.authState.value as? AuthState.SignedIn)?.user ?: return@withLock MediaBackupResult.Skipped(SyncIssue.SIGN_IN_REQUIRED)
        val token = auth.accessToken() ?: return@withLock fail(SyncIssue.NETWORK, true)
        try {
            val assets = dao.observeAll(null).first()
            val uploaded = upload(user.id, token, assets)
            val restored = restore(token, dao.observeAll(null).first())
            status.setMediaIssue(if (uploaded.second) SyncIssue.QUOTA_EXCEEDED else null)
            MediaBackupResult.Done(uploaded.first, restored)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SupabaseHttpException) {
            RgLog.w(TAG, "media backup failed", e)
            when {
                e.isUnauthorized -> fail(SyncIssue.SESSION_EXPIRED, false)
                e.status == 413 -> fail(SyncIssue.QUOTA_EXCEEDED, false)
                else -> fail(SyncIssue.SERVER, e.status >= 500 || e.isRateLimited)
            }
        } catch (e: IOException) {
            RgLog.w(TAG, "media backup network failure", e)
            fail(SyncIssue.NETWORK, true)
        } finally {
            status.setMedia(MediaBackupProgress())
        }
    }

    private fun fail(issue: SyncIssue, retryable: Boolean): MediaBackupResult {
        status.setMediaIssue(issue)
        return MediaBackupResult.Failed(issue, retryable)
    }

    /** Returns (uploaded count, quota exceeded). */
    private suspend fun upload(userId: String, token: String, assets: List<MediaAssetEntity>): Pair<Int, Boolean> {
        val quota = entitlements.entitlements.value.cloudQuotaBytes
        val used = assets.filter { it.cloudPath != null }.sumOf { it.sizeBytes }
        val candidates = assets
            .filter { it.cloudPath == null && !it.uri.startsWith(CLOUD_URI_PREFIX) }
            .mapNotNull { asset -> sourceFor(asset)?.let { asset to it } }
        val (fit, over) = MediaQuota.fitting(candidates, { it.second.sizeBytes }, used, quota)
        val total = fit.sumOf { it.second.sizeBytes }
        var done = 0L
        var count = 0
        status.setMedia(MediaBackupProgress(true, 0, fit.size, 0, total))
        for ((asset, source) in fit) {
            val path = "$userId/${asset.id}.${extensionFor(asset)}"
            val base = done
            storage.upload(StorageClient.MEDIA_BUCKET, path, source, token, resumeStore) { sent ->
                status.setMedia(MediaBackupProgress(true, count, fit.size, base + sent, total))
            }
            done += source.sizeBytes
            count++
            // Record the object path and mark the row for a metadata push.
            dao.getById(asset.id)?.let { current -> dao.upsert(current.copy(cloudPath = path, syncStatus = SyncStatus.PENDING.name)) }
            changes.notifyChanged(SyncCollection.MEDIA)
            status.setMedia(MediaBackupProgress(true, count, fit.size, done, total))
        }
        if (over.isNotEmpty()) RgLog.w(TAG, "${over.size} files exceed the cloud quota")
        return count to over.isNotEmpty()
    }

    /** Downloads backed-up files for assets that only exist in the cloud on this device. */
    private suspend fun restore(token: String, assets: List<MediaAssetEntity>): Int {
        var restored = 0
        val dir = File(context.filesDir, "media/restored")
        for (asset in assets.filter { it.uri.startsWith(CLOUD_URI_PREFIX) && it.cloudPath != null }) {
            val target = File(dir, "${asset.id}.${extensionFor(asset)}")
            storage.download(StorageClient.MEDIA_BUCKET, asset.cloudPath!!, target, token)
            dao.getById(asset.id)?.let { current -> dao.upsert(current.copy(uri = target.absolutePath)) }
            restored++
        }
        return restored
    }

    private fun sourceFor(asset: MediaAssetEntity): UploadSource? = runCatching {
        val uri = asset.uri
        when {
            uri.startsWith("content://") -> {
                val parsed = Uri.parse(uri)
                val size = context.contentResolver.query(parsed, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
                } ?: asset.sizeBytes.takeIf { it > 0 } ?: return@runCatching null
                UploadSource(size, asset.mimeType) { context.contentResolver.openInputStream(parsed) ?: throw IOException("cannot open $uri") }
            }
            uri.startsWith("/") || uri.startsWith("file:") -> {
                val file = File(uri.removePrefix("file://").removePrefix("file:"))
                if (!file.isFile) return@runCatching null
                UploadSource(file.length(), asset.mimeType) { file.inputStream() }
            }
            else -> null
        }
    }.getOrNull()

    private fun extensionFor(asset: MediaAssetEntity): String = when {
        asset.mimeType.contains("mp4") -> "mp4"
        asset.mimeType.contains("quicktime") -> "mov"
        asset.mimeType.contains("webm") -> "webm"
        asset.mimeType.contains("3gpp") -> "3gp"
        asset.mimeType.contains("mpeg") && asset.kind == "AUDIO" -> "mp3"
        asset.mimeType.contains("aac") -> "aac"
        asset.mimeType.contains("mp4a") || asset.mimeType.contains("m4a") -> "m4a"
        asset.mimeType.contains("wav") -> "wav"
        asset.mimeType.contains("ogg") -> "ogg"
        asset.mimeType.contains("png") -> "png"
        asset.mimeType.contains("webp") -> "webp"
        asset.mimeType.contains("jpeg") || asset.mimeType.contains("jpg") -> "jpg"
        else -> asset.uri.substringAfterLast('.', "bin").take(5).lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "bin" }
    }

    /** Deletes every object in the user's folder (account deletion). Throws on failure so deletion can abort. */
    suspend fun deleteAllFor(userId: String, token: String) {
        val names = storage.list(StorageClient.MEDIA_BUCKET, userId, token)
        if (names.isNotEmpty()) storage.delete(StorageClient.MEDIA_BUCKET, names.map { "$userId/$it" }, token)
    }

    private companion object {
        const val TAG = "MediaBackup"
    }
}
