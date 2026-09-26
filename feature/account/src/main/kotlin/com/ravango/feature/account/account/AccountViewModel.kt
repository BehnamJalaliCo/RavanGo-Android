package com.ravango.feature.account.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ravango.core.common.AppConfig
import com.ravango.core.data.repository.ProjectRepository
import com.ravango.core.model.Entitlements
import com.ravango.core.model.service.AuthState
import com.ravango.core.model.service.EntitlementProvider
import com.ravango.platform.auth.AuthError
import com.ravango.platform.auth.AuthRepository
import com.ravango.platform.auth.AuthResult
import com.ravango.platform.billing.BillingRepository
import com.ravango.platform.cloud.CloudStatus
import com.ravango.platform.cloud.CloudSyncController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AccountUiState(
    val auth: AuthState = AuthState.Unknown,
    val cloudConfigured: Boolean = false,
    val entitlements: Entitlements = Entitlements(),
    val cloud: CloudStatus = CloudStatus(),
    val localMediaBytes: Long = 0,
    val busy: Boolean = false,
    val canManageSubscription: Boolean = false,
    val supportEmail: String = "",
)

sealed interface AccountEvent {
    data class Error(val error: AuthError) : AccountEvent
    data object ProfileSaved : AccountEvent
    data object SignedOut : AccountEvent
    data object AccountDeleted : AccountEvent
}

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val auth: AuthRepository,
    entitlements: EntitlementProvider,
    cloud: CloudSyncController,
    projects: ProjectRepository,
    private val billing: BillingRepository,
    appConfig: AppConfig,
) : ViewModel() {

    private val busy = MutableStateFlow(false)
    private val _events = Channel<AccountEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val state: StateFlow<AccountUiState> = combine(
        auth.authState,
        entitlements.entitlements,
        cloud.status,
        projects.observeStorageBytes(),
        busy,
    ) { a, e, c, bytes, b ->
        AccountUiState(
            auth = a,
            cloudConfigured = auth.isConfigured,
            entitlements = e,
            cloud = c,
            localMediaBytes = bytes,
            busy = b,
            canManageSubscription = billing.manageSubscriptionUrl() != null,
            supportEmail = appConfig.supportEmail,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountUiState(cloudConfigured = auth.isConfigured))

    init {
        // Refresh profile and server entitlement when the screen opens (silently; offline keeps cached data).
        viewModelScope.launch {
            if (auth.authState.value is AuthState.SignedIn) {
                auth.refreshUser()
                billing.refreshServerEntitlement()
            }
        }
    }

    fun manageSubscriptionUrl(): String? = billing.manageSubscriptionUrl()

    fun updateDisplayName(name: String) = run {
        when (val r = auth.updateProfile(name.trim())) {
            is AuthResult.Success -> _events.send(AccountEvent.ProfileSaved)
            is AuthResult.Failure -> _events.send(AccountEvent.Error(r.error))
        }
    }

    fun signOut(wipeLocalData: Boolean) = run {
        auth.signOut(wipeLocalData)
        _events.send(AccountEvent.SignedOut)
    }

    fun deleteAccount(wipeLocalData: Boolean) = run {
        when (val r = auth.deleteAccount(wipeLocalData)) {
            is AuthResult.Success -> _events.send(AccountEvent.AccountDeleted)
            is AuthResult.Failure -> _events.send(AccountEvent.Error(r.error))
        }
    }

    private fun run(block: suspend () -> Unit) {
        if (busy.value) return
        viewModelScope.launch {
            busy.update { true }
            try {
                block()
            } finally {
                busy.update { false }
            }
        }
    }
}
