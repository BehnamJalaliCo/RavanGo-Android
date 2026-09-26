package com.ravango.core.common.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
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

    @Provides @Singleton @ApplicationScope
    fun applicationScope(@DefaultDispatcher dispatcher: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatcher)

    @Provides fun clock(): com.ravango.core.model.Clock = com.ravango.core.model.Clock.System
}
