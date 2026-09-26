package com.ravango.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.ravango.core.common.crash.CrashGuard
import com.ravango.core.common.log.RgLog
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class RavanGoApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var crashGuard: CrashGuard
    @Inject lateinit var startup: AppStartup

    override fun onCreate() {
        super.onCreate()
        RgLog.debugEnabled = BuildConfig.DEBUG
        crashGuard.install()
        startup.run()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.INFO else android.util.Log.ERROR)
            .build()
}
