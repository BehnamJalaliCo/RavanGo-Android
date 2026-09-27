package com.ravango.platform.billing.bazaar

import android.app.Activity
import android.content.Context
import androidx.activity.ComponentActivity
import com.ravango.core.common.AppConfig
import com.ravango.core.common.log.RgLog
import com.ravango.core.common.result.ErrorKind
import com.ravango.core.common.result.Outcome
import com.ravango.platform.billing.BillingAvailability
import com.ravango.platform.billing.BillingFailure
import com.ravango.platform.billing.BillingProvider
import com.ravango.platform.billing.PricePhase
import com.ravango.platform.billing.ProductType
import com.ravango.platform.billing.PurchaseEvent
import com.ravango.platform.billing.PurchaseRecord
import com.ravango.platform.billing.PurchaseState
import com.ravango.platform.billing.StoreOffer
import com.ravango.platform.billing.StoreProduct
import com.ravango.platform.billing.UnavailableReason
import com.ravango.platform.billing.config.MonetizationConfigRepository
import com.ravango.platform.billing.config.ProductIds
import dagger.hilt.android.qualifiers.ApplicationContext
import ir.cafebazaar.poolakey.Connection
import ir.cafebazaar.poolakey.ConnectionState
import ir.cafebazaar.poolakey.Payment
import ir.cafebazaar.poolakey.config.PaymentConfiguration
import ir.cafebazaar.poolakey.config.SecurityCheck
import ir.cafebazaar.poolakey.entity.PurchaseInfo
import ir.cafebazaar.poolakey.entity.SkuDetails
import ir.cafebazaar.poolakey.exception.BazaarNotFoundException
import ir.cafebazaar.poolakey.exception.BazaarNotSupportedException
import ir.cafebazaar.poolakey.exception.IAPNotSupportedException
import ir.cafebazaar.poolakey.exception.SubsNotSupportedException
import ir.cafebazaar.poolakey.request.PurchaseRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Cafe Bazaar in-app billing through Poolakey. Bazaar has no subscription base plans, so the monthly and yearly
 * SKUs ([ProductIds.bazaarMonthlySku] / [ProductIds.bazaarYearlySku]) are presented to the rest of the app as the
 * two base plans of [ProductIds.subscriptionId] — the paywall, entitlement and credit-pack logic stay store-agnostic.
 * Purchases are signature-checked locally with the app's RSA key from the Bazaar developer panel.
 */
@Singleton
class BazaarBillingProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    appConfig: AppConfig,
    private val monetization: MonetizationConfigRepository,
) : BillingProvider {

    override val storeName: String = "Cafe Bazaar"

    private val _availability = MutableStateFlow<BillingAvailability>(BillingAvailability.Connecting)
    override val availability: StateFlow<BillingAvailability> = _availability.asStateFlow()

    private val _purchases = MutableStateFlow<List<PurchaseRecord>>(emptyList())
    override val purchases: StateFlow<List<PurchaseRecord>> = _purchases.asStateFlow()

    private val _events = MutableSharedFlow<PurchaseEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<PurchaseEvent> = _events.asSharedFlow()

    private val payment = Payment(context, PaymentConfiguration(SecurityCheck.Enable(appConfig.bazaarRsaPublicKey)))
    private var connection: Connection? = null
    private val connectMutex = Mutex()

    private val ids: ProductIds get() = monetization.config.value.products

    override suspend fun connect(): Boolean = connectMutex.withLock {
        if (connection?.getState() == ConnectionState.Connected) {
            _availability.value = BillingAvailability.Available
            return@withLock true
        }
        _availability.value = BillingAvailability.Connecting
        val failure: Throwable? = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                connection = payment.connect {
                    connectionSucceed { if (cont.isActive) cont.resume(null) }
                    connectionFailed { t -> if (cont.isActive) cont.resume(t) }
                    disconnected {
                        RgLog.w(TAG, "Bazaar billing disconnected")
                        if (cont.isActive) cont.resume(IllegalStateException("disconnected")) else connection = null
                    }
                }
            }
        }
        if (failure == null) {
            _availability.value = BillingAvailability.Available
            true
        } else {
            RgLog.w(TAG, "Bazaar billing unavailable", failure)
            connection = null
            _availability.value = BillingAvailability.Unavailable(failure.toUnavailableReason(), failure.message)
            false
        }
    }

    override suspend fun queryProducts(subscriptionIds: List<String>, oneTimeIds: List<String>): Outcome<List<StoreProduct>> {
        if (!connect()) return Outcome.Failure(ErrorKind.NOT_SUPPORTED, "Bazaar billing unavailable")
        val ids = ids
        val products = mutableListOf<StoreProduct>()
        if (ids.subscriptionId in subscriptionIds) {
            val skus = listOf(ids.bazaarMonthlySku, ids.bazaarYearlySku)
            when (val details = skuDetails(skus, subscription = true)) {
                is Outcome.Failure -> return details
                is Outcome.Success -> subscriptionProduct(details.value, ids)?.let { products += it }
            }
        }
        if (oneTimeIds.isNotEmpty()) {
            when (val details = skuDetails(oneTimeIds.distinct(), subscription = false)) {
                is Outcome.Failure -> return details
                is Outcome.Success -> details.value.forEach { products += it.toOneTimeProduct() }
            }
        }
        return Outcome.Success(products)
    }

    override suspend fun launchPurchase(activity: Activity, product: StoreProduct, offer: StoreOffer?, obfuscatedAccountId: String?): Outcome<Unit> {
        if (!connect()) return Outcome.Failure(ErrorKind.NOT_SUPPORTED, "Bazaar billing unavailable")
        val registry = (activity as? ComponentActivity)?.activityResultRegistry
            ?: return Outcome.Failure(ErrorKind.NOT_SUPPORTED, "purchase needs a ComponentActivity")
        val subscription = product.type == ProductType.SUBSCRIPTION
        val sku = if (subscription) offer?.offerToken ?: return Outcome.Failure(ErrorKind.INVALID_INPUT, "no plan selected") else product.productId
        val request = PurchaseRequest(sku, obfuscatedAccountId.orEmpty(), null)
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val callback: ir.cafebazaar.poolakey.callback.PurchaseCallback.() -> Unit = {
                    purchaseFlowBegan { if (cont.isActive) cont.resume(Outcome.Success(Unit)) }
                    failedToBeginFlow { t ->
                        RgLog.w(TAG, "could not start Bazaar purchase for $sku", t)
                        if (cont.isActive) cont.resume(Outcome.Failure(ErrorKind.NOT_SUPPORTED, t.message, t))
                    }
                    purchaseSucceed { info -> onPurchased(info) }
                    purchaseCanceled { _events.tryEmit(PurchaseEvent.Cancelled) }
                    purchaseFailed { t ->
                        RgLog.w(TAG, "Bazaar purchase failed for $sku", t)
                        _events.tryEmit(PurchaseEvent.Failed(t.toFailure(), t.message))
                    }
                }
                try {
                    if (subscription) payment.subscribeProduct(registry, request, callback) else payment.purchaseProduct(registry, request, callback)
                } catch (e: Exception) {
                    RgLog.w(TAG, "Bazaar purchase threw for $sku", e)
                    if (cont.isActive) cont.resume(Outcome.Failure(ErrorKind.UNKNOWN, e.message, e))
                }
            }
        }
    }

    override suspend fun restore(): Outcome<List<PurchaseRecord>> {
        if (!connect()) return Outcome.Failure(ErrorKind.NOT_SUPPORTED, "Bazaar billing unavailable")
        val ids = ids
        val owned = query(subscription = false)
        if (owned is Outcome.Failure) return owned
        // Subscription support depends on the installed Bazaar version; one-time purchases still restore without it.
        val subscribed = query(subscription = true)
        if (subscribed is Outcome.Failure) RgLog.w(TAG, "subscription query failed: ${subscribed.message}")
        val all = ((owned as Outcome.Success).value + ((subscribed as? Outcome.Success)?.value ?: emptyList()))
            .map { mapPurchase(it, ids) }
            .filter { it.state == PurchaseState.PURCHASED }
        _purchases.value = all
        return Outcome.Success(all)
    }

    /** Bazaar has no acknowledgement step; purchases are final once the store reports them. */
    override suspend fun acknowledge(purchase: PurchaseRecord): Outcome<Unit> {
        _purchases.value = _purchases.value.map { if (it.purchaseToken == purchase.purchaseToken) it.copy(acknowledged = true) else it }
        return Outcome.Success(Unit)
    }

    override suspend fun consume(purchase: PurchaseRecord): Outcome<Unit> {
        if (!connect()) return Outcome.Failure(ErrorKind.NETWORK, "Bazaar billing unavailable")
        val failure: Throwable? = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                payment.consumeProduct(purchase.purchaseToken) {
                    consumeSucceed { if (cont.isActive) cont.resume(null) }
                    consumeFailed { t -> if (cont.isActive) cont.resume(t) }
                }
            }
        }
        return if (failure == null) {
            _purchases.value = _purchases.value.filterNot { it.purchaseToken == purchase.purchaseToken }
            Outcome.Success(Unit)
        } else {
            Outcome.Failure(ErrorKind.NETWORK, failure.message, failure)
        }
    }

    /** The app's page on Cafe Bazaar (opens in the Bazaar app when installed), where subscriptions are managed. */
    override fun manageSubscriptionUrl(productId: String?): String = "https://cafebazaar.ir/app/${context.packageName}"

    private fun onPurchased(info: PurchaseInfo) {
        val record = mapPurchase(info, ids)
        if (record.state != PurchaseState.PURCHASED) return
        val byToken = _purchases.value.associateBy { it.purchaseToken }.toMutableMap()
        byToken[record.purchaseToken] = record
        _purchases.value = byToken.values.toList()
        _events.tryEmit(PurchaseEvent.Purchased(record))
    }

    private suspend fun skuDetails(skus: List<String>, subscription: Boolean): Outcome<List<SkuDetails>> = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val callback: ir.cafebazaar.poolakey.callback.GetSkuDetailsCallback.() -> Unit = {
                getSkuDetailsSucceed { list -> if (cont.isActive) cont.resume(Outcome.Success(list)) }
                getSkuDetailsFailed { t -> if (cont.isActive) cont.resume(Outcome.Failure(t.toErrorKind(), t.message, t)) }
            }
            if (subscription) payment.getSubscriptionSkuDetails(skus, callback) else payment.getInAppSkuDetails(skus, callback)
        }
    }

    private suspend fun query(subscription: Boolean): Outcome<List<PurchaseInfo>> = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val callback: ir.cafebazaar.poolakey.callback.PurchaseQueryCallback.() -> Unit = {
                querySucceed { list -> if (cont.isActive) cont.resume(Outcome.Success(list)) }
                queryFailed { t -> if (cont.isActive) cont.resume(Outcome.Failure(t.toErrorKind(), t.message, t)) }
            }
            if (subscription) payment.getSubscribedProducts(callback) else payment.getPurchasedProducts(callback)
        }
    }

    private fun mapPurchase(info: PurchaseInfo, ids: ProductIds) = PurchaseRecord(
        productIds = listOf(appProductId(info.productId, ids)),
        purchaseToken = info.purchaseToken,
        orderId = info.orderId,
        state = if (info.purchaseState == ir.cafebazaar.poolakey.entity.PurchaseState.PURCHASED) PurchaseState.PURCHASED else PurchaseState.UNKNOWN,
        acknowledged = true,
        autoRenewing = isSubscriptionSku(info.productId, ids),
        purchaseTime = info.purchaseTime,
        quantity = 1,
        packageName = info.packageName,
    )

    private fun SkuDetails.toOneTimeProduct(): StoreProduct {
        val phase = pricePhase(price, billingPeriod = "", recurring = false)
        return StoreProduct(sku, ProductType.ONE_TIME, title, description, listOf(StoreOffer(sku, null, null, null, listOf(phase))))
    }

    private fun Throwable.toUnavailableReason(): UnavailableReason = when (this) {
        is BazaarNotFoundException, is BazaarNotSupportedException, is IAPNotSupportedException -> UnavailableReason.STORE_APP_UNAVAILABLE
        else -> UnavailableReason.SERVICE_UNAVAILABLE
    }

    private fun Throwable.toErrorKind(): ErrorKind = when (this) {
        is BazaarNotFoundException, is BazaarNotSupportedException, is IAPNotSupportedException, is SubsNotSupportedException -> ErrorKind.NOT_SUPPORTED
        else -> ErrorKind.NETWORK
    }

    private fun Throwable.toFailure(): BillingFailure = when (this) {
        is BazaarNotFoundException, is BazaarNotSupportedException, is IAPNotSupportedException, is SubsNotSupportedException -> BillingFailure.BILLING_UNAVAILABLE
        else -> BillingFailure.UNKNOWN
    }

    companion object {
        private const val TAG = "BazaarBilling"

        fun isSubscriptionSku(sku: String, ids: ProductIds): Boolean = sku == ids.bazaarMonthlySku || sku == ids.bazaarYearlySku

        /** Maps a Bazaar SKU to the app's product id (both subscription SKUs become [ProductIds.subscriptionId]). */
        fun appProductId(sku: String, ids: ProductIds): String = if (isSubscriptionSku(sku, ids)) ids.subscriptionId else sku

        /** Builds the app's single subscription product (monthly/yearly "base plans") from Bazaar's two SKUs. */
        fun subscriptionProduct(details: List<SkuDetails>, ids: ProductIds): StoreProduct? =
            subscriptionProductFrom(details.map { SkuPrice(it.sku, it.title, it.description, it.price) }, ids)

        internal data class SkuPrice(val sku: String, val title: String, val description: String, val price: String)

        internal fun subscriptionProductFrom(details: List<SkuPrice>, ids: ProductIds): StoreProduct? {
            val offers = listOfNotNull(
                details.firstOrNull { it.sku == ids.bazaarMonthlySku }?.let { d ->
                    StoreOffer(ids.subscriptionId, ids.monthlyBasePlanId, null, d.sku, listOf(pricePhase(d.price, "P1M", recurring = true)))
                },
                details.firstOrNull { it.sku == ids.bazaarYearlySku }?.let { d ->
                    StoreOffer(ids.subscriptionId, ids.yearlyBasePlanId, null, d.sku, listOf(pricePhase(d.price, "P1Y", recurring = true)))
                },
            )
            if (offers.isEmpty()) return null
            val first = details.first { it.sku == offers.first().offerToken }
            return StoreProduct(ids.subscriptionId, ProductType.SUBSCRIPTION, first.title, first.description, offers)
        }

        /**
         * Bazaar only reports a formatted price ("۴۹٬۰۰۰ تومان", "49,000 Toman", "۴۹۰٬۰۰۰ ریال"). The amount is parsed
         * so the paywall can compare plans; when it can't be parsed the price is still shown as-is and never treated
         * as free.
         */
        fun pricePhase(formatted: String, billingPeriod: String, recurring: Boolean): PricePhase {
            val amount = parseAmount(formatted)
            val currency = if (formatted.contains("ریال") || formatted.contains("rial", ignoreCase = true)) "IRR" else "IRT"
            return PricePhase(
                formattedPrice = formatted.trim(),
                priceMicros = amount?.let { it * 1_000_000 } ?: UNKNOWN_PRICE_MICROS,
                currencyCode = if (amount == null) "" else currency,
                billingPeriod = billingPeriod,
                billingCycleCount = 0,
                recurring = recurring,
            )
        }

        fun parseAmount(formatted: String): Long? {
            val digits = buildString {
                for (ch in formatted) {
                    when (ch) {
                        in '0'..'9' -> append(ch)
                        in '۰'..'۹' -> append('0' + (ch - '۰'))
                        in '٠'..'٩' -> append('0' + (ch - '٠'))
                    }
                }
            }
            return digits.takeIf { it.isNotEmpty() && it.length <= 15 }?.toLong()
        }

        /** Non-zero sentinel so an unparsed price never reads as a free trial phase. */
        const val UNKNOWN_PRICE_MICROS = -1L
    }
}
