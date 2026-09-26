package com.ravango.engine.audio.di

import com.ravango.engine.audio.AndroidAudioEngine
import com.ravango.engine.audio.AudioEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AudioEngineModule {
    @Binds
    @Singleton
    abstract fun bindAudioEngine(impl: AndroidAudioEngine): AudioEngine
}
