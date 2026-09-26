package com.ravango.platform.billing

import com.ravango.core.common.AppConfig
import com.ravango.core.common.di.ApplicationScope
import com.ravango.core.datastore.PreferencesDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Pro test mode" for the owner and testers: unlocks every Pro feature on this device without a purchase, so the whole
 * app can be verified before store products exist. Guarded by a secret code whose SHA-256 is injected at build time
 * ([AppConfig.ownerCodeSha256]); builds without a hash cannot enable it.
 */
@Singleton
class TesterAccess @Inject constructor(
    private val config: AppConfig,
    private val preferences: PreferencesDataSource,
    @ApplicationScope scope: CoroutineScope,
) {
    /** Whether this build can enable tester access at all. */
    val available: Boolean get() = config.ownerCodeSha256.length == 64

    val active: StateFlow<Boolean> = preferences.observeString(KEY)
        .map { it == ENABLED && available }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** Returns true and enables Pro test mode when [code] matches the configured hash. */
    suspend fun unlock(code: String): Boolean {
        if (!available) return false
        val matches = MessageDigest.isEqual(sha256(code.trim()).toByteArray(), config.ownerCodeSha256.lowercase().toByteArray())
        if (matches) preferences.putString(KEY, ENABLED)
        return matches
    }

    suspend fun disable() = preferences.putString(KEY, null)

    companion object {
        private const val KEY = "tester_pro_mode"
        private const val ENABLED = "1"

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
