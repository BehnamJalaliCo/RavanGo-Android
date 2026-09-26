package com.ravango.platform.cloud

import android.content.Context
import com.ravango.core.common.log.RgLog
import dagger.hilt.android.qualifiers.ApplicationContext
import com.ravango.core.database.dao.SyncCursorDao
import com.ravango.core.database.entity.SyncCursorEntity
import com.ravango.core.datastore.PreferencesDataSource
import com.ravango.core.model.Clock
import com.ravango.core.model.Entitlements
import com.ravango.core.model.SyncStatus
import com.ravango.core.model.service.AuthState
import com.ravango.core.model.service.EntitlementProvider
import com.ravango.platform.auth.AuthRepository
import com.ravango.platform.auth.supabase.SupabaseEndpoints
import com.ravango.platform.auth.supabase.SupabaseHttpException
import com.ravango.platform.auth.supabase.SupabaseJson
import com.ravango.platform.cloud.local.LocalRecord
import com.ravango.platform.cloud.local.SyncAdapter
import com.ravango.platform.cloud.local.SyncAdapters
import com.ravango.platform.cloud.merge.ConflictStrategy
import com.ravango.platform.cloud.merge.CursorPager
import com.ravango.platform.cloud.merge.MergeDecision
import com.ravango.platform.cloud.merge.MergePolicy
import com.ravango.platform.cloud.merge.PageCursor
import com.ravango.platform.cloud.merge.RecordMeta
import com.ravango.platform.cloud.remote.OutgoingRow
import com.ravango.platform.cloud.remote.PostgrestClient
import com.ravango.platform.cloud.remote.RemoteRow
import com.ravango.platform.cloud.remote.StorageClient
import com.ravango.platform.cloud.settings.SettingsSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Result of one sync run. */
sealed interface SyncRunResult {
    data class Success(val pushed: Int, val pulled: Int, val conflictCopies: Int) : SyncRunResult
    /** Nothing to do: sync disabled, signed out or not configured. */
    data class Skipped(val issue: SyncIssue?) : SyncRunResult
    data class Failed(val issue: SyncIssue, val retryable: Boolean) : SyncRunResult
}

/**
 * Performs one full sync: settings, then every collection the plan allows (pull → merge → push).
 * Serialized with a mutex so the periodic worker, the debounced worker and "Sync now" never overlap.
 */
@Singleton
class SyncEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val endpoints: SupabaseEndpoints,
    private val auth: AuthRepository,
    private val preferences: PreferencesDataSource,
    private val entitlements: EntitlementProvider,
    private val adapters: SyncAdapters,
    private val settingsSync: SettingsSync,
    private val postgrest: PostgrestClient,
    private val storage: StorageClient,
    private val cursors: SyncCursorDao,
    private val status: SyncStatusStore,
    private val clock: Clock,
) {
    private val mutex = Mutex()

    /** Localized "(conflict copy)" suffix, resolved in the app's current language. */
    private val conflictSuffix: String get() = context.getString(R.string.cloud_conflict_copy_suffix)

    suspend fun sync(reason: String): SyncRunResult = mutex.withLock { syncLocked(reason) }

    private suspend fun syncLocked(reason: String): SyncRunResult {
        if (!endpoints.isConfigured) return SyncRunResult.Skipped(SyncIssue.NOT_CONFIGURED)
        if (!preferences.currentUserPreferences().cloudSyncEnabled) return SyncRunResult.Skipped(null)
        val user = (auth.authState.value as? AuthState.SignedIn)?.user ?: return SyncRunResult.Skipped(SyncIssue.SIGN_IN_REQUIRED)
        RgLog.i(TAG, "sync start ($reason)")
        status.setRunning(true)
        try {
            val token = auth.accessToken()
                ?: return fail(if (auth.authState.value is AuthState.SignedIn) SyncIssue.NETWORK else SyncIssue.SESSION_EXPIRED, retryable = true)
            prepareForAccount(user.id)
            val plan = entitlements.entitlements.value
            settingsSync.sync(user.id, token)
            var pushed = 0
            var pulled = 0
            var copies = 0
            for (adapter in enabledAdapters(plan)) {
                val stats = syncCollection(adapter, user.id, token)
                pushed += stats.pushed
                pulled += stats.pulled
                copies += stats.copies
            }
            status.markSucceeded(clock.now())
            RgLog.i(TAG, "sync done: pushed=$pushed pulled=$pulled conflictCopies=$copies")
            return SyncRunResult.Success(pushed, pulled, copies)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SupabaseHttpException) {
            RgLog.w(TAG, "sync failed", e)
            return when {
                e.isUnauthorized -> fail(SyncIssue.SESSION_EXPIRED, retryable = false)
                e.status >= 500 || e.isRateLimited -> fail(SyncIssue.SERVER, retryable = true)
                else -> fail(SyncIssue.SERVER, retryable = false)
            }
        } catch (e: IOException) {
            RgLog.w(TAG, "sync network failure", e)
            return fail(SyncIssue.NETWORK, retryable = true)
        } catch (e: Exception) {
            RgLog.e(TAG, "sync crashed", e)
            return fail(SyncIssue.UNKNOWN, retryable = false)
        } finally {
            status.setRunning(false)
        }
    }

    fun enabledAdapters(plan: Entitlements): List<SyncAdapter> =
        adapters.all.filter { it.requiredFeature == null || plan.has(it.requiredFeature!!) }

    /** Number of local changes waiting to be pushed (for the status UI). */
    suspend fun pendingCount(): Int {
        val plan = entitlements.entitlements.value
        val rows = enabledAdapters(plan).sumOf { runCatching { it.pending().size }.getOrDefault(0) }
        return rows + if (runCatching { settingsSync.hasPendingChanges() }.getOrDefault(false)) 1 else 0
    }

    private fun fail(issue: SyncIssue, retryable: Boolean): SyncRunResult {
        status.setIssue(issue)
        return SyncRunResult.Failed(issue, retryable)
    }

    /**
     * When a different account (or the first account) syncs on this device, everything local is re-uploaded to
     * it and pulled from scratch — rows marked SYNCED for another account must not be skipped.
     */
    private suspend fun prepareForAccount(userId: String) {
        val last = preferences.observeString(KEY_LAST_USER).first()
        if (last == userId) return
        RgLog.i(TAG, "new account on this device: full re-sync")
        cursors.clear()
        settingsSync.reset()
        adapters.all.forEach { it.markAllPending() }
        adapters.all.forEach { preferences.putString(baseKey(it), null) }
        preferences.putString(KEY_LAST_USER, userId)
    }

    private data class Stats(val pushed: Int, val pulled: Int, val copies: Int)

    private suspend fun syncCollection(adapter: SyncAdapter, userId: String, token: String): Stats {
        val bases = loadBases(adapter)
        var pulled = 0
        var copies = 0

        // Pull.
        var cursor = PageCursor(cursors.get(adapter.table) ?: 0L, 0)
        while (true) {
            val page = postgrest.pullPage(adapter.table, userId, cursor, PAGE_SIZE, token)
            for (row in page) {
                val outcome = runCatching { mergeRow(adapter, row, bases) }
                    .onFailure { if (it is CancellationException) throw it; RgLog.w(TAG, "skipping bad row ${adapter.table}/${row.id}", it) }
                    .getOrNull()
                if (outcome != null) {
                    if (outcome.applied) pulled++
                    copies += outcome.copies
                }
            }
            val timestamps = page.map { it.serverUpdatedAtUs }
            cursors.upsert(SyncCursorEntity(adapter.table, CursorPager.persisted(cursor, timestamps)))
            cursor = CursorPager.next(cursor, timestamps, PAGE_SIZE) ?: break
        }

        // Push.
        var pushed = 0
        for (batch in adapter.pending().chunked(PUSH_BATCH)) {
            val stored = postgrest.upsert(adapter.table, userId, batch.map { OutgoingRow(it.id, it.updatedAt, it.deletedAt, it.data) }, token)
                .associateBy { it.id }
            for (record in batch) {
                val server = stored[record.id]
                if (server == null || server.updatedAt == record.updatedAt) {
                    adapter.markSynced(record)
                    bases[record.id] = record.updatedAt
                    pushed++
                } else if (bases[record.id] == server.updatedAt) {
                    // The server still holds the version we last synced but our edit carries an older timestamp
                    // (this device's clock is behind): re-push just after the server's version.
                    val bumped = server.updatedAt + 1
                    postgrest.upsert(adapter.table, userId, listOf(OutgoingRow(record.id, bumped, record.deletedAt, record.data)), token)
                    adapter.markSynced(record)
                    bases[record.id] = bumped
                    pushed++
                } else {
                    // The server kept a newer version written by another device: merge it like a pulled row.
                    val outcome = mergeRow(adapter, server, bases)
                    copies += outcome.copies
                }
            }
            afterPush(adapter, batch, token)
        }
        saveBases(adapter, bases)
        return Stats(pushed, pulled, copies)
    }

    private data class MergeOutcome(val applied: Boolean, val copies: Int)

    private suspend fun mergeRow(adapter: SyncAdapter, row: RemoteRow, bases: MutableMap<String, Long>): MergeOutcome {
        val local = adapter.local(row.id)
        val decision = MergePolicy.decide(
            local = local?.let { RecordMeta(it.updatedAt, it.deletedAt != null, it.status, bases[it.id]) },
            remote = RecordMeta(row.updatedAt, row.deletedAt != null),
            strategy = adapter.strategy,
            sameContent = local != null && local.data == row.data,
        )
        val now = clock.now()
        return when (decision) {
            MergeDecision.Skip -> {
                bases[row.id] = row.updatedAt
                MergeOutcome(false, 0)
            }
            MergeDecision.ApplyRemote -> {
                adapter.applyRemote(row)
                bases[row.id] = row.updatedAt
                MergeOutcome(true, 0)
            }
            MergeDecision.KeepLocal -> MergeOutcome(false, 0)
            MergeDecision.ApplyRemoteKeepLocalCopy -> {
                val copied = local?.let { adapter.saveConflictCopy(it.data, now, conflictSuffix) }
                adapter.applyRemote(row)
                bases[row.id] = row.updatedAt
                MergeOutcome(true, if (copied != null) 1 else 0)
            }
            MergeDecision.KeepLocalSaveRemoteCopy -> {
                val copied = adapter.saveConflictCopy(row.data, now, conflictSuffix)
                // The remote version is now accounted for; our pending edit overwrites it on push.
                bases[row.id] = row.updatedAt
                MergeOutcome(false, if (copied != null) 1 else 0)
            }
        }
    }

    /** Media tombstones: remove the backed-up file too (best effort; the row itself is already a tombstone). */
    private suspend fun afterPush(adapter: SyncAdapter, batch: List<LocalRecord>, token: String) {
        if (adapter.table != com.ravango.core.data.repository.SyncCollection.MEDIA.remoteName) return
        val paths = batch.filter { it.deletedAt != null && !it.cloudPath.isNullOrBlank() }.mapNotNull { it.cloudPath }
        if (paths.isEmpty()) return
        runCatching { storage.delete(StorageClient.MEDIA_BUCKET, paths, token) }
            .onFailure { if (it is CancellationException) throw it; RgLog.w(TAG, "could not delete ${paths.size} backed-up files", it) }
    }

    private fun baseKey(adapter: SyncAdapter) = "cloud.base.${adapter.table}"

    private val basesSerializer = MapSerializer(String.serializer(), Long.serializer())

    /**
     * Base versions (last synced `updated_at` per row) are persisted only for collections that keep conflict
     * copies (scripts); other collections track them for the duration of one run.
     */
    private suspend fun loadBases(adapter: SyncAdapter): MutableMap<String, Long> {
        if (adapter.strategy != ConflictStrategy.KEEP_CONFLICT_COPY) return mutableMapOf()
        val raw = preferences.observeString(baseKey(adapter)).first() ?: return mutableMapOf()
        return runCatching { SupabaseJson.decodeFromString(basesSerializer, raw).toMutableMap() }.getOrElse { mutableMapOf() }
    }

    private suspend fun saveBases(adapter: SyncAdapter, bases: Map<String, Long>) {
        if (adapter.strategy != ConflictStrategy.KEEP_CONFLICT_COPY) return
        // Drop entries of rows that no longer exist or are synced tombstones.
        val live = bases.filterKeys { id -> adapter.local(id)?.let { !(it.deletedAt != null && it.status == SyncStatus.SYNCED) } == true }
        preferences.putString(baseKey(adapter), SupabaseJson.encodeToString(basesSerializer, live))
    }

    private companion object {
        const val TAG = "Sync"
        const val PAGE_SIZE = 200
        const val PUSH_BATCH = 100
        const val KEY_LAST_USER = "cloud.lastUserId"
    }
}
