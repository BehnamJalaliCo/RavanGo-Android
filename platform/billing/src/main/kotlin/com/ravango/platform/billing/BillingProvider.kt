package com.ravango.platform.billing

import android.app.Activity
import com.ravango.core.common.result.Outcome
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class ProductType { SUBSCRIPTION, ONE_TIME }

/** One pricing phase of an offer (e.g. "7 days free", then "€29.99 / year"). */
data class PricePhase(
    val formattedPrice: String,
    val priceMicros: Long,
    val currencyCode: String,
    /** ISO-8601 period, e.g. P1M, P1Y, P7D. Empty for one-time products. */
    val billingPeriod: String,
    val billingCycleCount: Int,
    val recurring: Boolean,
) {
    val isFree: Boolean get() = priceMicros == 0L
}

data class StoreOffer(
    val productId: String,
    val basePlanId: String?,
    val offerId: String?,
    /** Opaque token needed to launch the purchase (subscriptions). */
    val offerToken: String?,
    val phases: List<PricePhase>,
    val tags: List<String> = emptyList(),
) {
    val freeTrialPhase: PricePhase? get() = phases.firstOrNull { it.isFree }
    /** The price the user pays after any intro phases. */
    val fullPricePhase: PricePhase? get() = phases.lastOrNull { !it.isFree } ?: phases.lastOrNull()
}

data class StoreProduct(
    val productId: String,
    val type: ProductType,
    val title: String,
    val description: String,
    val offers: List<StoreOffer>,
)

enum class PurchaseState { PURCHASED, PENDING, UNKNOWN }

data class PurchaseRecord(
    val productIds: List<String>,
    val purchaseToken: String,
    val orderId: String?,
    val state: PurchaseState,
    val acknowledged: Boolean,
    val autoRenewing: Boolean,
    val purchaseTime: Long,
    val quantity: Int,
    val packageName: String,
)

enum class BillingFailure { BILLING_UNAVAILABLE, NETWORK, ITEM_UNAVAILABLE, DEVELOPER_ERROR, NOT_CONNECTED, UNKNOWN }

/** Results of a purchase flow, delivered asynchronously by the store. */
sealed interface PurchaseEvent {
    data class Purchased(val record: PurchaseRecord) : PurchaseEvent
    /** Payment is pending (e.g. cash / carrier billing); access is granted once it completes. */
    data class Pending(val productIds: List<String>) : PurchaseEvent
    data object Cancelled : PurchaseEvent
    data object AlreadyOwned : PurchaseEvent
    data class Failed(val reason: BillingFailure, val debugMessage: String? = null) : PurchaseEvent
}

enum class UnavailableReason {
    /** This build's distribution channel has no store billing integrated (e.g. Bazaar/Myket/direct APK). */
    STORE_NOT_SUPPORTED,
    /** Google Play Billing is not available on this device (no Play Store / outdated / not signed in). */
    PLAY_BILLING_UNAVAILABLE,
    /** Could not reach the store right now. */
    SERVICE_UNAVAILABLE,
}

sealed interface BillingAvailability {
    data object Connecting : BillingAvailability
    data object Available : BillingAvailability
    data class Unavailable(val reason: UnavailableReason, val detail: String? = null) : BillingAvailability
}

/**
 * Store abstraction. Google Play Billing 8 is implemented; Cafe Bazaar (Poolakey) and Myket (Myket IAB) plug in
 * behind the same interface, selected by `AppConfig.distribution`.
 */
interface BillingProvider {
    /** Display name of the store ("Google Play"). */
    val storeName: String
    val availability: StateFlow<BillingAvailability>
    /** Active (owned or pending) purchases as last reported by the store. */
    val purchases: StateFlow<List<PurchaseRecord>>
    val events: SharedFlow<PurchaseEvent>

    suspend fun connect(): Boolean
    suspend fun queryProducts(subscriptionIds: List<String>, oneTimeIds: List<String>): Outcome<List<StoreProduct>>

    /** Opens the store's purchase UI; the result arrives on [events]. */
    suspend fun launchPurchase(activity: Activity, product: StoreProduct, offer: StoreOffer?, obfuscatedAccountId: String?): Outcome<Unit>

    /** Re-reads owned purchases from the store (also used for "Restore purchases"). */
    suspend fun restore(): Outcome<List<PurchaseRecord>>
    suspend fun acknowledge(purchase: PurchaseRecord): Outcome<Unit>
    suspend fun consume(purchase: PurchaseRecord): Outcome<Unit>

    /** Deep link to the store's subscription management page. */
    fun manageSubscriptionUrl(productId: String?): String?
}
