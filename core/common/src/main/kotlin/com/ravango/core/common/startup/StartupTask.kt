package com.ravango.core.common.startup

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/**
 * Background work to start at app launch (sync observers, billing refresh, cleanup). Contributed with
 * `@Binds @IntoSet` from any module; executed asynchronously so it never delays the first frame.
 */
interface StartupTask {
    /** Lower runs first. */
    val priority: Int get() = 100
    suspend fun run()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class StartupTaskModule {
    @Multibinds abstract fun startupTasks(): Set<StartupTask>
}
