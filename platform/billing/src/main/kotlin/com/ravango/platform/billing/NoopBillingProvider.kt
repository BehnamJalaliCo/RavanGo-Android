package com.ravango.platform.billing

import android.app.Activity
import com.ravango.core.common.result.ErrorKind
import com.ravango.core.common.result.Outcome
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Billing for distributions without an integrated store (Cafe Bazaar, Myket, direct APK). Purchases are available
 * in the Google Play version; Bazaar/Myket require their own SDKs (Poolakey / Myket IAB) implemented behind
 * [BillingProvider]. Entitlements granted server-side (e.g. purchases made on another device) still apply.
 */
class NoopBillingProvider(val distribution: String) : BillingProvider {
    override val storeName: String = when (distribution.lowercase()) {
        "bazaar" -> "Cafe Bazaar"
        "myket" -> "Myket"
        else -> distribution
    }
    override val availability: StateFlow<BillingAvailability> =
        MutableStateFlow<BillingAvailability>(BillingAvailability.Unavailable(UnavailableReason.STORE_NOT_SUPPORTED, distribution)).asStateFlow()
    override val purchases: StateFlow<List<PurchaseRecord>> = MutableStateFlow(emptyList<PurchaseRecord>()).asStateFlow()
    override val events: SharedFlow<PurchaseEvent> = MutableSharedFlow<PurchaseEvent>().asSharedFlow()

    private val unsupported = Outcome.Failure(ErrorKind.NOT_CONFIGURED, "store billing not integrated for '$distribution'")

    override suspend fun connect(): Boolean = false
    override suspend fun queryProducts(subscriptionIds: List<String>, oneTimeIds: List<String>): Outcome<List<StoreProduct>> = unsupported
    override suspend fun launchPurchase(activity: Activity, product: StoreProduct, offer: StoreOffer?, obfuscatedAccountId: String?): Outcome<Unit> = unsupported
    override suspend fun restore(): Outcome<List<PurchaseRecord>> = unsupported
    override suspend fun acknowledge(purchase: PurchaseRecord): Outcome<Unit> = unsupported
    override suspend fun consume(purchase: PurchaseRecord): Outcome<Unit> = unsupported
    override fun manageSubscriptionUrl(productId: String?): String? = null
}
