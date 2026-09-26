package com.ravango.app

import com.ravango.core.common.di.ApplicationScope
import com.ravango.core.common.log.RgLog
import com.ravango.core.common.startup.StartupTask
import com.ravango.core.datastore.PreferencesDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Lightweight, non-blocking startup: heavy work is deferred to first use so cold start stays fast. */
@Singleton
class AppStartup @Inject constructor(
    @ApplicationScope private val scope: CoroutineScope,
    private val preferences: PreferencesDataSource,
    private val tasks: Set<@JvmSuppressWildcards StartupTask>,
) {
    fun run() {
        scope.launch { preferences.currentUserPreferences() } // warm the DataStore
        tasks.sortedBy { it.priority }.forEach { task ->
            scope.launch {
                runCatching { task.run() }.onFailure { RgLog.e("Startup", "${task::class.simpleName} failed", it) }
            }
        }
    }
}
