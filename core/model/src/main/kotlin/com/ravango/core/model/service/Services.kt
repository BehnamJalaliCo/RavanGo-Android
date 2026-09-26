package com.ravango.core.model.service

import com.ravango.core.model.AiOperation
import com.ravango.core.model.Entitlements
import com.ravango.core.model.ProFeature
import com.ravango.core.model.UserAccount
import kotlinx.coroutines.flow.StateFlow

/*
 * Cross-cutting service contracts. Implementations live in platform modules (auth, cloud, billing)
 * and are bound with Hilt; features depend only on these interfaces.
 */

sealed interface AuthState {
    data object Unknown : AuthState
    data object Guest : AuthState
    data class SignedIn(val user: UserAccount) : AuthState
}

interface AuthSessionProvider {
    val authState: StateFlow<AuthState>

    /** A valid access token for backend calls, refreshing if needed; null when signed out or offline. */
    suspend fun accessToken(): String?
}

enum class SyncPhase { DISABLED, IDLE, SYNCING, OFFLINE, ERROR }

data class SyncState(
    val phase: SyncPhase = SyncPhase.DISABLED,
    val lastSyncedAt: Long? = null,
    val pendingChanges: Int = 0,
    val message: String? = null,
)

interface SyncController {
    val state: StateFlow<SyncState>

    /** Schedules a sync as soon as constraints allow. Safe to call often (debounced). */
    fun requestSync(reason: String = "")
}

interface EntitlementProvider {
    val entitlements: StateFlow<Entitlements>

    fun has(feature: ProFeature): Boolean = entitlements.value.has(feature)

    /** Atomically reserves AI credits. Returns false (and consumes nothing) if the balance is insufficient. */
    suspend fun tryConsumeAiCredits(operation: AiOperation, units: Int = 1): Boolean

    /** Refunds credits reserved for an operation that failed. */
    suspend fun refundAiCredits(operation: AiOperation, units: Int = 1)
}
