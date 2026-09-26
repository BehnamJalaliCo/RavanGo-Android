package com.ravango.platform.cloud

import com.ravango.core.datastore.PreferencesDataSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Why the last sync did not complete; the UI shows a localized explanation for each. */
enum class SyncIssue {
    NOT_CONFIGURED,
    SIGN_IN_REQUIRED,
    SESSION_EXPIRED,
    NETWORK,
    SERVER,
    /** Cloud media quota of the current plan is full; media backup paused. */
    QUOTA_EXCEEDED,
    UNKNOWN,
}

/** Progress of the media backup worker. */
data class MediaBackupProgress(
    val running: Boolean = false,
    val filesDone: Int = 0,
    val filesTotal: Int = 0,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
)

/** Shared, process-wide sync bookkeeping written by the workers and read by the controller/UI. */
@Singleton
class SyncStatusStore @Inject constructor(private val preferences: PreferencesDataSource) {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _issue = MutableStateFlow<SyncIssue?>(null)
    val issue: StateFlow<SyncIssue?> = _issue.asStateFlow()

    private val _media = MutableStateFlow(MediaBackupProgress())
    val media: StateFlow<MediaBackupProgress> = _media.asStateFlow()

    private val _mediaIssue = MutableStateFlow<SyncIssue?>(null)
    val mediaIssue: StateFlow<SyncIssue?> = _mediaIssue.asStateFlow()

    val lastSyncedAt: Flow<Long?> = preferences.observeString(KEY_LAST_SYNC).map { it?.toLongOrNull() }

    fun setRunning(value: Boolean) { _running.value = value }
    fun setIssue(value: SyncIssue?) { _issue.value = value }
    fun setMedia(progress: MediaBackupProgress) { _media.value = progress }
    fun setMediaIssue(value: SyncIssue?) { _mediaIssue.value = value }

    suspend fun markSucceeded(at: Long) {
        _issue.value = null
        preferences.putString(KEY_LAST_SYNC, at.toString())
    }

    suspend fun reset() {
        _issue.value = null
        _mediaIssue.value = null
        _media.value = MediaBackupProgress()
        preferences.putString(KEY_LAST_SYNC, null)
    }

    private companion object {
        const val KEY_LAST_SYNC = "cloud.lastSyncedAt"
    }
}
