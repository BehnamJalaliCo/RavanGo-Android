package com.ravango.core.common.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import com.ravango.core.common.log.RgLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class IoDispatcher
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class DefaultDispatcher
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class MainDispatcher

/** Process-lifetime scope for work that must outlive a screen (saving, syncing, exporting). */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {
    @Provides @IoDispatcher fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO
    @Provides @DefaultDispatcher fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default
    @Provides @MainDispatcher fun mainDispatcher(): CoroutineDispatcher = Dispatchers.Main

    /**
     * A failure in one app-scope job (a save, a sync, a camera shutdown) is logged to the on-device diagnostics and
     * never takes the whole process down: without a handler an uncaught exception here crashes the app.
     */
    @Provides @Singleton @ApplicationScope
    fun applicationScope(@DefaultDispatcher dispatcher: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatcher + AppScopeExceptionHandler)

    @Provides fun clock(): com.ravango.core.model.Clock = com.ravango.core.model.Clock.System
}

/** Logs (and records as a non-fatal diagnostics event, see `RgLog.sink`) instead of crashing the process. */
val AppScopeExceptionHandler: CoroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
    RgLog.e("AppScope", "Uncaught failure in a background job (process kept alive)", throwable)
}
