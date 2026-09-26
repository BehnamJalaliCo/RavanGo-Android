package com.ravango.engine.ai.di

import com.ravango.engine.ai.api.AiCredentialStore
import com.ravango.engine.ai.api.AiTextService
import com.ravango.engine.ai.api.AudioCleanupService
import com.ravango.engine.ai.api.EyeContactService
import com.ravango.engine.ai.api.SpeechToTextService
import com.ravango.engine.ai.api.SubtitleBuilder
import com.ravango.engine.ai.api.VideoIntelligence
import com.ravango.engine.ai.audio.OfflineAudioCleanupService
import com.ravango.engine.ai.config.SecureAiCredentialStore
import com.ravango.engine.ai.eye.GatewayEyeContactService
import com.ravango.engine.ai.speech.DefaultSpeechToTextService
import com.ravango.engine.ai.subtitle.DefaultSubtitleBuilder
import com.ravango.engine.ai.text.DefaultAiTextService
import com.ravango.engine.ai.video.DefaultVideoIntelligence
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** OkHttp client for AI traffic (long read timeouts for streaming and uploads). */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class AiHttpClient

@Module
@InstallIn(SingletonComponent::class)
abstract class AiBindingsModule {
    @Binds abstract fun credentialStore(impl: SecureAiCredentialStore): AiCredentialStore
    @Binds abstract fun textService(impl: DefaultAiTextService): AiTextService
    @Binds abstract fun speechToText(impl: DefaultSpeechToTextService): SpeechToTextService
    @Binds abstract fun subtitleBuilder(impl: DefaultSubtitleBuilder): SubtitleBuilder
    @Binds abstract fun videoIntelligence(impl: DefaultVideoIntelligence): VideoIntelligence
    @Binds abstract fun audioCleanup(impl: OfflineAudioCleanupService): AudioCleanupService
    @Binds abstract fun eyeContact(impl: GatewayEyeContactService): EyeContactService
}

@Module
@InstallIn(SingletonComponent::class)
object AiProvidesModule {
    @Provides
    @Singleton
    @AiHttpClient
    fun aiHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()
}
