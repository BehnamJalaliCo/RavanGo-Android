package com.ravango.platform.cloud.di

import com.ravango.core.common.log.RgLog
import com.ravango.core.common.startup.StartupTask
import com.ravango.core.database.dao.SyncCursorDao
import com.ravango.core.datastore.PreferencesDataSource
import com.ravango.core.model.UserAccount
import com.ravango.core.model.service.SyncController
import com.ravango.platform.auth.AccountLifecycleListener
import com.ravango.platform.cloud.CloudSyncController
import com.ravango.platform.cloud.LocalDataManager
import com.ravango.platform.cloud.SyncStatusStore
import com.ravango.platform.cloud.media.MediaBackupEngine
import com.ravango.platform.cloud.settings.SettingsSync
import com.ravango.platform.cloud.work.SyncScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton

/** Starts sync observation (local changes, auth, connectivity) off the main thread at launch. */
class CloudSyncStartupTask @Inject constructor(private val controller: CloudSyncController) : StartupTask {
    override val priority: Int = 50
    override suspend fun run() = controller.start()
}

/**
 * Cloud side of the account lifecycle: deletes backed-up media before an account deletion, stops sync and
 * forgets cursors on sign-out, and erases local data when the user asked for it.
 */
@Singleton
class CloudAccountListener @Inject constructor(
    private val scheduler: SyncScheduler,
    private val cursors: SyncCursorDao,
    private val preferences: PreferencesDataSource,
    private val settingsSync: SettingsSync,
    private val status: SyncStatusStore,
    private val media: MediaBackupEngine,
    private val localData: LocalDataManager,
    private val controller: CloudSyncController,
) : AccountLifecycleListener {

    override suspend fun beforeAccountDeletion(userId: String, accessToken: String) {
        // Throws on failure so the account is not deleted while its files would be orphaned.
        media.deleteAllFor(userId, accessToken)
    }

    override suspend fun onSignedOut(userId: String, wipeLocalData: Boolean) {
        scheduler.cancelAll()
        cursors.clear()
        settingsSync.reset()
        status.reset()
        preferences.updateUserPreferences { it.copy(cloudSyncEnabled = false, backupMedia = false) }
        if (wipeLocalData) localData.wipeLocalData()
        RgLog.i("CloudAccount", "signed out; sync stopped (wipe=$wipeLocalData)")
    }

    override suspend fun onSignedIn(user: UserAccount) {
        controller.requestSync("signed-in")
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CloudModule {
    @Binds abstract fun syncController(impl: CloudSyncController): SyncController

    @Binds @IntoSet abstract fun startupTask(impl: CloudSyncStartupTask): StartupTask

    @Binds @IntoSet abstract fun accountListener(impl: CloudAccountListener): AccountLifecycleListener
}
