package com.ravango.app

import com.ravango.core.common.AppConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun appConfig(): AppConfig = AppConfig(
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        isDebug = BuildConfig.DEBUG,
        distribution = BuildConfig.DISTRIBUTION,
        supabaseUrl = BuildConfig.SUPABASE_URL.trimEnd('/'),
        supabaseAnonKey = BuildConfig.SUPABASE_ANON_KEY,
        googleWebClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID,
        aiGatewayUrl = BuildConfig.AI_GATEWAY_URL.trimEnd('/'),
        privacyPolicyUrl = BuildConfig.PRIVACY_URL,
        termsUrl = BuildConfig.TERMS_URL,
        supportEmail = BuildConfig.SUPPORT_EMAIL,
    )
}
