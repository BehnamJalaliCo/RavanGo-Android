package com.ravango.platform.cloud

import com.ravango.core.common.di.ApplicationScope
import com.ravango.core.common.log.RgLog
import com.ravango.core.data.repository.LocalChangeBus
import com.ravango.core.database.dao.MediaAssetDao
import com.ravango.core.datastore.PreferencesDataSource
import com.ravango.core.model.ProFeature
import com.ravango.core.model.UserPreferences
import com.ravango.core.model.service.AuthState
import com.ravango.core.model.service.EntitlementProvider
import com.ravango.core.model.service.SyncController
import com.ravango.core.model.service.SyncPhase
import com.ravango.core.model.service.SyncState
import com.ravango.platform.auth.AuthRepository
import com.ravango.platform.auth.supabase.SupabaseEndpoints
import com.ravango.platform.cloud.settings.SettingsSync
import com.ravango.platform.cloud.work.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Everything the Cloud screen needs, in one snapshot. */
data class CloudStatus(
    val configured: Boolean = false,
    val signedIn: Boolean = false,
    val syncEnabled: Boolean = false,
    val wifiOnly: Boolean = true,
    val backupMedia: Boolean = false,
    val canSyncProjects: Boolean = false,
    val canBackupMedia: Boolean = false,
    val phase: SyncPhase = SyncPhase.DISABLED,
    val issue: SyncIssue? = null,
    val lastSyncedAt: Long? = null,
    val pendingChanges: Int = 0,
    val online: Boolean = true,
    val onUnmeteredNetwork: Boolean = true,
    /** Sync is waiting for Wi-Fi (wifi-only on a metered network). */
    val waitingForWifi: Boolean = false,
    val backedUpBytes: Long = 0,
    val quotaBytes: Long = 0,
    val media: MediaBackupProgress = MediaBackupProgress(),
    val mediaIssue: SyncIssue? = null,
)

enum class CloudToggleResult { OK, NOT_CONFIGURED, SIGN_IN_REQUIRED, REQUIRES_PRO }

/**
 * [SyncController] implementation: observes local changes ([LocalChangeBus] + settings), auth state, preferences
 * and connectivity; schedules debounced one-time sync work plus a 6-hour periodic sync while sync is enabled and
 * the user is signed in. Sync is off by default and requires an account.
 */
@OptIn(FlowPreview::class)
@Singleton
class CloudSyncController @Inject constructor(
    private val endpoints: SupabaseEndpoints,
    private val auth: AuthRepository,
    private val preferences: PreferencesDataSource,
    private val changeBus: LocalChangeBus,
    private val engine: SyncEngine,
    private val scheduler: SyncScheduler,
    private val network: NetworkMonitor,
    private val statusStore: SyncStatusStore,
    private val entitlements: EntitlementProvider,
    private val settingsSync: SettingsSync,
    mediaDao: MediaAssetDao,
    @ApplicationScope private val scope: CoroutineScope,
) : SyncController {

    private val started = AtomicBoolean(false)
    private val requests = MutableSharedFlow<String>(extraBufferCapacity = 32)
    private val pending = MutableStateFlow(0)

    private val backedUpBytes = mediaDao.observeAll(null).map { list -> list.filter { it.cloudPath != null }.sumOf { it.sizeBytes } }

    private data class Inputs(val auth: AuthState, val prefs: UserPreferences, val net: NetworkStatus, val running: Boolean, val issue: SyncIssue?)
    private data class Extras(val lastSyncedAt: Long?, val pending: Int, val waiting: Boolean, val backedUp: Long, val media: MediaBackupProgress, val mediaIssue: SyncIssue?)

    private val inputs = combine(auth.authState, preferences.userPreferences, network.status, statusStore.running, statusStore.issue) { a, p, n, r, i -> Inputs(a, p, n, r, i) }
    private val extras = combine(
        statusStore.lastSyncedAt,
        pending,
        scheduler.observeSyncWaiting().onStart { emit(false) },
        backedUpBytes.onStart { emit(0L) },
        combine(statusStore.media, statusStore.mediaIssue) { m, i -> m to i },
    ) { last, count, waiting, bytes, media -> Extras(last, count, waiting, bytes, media.first, media.second) }

    val status: StateFlow<CloudStatus> = combine(inputs, extras, entitlements.entitlements) { i, x, plan ->
        val signedIn = i.auth is AuthState.SignedIn
        val active = endpoints.isConfigured && signedIn && i.prefs.cloudSyncEnabled
        val phase = when {
            !active -> SyncPhase.DISABLED
            i.running -> SyncPhase.SYNCING
            !i.net.online -> SyncPhase.OFFLINE
            i.issue != null -> SyncPhase.ERROR
            else -> SyncPhase.IDLE
        }
        CloudStatus(
            configured = endpoints.isConfigured,
            signedIn = signedIn,
            syncEnabled = i.prefs.cloudSyncEnabled,
            wifiOnly = i.prefs.syncOverWifiOnly,
            backupMedia = i.prefs.backupMedia,
            canSyncProjects = plan.has(ProFeature.CLOUD_PROJECT_SYNC),
            canBackupMedia = plan.has(ProFeature.CLOUD_MEDIA_BACKUP),
            phase = phase,
            issue = i.issue,
            lastSyncedAt = x.lastSyncedAt,
            pendingChanges = x.pending,
            online = i.net.online,
            onUnmeteredNetwork = i.net.unmetered,
            waitingForWifi = active && i.prefs.syncOverWifiOnly && i.net.online && !i.net.unmetered && (x.waiting || x.pending > 0),
            backedUpBytes = x.backedUp,
            quotaBytes = plan.cloudQuotaBytes,
            media = x.media,
            mediaIssue = x.mediaIssue,
        )
    }.stateIn(scope, SharingStarted.Eagerly, CloudStatus(configured = endpoints.isConfigured))

    override val state: StateFlow<SyncState> = status.map { s ->
        SyncState(
            phase = s.phase,
            lastSyncedAt = s.lastSyncedAt,
            pendingChanges = s.pendingChanges,
            message = when {
                !s.configured -> "Cloud is not configured in this build"
                !s.signedIn -> "Sign in to sync"
                s.phase == SyncPhase.OFFLINE -> "Offline"
                s.waitingForWifi -> "Waiting for Wi-Fi"
                s.issue != null -> s.issue.name
                else -> null
            },
        )
    }.stateIn(scope, SharingStarted.Eagerly, SyncState())

    private val isActive: Boolean
        get() = endpoints.isConfigured && auth.authState.value is AuthState.SignedIn && status.value.syncEnabled

    /** Starts observing; called once from the startup task. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        network.start()

        scope.launch {
            requests.debounce(DEBOUNCE_MS).collect { reason ->
                if (isActive && preferences.currentUserPreferences().cloudSyncEnabled) scheduler.enqueueSync(reason)
            }
        }
        scope.launch {
            changeBus.changes.collect { collection ->
                requestSync("change:${collection.remoteName}")
                refreshPendingSoon()
            }
        }
        scope.launch {
            settingsSync.snapshots.collect { snapshot ->
                if (settingsSync.onLocalSnapshot(snapshot)) requestSync("settings")
                refreshPendingSoon()
            }
        }
        scope.launch {
            combine(auth.authState, preferences.userPreferences) { a, p -> Triple(a is AuthState.SignedIn, p.cloudSyncEnabled, p.syncOverWifiOnly) }
                .distinctUntilChanged()
                .collect { (signedIn, enabled, wifiOnly) ->
                    scheduler.wifiOnly = wifiOnly
                    if (endpoints.isConfigured && signedIn && enabled) {
                        scheduler.schedulePeriodic()
                        scheduler.enqueueSync("activated")
                    } else {
                        scheduler.cancelAll()
                    }
                }
        }
        scope.launch {
            // Back online → catch up.
            network.status.map { it.online }.distinctUntilChanged().drop(1).filter { it }.collect { requestSync("online") }
        }
        scope.launch {
            // Plan upgrades unlock more collections → sync them right away.
            entitlements.entitlements.map { it.has(ProFeature.CLOUD_PROJECT_SYNC) to it.has(ProFeature.CLOUD_MEDIA_BACKUP) }
                .distinctUntilChanged().drop(1).collect { requestSync("plan") }
        }
        scope.launch {
            statusStore.running.filter { !it }.collect { refreshPending() }
        }
        scope.launch {
            refreshRequests.debounce(1_000).collect { refreshPending() }
        }
    }

    private val refreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private fun refreshPendingSoon() {
        refreshRequests.tryEmit(Unit)
    }

    private suspend fun refreshPending() {
        pending.value = runCatching { engine.pendingCount() }.onFailure { RgLog.w(TAG, "pending count failed", it) }.getOrDefault(pending.value)
    }

    override fun requestSync(reason: String) {
        requests.tryEmit(reason)
    }

    /** "Sync now": skips the debounce. Honors Wi-Fi only. */
    fun syncNow() {
        if (isActive) scheduler.enqueueSync("manual", expedite = true)
    }

    suspend fun setSyncEnabled(enabled: Boolean): CloudToggleResult {
        if (enabled) {
            if (!endpoints.isConfigured) return CloudToggleResult.NOT_CONFIGURED
            if (auth.authState.value !is AuthState.SignedIn) return CloudToggleResult.SIGN_IN_REQUIRED
        }
        preferences.updateUserPreferences { it.copy(cloudSyncEnabled = enabled, backupMedia = if (enabled) it.backupMedia else false) }
        if (!enabled) statusStore.setIssue(null)
        return CloudToggleResult.OK
    }

    suspend fun setWifiOnly(wifiOnly: Boolean) {
        preferences.updateUserPreferences { it.copy(syncOverWifiOnly = wifiOnly) }
        scheduler.wifiOnly = wifiOnly
        if (isActive) {
            scheduler.schedulePeriodic()
            requestSync("wifi-only")
        }
    }

    suspend fun setBackupMedia(enabled: Boolean): CloudToggleResult {
        if (enabled) {
            if (!endpoints.isConfigured) return CloudToggleResult.NOT_CONFIGURED
            if (auth.authState.value !is AuthState.SignedIn) return CloudToggleResult.SIGN_IN_REQUIRED
            if (!entitlements.has(ProFeature.CLOUD_MEDIA_BACKUP)) return CloudToggleResult.REQUIRES_PRO
        }
        preferences.updateUserPreferences { it.copy(backupMedia = enabled) }
        if (enabled) scheduler.scheduleMediaBackup() else scheduler.cancelMediaBackup()
        return CloudToggleResult.OK
    }

    private companion object {
        const val TAG = "SyncController"
        const val DEBOUNCE_MS = 3_000L
    }
}
