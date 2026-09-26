package com.ravango.platform.billing.config

import com.ravango.core.common.di.ApplicationScope
import com.ravango.core.common.di.IoDispatcher
import com.ravango.core.common.log.RgLog
import com.ravango.core.datastore.PreferencesDataSource
import com.ravango.platform.billing.server.EntitlementServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Current [MonetizationConfig]: built-in defaults, replaced by the last remote override cached in DataStore,
 * refreshed from `app_config` (key `monetization`) when Supabase is configured.
 */
@Singleton
class MonetizationConfigRepository @Inject constructor(
    private val preferences: PreferencesDataSource,
    private val server: EntitlementServer,
    @ApplicationScope scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val _config = MutableStateFlow(MonetizationConfig.Default)
    val config: StateFlow<MonetizationConfig> = _config.asStateFlow()

    init {
        scope.launch {
            val cached = preferences.observeString(KEY_CACHE).first()?.let(MonetizationConfig::decode)
            if (cached != null) _config.compareAndSet(MonetizationConfig.Default, cached)
        }
    }

    /** Fetches the remote override. Failures keep the current config. */
    suspend fun refresh() {
        if (!server.isConfigured) return
        try {
            val override = withContext(io) { server.appConfig(REMOTE_KEY) } ?: return
            val merged = MonetizationConfig.applyOverride(MonetizationConfig.Default, override)
            if (merged == null) {
                RgLog.w(TAG, "remote monetization config rejected (invalid)")
                return
            }
            _config.value = merged
            preferences.putString(KEY_CACHE, MonetizationConfig.encode(merged))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RgLog.w(TAG, "monetization config refresh failed", e)
        }
    }

    private companion object {
        const val TAG = "MonetizationConfig"
        const val REMOTE_KEY = "monetization"
        const val KEY_CACHE = "billing.monetization.v1"
    }
}
