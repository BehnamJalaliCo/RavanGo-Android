package com.ravango.engine.camera.di

import com.ravango.core.common.startup.StartupTask
import com.ravango.engine.camera.Camera2Engine
import com.ravango.engine.camera.CameraEngine
import com.ravango.engine.camera.recovery.RecordingRecoveryStartupTask
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class CameraEngineModule {
    @Binds abstract fun cameraEngine(impl: Camera2Engine): CameraEngine

    @Binds @IntoSet abstract fun recordingRecovery(task: RecordingRecoveryStartupTask): StartupTask
}
