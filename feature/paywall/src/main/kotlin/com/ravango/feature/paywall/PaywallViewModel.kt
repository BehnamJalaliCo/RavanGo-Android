package com.ravango.feature.paywall

import android.app.Activity
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.ravango.core.common.AppConfig
import com.ravango.core.common.result.ErrorKind
import com.ravango.core.common.result.Outcome
import com.ravango.core.model.Entitlements
import com.ravango.core.model.Plan
import com.ravango.core.model.ProFeature
import com.ravango.core.model.service.EntitlementProvider
import com.ravango.core.navigation.PaywallRoute
import com.ravango.platform.billing.BillingAvailability
import com.ravango.platform.billing.BillingFailure
import com.ravango.platform.billing.BillingNotice
import com.ravango.platform.billing.BillingRepository
import com.ravango.platform.billing.PackOffer
import com.ravango.platform.billing.PaywallCatalog
import com.ravango.platform.billing.PlanChoice
import com.ravango.platform.billing.config.MonetizationConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PaywallUiState(
    val feature: ProFeature? = null,
    val source: String = "",
    val entitlements: Entitlements = Entitlements(),
    val config: MonetizationConfig = MonetizationConfig.Default,
    val availability: BillingAvailability = BillingAvailability.Connecting,
    val storeName: String = "",
    val distribution: String = "play",
    val loadingCatalog: Boolean = true,
    val catalog: PaywallCatalog? = null,
    val catalogError: ErrorKind? = null,
    val selected: PlanChoice = PlanChoice.YEARLY,
    val purchasing: Boolean = false,
    val restoring: Boolean = false,
    val termsUrl: String = "",
    val privacyUrl: String = "",
    val canManageSubscription: Boolean = false,
) {
    val isPaid: Boolean get() = entitlements.plan != Plan.FREE
}

/** One-off messages shown in a snackbar (or closing the paywall on success). */
sealed interface PaywallMessage {
    data class Activated(val plan: Plan) : PaywallMessage
    data class CreditsAdded(val credits: Int) : PaywallMessage
    data object Pending : PaywallMessage
    data object Cancelled : PaywallMessage
    data object AlreadyOwned : PaywallMessage
    data class Failed(val reason: BillingFailure?) : PaywallMessage
    data class Error(val kind: ErrorKind) : PaywallMessage
    data class Restored(val plan: Plan) : PaywallMessage
}

@HiltViewModel
class PaywallViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val billing: BillingRepository,
    entitlementProvider: EntitlementProvider,
    appConfig: AppConfig,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<PaywallRoute>()

    private val _state = MutableStateFlow(
        PaywallUiState(
            feature = route.feature?.let { name -> ProFeature.entries.firstOrNull { it.name == name } },
            source = route.source,
            storeName = billing.storeName,
            distribution = appConfig.distribution,
            termsUrl = appConfig.termsUrl,
            privacyUrl = appConfig.privacyPolicyUrl,
            canManageSubscription = billing.manageSubscriptionUrl() != null,
        ),
    )
    val state: StateFlow<PaywallUiState> = _state.asStateFlow()

    private val _messages = Channel<PaywallMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        viewModelScope.launch { entitlementProvider.entitlements.collect { e -> _state.update { it.copy(entitlements = e) } } }
        viewModelScope.launch { billing.config.collect { c -> _state.update { it.copy(config = c) } } }
        viewModelScope.launch { billing.availability.collect { a -> _state.update { it.copy(availability = a) } } }
        viewModelScope.launch { billing.notices.collect(::onNotice) }
        loadCatalog()
    }

    fun loadCatalog() {
        viewModelScope.launch {
            _state.update { it.copy(loadingCatalog = true, catalogError = null) }
            when (val result = billing.loadCatalog()) {
                is Outcome.Success -> _state.update { s ->
                    val catalog = result.value
                    val selected = when {
                        catalog.offer(s.selected) != null -> s.selected
                        catalog.yearly != null -> PlanChoice.YEARLY
                        catalog.monthly != null -> PlanChoice.MONTHLY
                        else -> PlanChoice.LIFETIME
                    }
                    s.copy(loadingCatalog = false, catalog = catalog, selected = selected)
                }
                is Outcome.Failure -> _state.update { it.copy(loadingCatalog = false, catalogError = result.kind) }
            }
        }
    }

    fun select(choice: PlanChoice) = _state.update { it.copy(selected = choice) }

    fun purchase(activity: Activity) {
        if (_state.value.purchasing) return
        viewModelScope.launch {
            _state.update { it.copy(purchasing = true) }
            val result = billing.purchasePlan(activity, _state.value.selected)
            if (result is Outcome.Failure) {
                _state.update { it.copy(purchasing = false) }
                if (result.kind != ErrorKind.CANCELLED) _messages.send(PaywallMessage.Error(result.kind))
            }
            // On success the store UI is showing; the outcome arrives as a BillingNotice.
        }
    }

    fun purchasePack(activity: Activity, pack: PackOffer) {
        if (_state.value.purchasing) return
        viewModelScope.launch {
            _state.update { it.copy(purchasing = true) }
            val result = billing.purchasePack(activity, pack)
            if (result is Outcome.Failure) {
                _state.update { it.copy(purchasing = false) }
                if (result.kind != ErrorKind.CANCELLED) _messages.send(PaywallMessage.Error(result.kind))
            }
        }
    }

    fun restore() {
        if (_state.value.restoring) return
        viewModelScope.launch {
            _state.update { it.copy(restoring = true) }
            val message = when (val result = billing.restore()) {
                is Outcome.Success -> PaywallMessage.Restored(result.value)
                is Outcome.Failure -> PaywallMessage.Error(result.kind)
            }
            _state.update { it.copy(restoring = false) }
            _messages.send(message)
        }
    }

    fun manageSubscriptionUrl(): String? = billing.manageSubscriptionUrl()

    private suspend fun onNotice(notice: BillingNotice) {
        _state.update { it.copy(purchasing = false) }
        _messages.send(
            when (notice) {
                is BillingNotice.PlanActivated -> PaywallMessage.Activated(notice.plan)
                is BillingNotice.CreditsAdded -> PaywallMessage.CreditsAdded(notice.credits)
                is BillingNotice.Pending -> PaywallMessage.Pending
                BillingNotice.Cancelled -> PaywallMessage.Cancelled
                BillingNotice.AlreadyOwned -> PaywallMessage.AlreadyOwned
                is BillingNotice.Failed -> PaywallMessage.Failed(notice.reason)
            },
        )
    }
}
