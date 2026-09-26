package com.ravango.platform.billing.di

import com.ravango.core.common.AppConfig
import com.ravango.core.common.startup.StartupTask
import com.ravango.core.model.service.EntitlementProvider
import com.ravango.platform.billing.BillingProvider
import com.ravango.platform.billing.BillingRepository
import com.ravango.platform.billing.DefaultEntitlementProvider
import com.ravango.platform.billing.NoopBillingProvider
import com.ravango.platform.billing.play.PlayBillingProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** Connects billing, restores purchases, refreshes the remote monetization config and server entitlement. */
class BillingStartupTask @Inject constructor(private val billing: BillingRepository) : StartupTask {
    override val priority: Int = 60
    override suspend fun run() = billing.refresh()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BillingBindings {
    @Binds abstract fun entitlementProvider(impl: DefaultEntitlementProvider): EntitlementProvider

    @Binds @IntoSet abstract fun startupTask(impl: BillingStartupTask): StartupTask
}

@Module
@InstallIn(SingletonComponent::class)
object BillingProviderModule {
    /** Google Play Billing for the Play build; other channels get a provider that explains why purchases are unavailable. */
    @Provides
    @Singleton
    fun billingProvider(config: AppConfig, play: Provider<PlayBillingProvider>): BillingProvider =
        if (config.distribution.equals(DISTRIBUTION_PLAY, ignoreCase = true)) play.get() else NoopBillingProvider(config.distribution)

    private const val DISTRIBUTION_PLAY = "play"
}
