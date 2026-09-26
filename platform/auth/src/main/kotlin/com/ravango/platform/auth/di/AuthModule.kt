package com.ravango.platform.auth.di

import com.ravango.core.model.service.AuthSessionProvider
import com.ravango.platform.auth.AccountLifecycleListener
import com.ravango.platform.auth.AuthRepository
import com.ravango.platform.auth.SupabaseAuthRepository
import com.ravango.platform.auth.supabase.SupabaseHttp
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AuthBindings {
    @Binds abstract fun authRepository(impl: SupabaseAuthRepository): AuthRepository
    @Binds abstract fun authSessionProvider(impl: SupabaseAuthRepository): AuthSessionProvider
    @Multibinds abstract fun accountListeners(): Set<AccountLifecycleListener>
}

@Module
@InstallIn(SingletonComponent::class)
object SupabaseHttpModule {
    @Provides
    @Singleton
    @SupabaseHttp
    fun supabaseHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}
