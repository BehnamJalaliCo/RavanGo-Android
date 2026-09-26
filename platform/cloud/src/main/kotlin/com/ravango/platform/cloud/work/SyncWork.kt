package com.ravango.platform.cloud.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ravango.platform.cloud.SyncEngine
import com.ravango.platform.cloud.SyncRunResult
import com.ravango.platform.cloud.media.MediaBackupEngine
import com.ravango.platform.cloud.media.MediaBackupResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Data sync (settings + rows). Retries with exponential backoff on network/server errors. */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val engine: SyncEngine,
    private val scheduler: SyncScheduler,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val reason = inputData.getString(KEY_REASON) ?: "worker"
        return when (val result = engine.sync(reason)) {
            is SyncRunResult.Success -> {
                scheduler.scheduleMediaBackup()
                Result.success()
            }
            is SyncRunResult.Skipped -> Result.success()
            is SyncRunResult.Failed -> if (result.retryable && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_REASON = "reason"
        const val MAX_ATTEMPTS = 6
    }
}

/** Media upload/restore. Long-running, resumable; kept separate so data edits never restart an upload. */
@HiltWorker
class MediaBackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val engine: MediaBackupEngine,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = when (val result = engine.run()) {
        is MediaBackupResult.Done, is MediaBackupResult.Skipped -> Result.success()
        is MediaBackupResult.Failed -> if (result.retryable && runAttemptCount < SyncWorker.MAX_ATTEMPTS) Result.retry() else Result.failure()
    }
}

/** Enqueues sync work with the right constraints (network, Wi-Fi only) and backoff. */
@Singleton
class SyncScheduler @Inject constructor(@ApplicationContext private val context: Context) {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    @Volatile var wifiOnly: Boolean = true

    private fun constraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .build()

    /** Replaces any queued-but-not-started sync so rapid edits coalesce; a running sync is restarted (idempotent). */
    fun enqueueSync(reason: String, expedite: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(if (expedite && !wifiOnly) Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build() else constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(SyncWorker.KEY_REASON to reason))
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(UNIQUE_SYNC, ExistingWorkPolicy.REPLACE, request)
    }

    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS, 30, TimeUnit.MINUTES)
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .setInputData(workDataOf(SyncWorker.KEY_REASON to "periodic"))
            .addTag(TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Media backup always waits for an unmetered network when "Wi-Fi only" is on; KEEP never interrupts an upload. */
    fun scheduleMediaBackup() {
        val request = OneTimeWorkRequestBuilder<MediaBackupWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(UNIQUE_MEDIA, ExistingWorkPolicy.KEEP, request)
    }

    fun cancelAll() {
        workManager.cancelUniqueWork(UNIQUE_SYNC)
        workManager.cancelUniqueWork(UNIQUE_PERIODIC)
        workManager.cancelUniqueWork(UNIQUE_MEDIA)
    }

    fun cancelMediaBackup() = workManager.cancelUniqueWork(UNIQUE_MEDIA)

    /** True while a one-time sync is queued waiting for constraints (e.g. no Wi-Fi). */
    fun observeSyncWaiting(): Flow<Boolean> = workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_SYNC)
        .map { infos -> infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED } }

    private companion object {
        const val TAG = "ravango-sync"
        const val UNIQUE_SYNC = "ravango-sync-once"
        const val UNIQUE_PERIODIC = "ravango-sync-periodic"
        const val UNIQUE_MEDIA = "ravango-media-backup"
    }
}
