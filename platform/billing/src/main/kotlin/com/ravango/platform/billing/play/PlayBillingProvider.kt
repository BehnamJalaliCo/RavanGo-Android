package com.ravango.platform.billing.play

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.consumePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.ravango.core.common.di.ApplicationScope
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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Google Play Billing Library 8 implementation. */
@Singleton
class PlayBillingProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
) : BillingProvider {

    override val storeName: String = "Google Play"

    private val _availability = MutableStateFlow<BillingAvailability>(BillingAvailability.Connecting)
    override val availability: StateFlow<BillingAvailability> = _availability.asStateFlow()

    private val _purchases = MutableStateFlow<List<PurchaseRecord>>(emptyList())
    override val purchases: StateFlow<List<PurchaseRecord>> = _purchases.asStateFlow()

    private val _events = MutableSharedFlow<PurchaseEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<PurchaseEvent> = _events.asSharedFlow()

    /** ProductDetails are needed to launch a purchase; cached from the last query. */
    private val details = ConcurrentHashMap<String, ProductDetails>()
    private val connectMutex = Mutex()

    private val listener = PurchasesUpdatedListener { result, purchaseList -> onPurchasesUpdated(result, purchaseList) }

    private val client: BillingClient = BillingClient.newBuilder(context)
        .setListener(listener)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().enablePrepaidPlans().build())
        .enableAutoServiceReconnection()
        .build()

    override suspend fun connect(): Boolean = connectMutex.withLock {
        if (client.isReady) {
            _availability.value = BillingAvailability.Available
            return@withLock true
        }
        _availability.value = BillingAvailability.Connecting
        val result = suspendCancellableCoroutine<BillingResult> { cont ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    if (cont.isActive) cont.resume(billingResult)
                }

                override fun onBillingServiceDisconnected() {
                    // Auto service reconnection re-establishes the connection on the next call.
                    RgLog.w(TAG, "billing service disconnected")
                }
            })
        }
        when (result.responseCode) {
            BillingResponseCode.OK -> {
                _availability.value = BillingAvailability.Available
                true
            }
            BillingResponseCode.BILLING_UNAVAILABLE, BillingResponseCode.FEATURE_NOT_SUPPORTED -> {
                _availability.value = BillingAvailability.Unavailable(UnavailableReason.PLAY_BILLING_UNAVAILABLE, result.debugMessage)
                false
            }
            else -> {
                _availability.value = BillingAvailability.Unavailable(UnavailableReason.SERVICE_UNAVAILABLE, result.debugMessage)
                false
            }
        }
    }

    override suspend fun queryProducts(subscriptionIds: List<String>, oneTimeIds: List<String>): Outcome<List<StoreProduct>> {
        if (!connect()) return Outcome.Failure(ErrorKind.NOT_SUPPORTED, "billing unavailable")
        val products = mutableListOf<StoreProduct>()
        for ((type, ids) in listOf(BillingClient.ProductType.SUBS to subscriptionIds, BillingClient.ProductType.INAPP to oneTimeIds)) {
            if (ids.isEmpty()) continue
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(ids.distinct().map { QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(type).build() })
                .build()
            val result = client.queryProductDetails(params)
            if (result.billingResult.responseCode != BillingResponseCode.OK) {
                return Outcome.Failure(result.billingResult.toErrorKind(), result.billingResult.debugMessage)
            }
            result.productDetailsList.orEmpty().forEach { pd ->
                details[pd.productId] = pd
                products += pd.toStoreProduct()
            }
        }
        return Outcome.Success(products)
    }

    override suspend fun launchPurchase(activity: Activity, product: StoreProduct, offer: StoreOffer?, obfuscatedAccountId: String?): Outcome<Unit> {
        if (!connect()) return Outcome.Failure(ErrorKind.NOT_SUPPORTED, "billing unavailable")
        val pd = details[product.productId] ?: return Outcome.Failure(ErrorKind.INVALID_INPUT, "product details not loaded")
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(pd).apply {
            val token = offer?.offerToken ?: pd.oneTimePurchaseOfferDetails?.offerToken
            if (token != null) setOfferToken(token)
        }.build()
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .apply { if (!obfuscatedAccountId.isNullOrBlank()) setObfuscatedAccountId(obfuscatedAccountId) }
            .build()
        val result = withContext(Dispatchers.Main) { client.launchBillingFlow(activity, flowParams) }
        return when (result.responseCode) {
            BillingResponseCode.OK -> Outcome.Success(Unit)
            BillingResponseCode.USER_CANCELED -> Outcome.Failure(ErrorKind.CANCELLED)
            BillingResponseCode.ITEM_ALREADY_OWNED -> {
                restore()
                _events.tryEmit(PurchaseEvent.AlreadyOwned)
                Outcome.Success(Unit)
            }
            else -> Outcome.Failure(result.toErrorKind(), result.debugMessage)
        }
    }

    override suspend fun restore(): Outcome<List<PurchaseRecord>> {
        if (!connect()) return Outcome.Failure(ErrorKind.NOT_SUPPORTED, "billing unavailable")
        val all = mutableListOf<PurchaseRecord>()
        for (type in listOf(BillingClient.ProductType.SUBS, BillingClient.ProductType.INAPP)) {
            val result = client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(type).build())
            if (result.billingResult.responseCode != BillingResponseCode.OK) {
                return Outcome.Failure(result.billingResult.toErrorKind(), result.billingResult.debugMessage)
            }
            all += result.purchasesList.map { it.toRecord() }
        }
        _purchases.value = all
        return Outcome.Success(all)
    }

    override suspend fun acknowledge(purchase: PurchaseRecord): Outcome<Unit> {
        if (purchase.acknowledged) return Outcome.Success(Unit)
        if (!connect()) return Outcome.Failure(ErrorKind.NETWORK, "billing unavailable")
        val result = client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build())
        return if (result.responseCode == BillingResponseCode.OK) {
            _purchases.value = _purchases.value.map { if (it.purchaseToken == purchase.purchaseToken) it.copy(acknowledged = true) else it }
            Outcome.Success(Unit)
        } else {
            Outcome.Failure(result.toErrorKind(), result.debugMessage)
        }
    }

    override suspend fun consume(purchase: PurchaseRecord): Outcome<Unit> {
        if (!connect()) return Outcome.Failure(ErrorKind.NETWORK, "billing unavailable")
        val result = client.consumePurchase(ConsumeParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build())
        return when (result.billingResult.responseCode) {
            BillingResponseCode.OK -> {
                _purchases.value = _purchases.value.filterNot { it.purchaseToken == purchase.purchaseToken }
                Outcome.Success(Unit)
            }
            else -> Outcome.Failure(result.billingResult.toErrorKind(), result.billingResult.debugMessage)
        }
    }

    override fun manageSubscriptionUrl(productId: String?): String {
        val base = "https://play.google.com/store/account/subscriptions?package=${context.packageName}"
        return if (productId != null) "$base&sku=$productId" else base
    }

    private fun onPurchasesUpdated(result: BillingResult, list: List<Purchase>?) {
        when (result.responseCode) {
            BillingResponseCode.OK -> {
                val records = list.orEmpty().map { it.toRecord() }
                val byToken = _purchases.value.associateBy { it.purchaseToken }.toMutableMap()
                records.forEach { byToken[it.purchaseToken] = it }
                _purchases.value = byToken.values.toList()
                records.forEach { record ->
                    when (record.state) {
                        PurchaseState.PURCHASED -> _events.tryEmit(PurchaseEvent.Purchased(record))
                        PurchaseState.PENDING -> _events.tryEmit(PurchaseEvent.Pending(record.productIds))
                        PurchaseState.UNKNOWN -> Unit
                    }
                }
            }
            BillingResponseCode.USER_CANCELED -> _events.tryEmit(PurchaseEvent.Cancelled)
            BillingResponseCode.ITEM_ALREADY_OWNED -> {
                _events.tryEmit(PurchaseEvent.AlreadyOwned)
                scope.launch { restore() }
            }
            else -> _events.tryEmit(PurchaseEvent.Failed(result.toFailure(), result.debugMessage))
        }
    }

    private fun Purchase.toRecord() = PurchaseRecord(
        productIds = products,
        purchaseToken = purchaseToken,
        orderId = orderId,
        state = when (purchaseState) {
            Purchase.PurchaseState.PURCHASED -> PurchaseState.PURCHASED
            Purchase.PurchaseState.PENDING -> PurchaseState.PENDING
            else -> PurchaseState.UNKNOWN
        },
        acknowledged = isAcknowledged,
        autoRenewing = isAutoRenewing,
        purchaseTime = purchaseTime,
        quantity = quantity,
        packageName = packageName,
    )

    private fun ProductDetails.toStoreProduct(): StoreProduct {
        val isSub = productType == BillingClient.ProductType.SUBS
        val offers = if (isSub) {
            subscriptionOfferDetails.orEmpty().map { o ->
                StoreOffer(
                    productId = productId,
                    basePlanId = o.basePlanId,
                    offerId = o.offerId,
                    offerToken = o.offerToken,
                    phases = o.pricingPhases.pricingPhaseList.map { p ->
                        PricePhase(p.formattedPrice, p.priceAmountMicros, p.priceCurrencyCode, p.billingPeriod, p.billingCycleCount, p.recurrenceMode == ProductDetails.RecurrenceMode.INFINITE_RECURRING)
                    },
                    tags = o.offerTags.orEmpty(),
                )
            }
        } else {
            listOfNotNull(oneTimePurchaseOfferDetails).map { o ->
                StoreOffer(productId, null, o.offerId, o.offerToken, listOf(PricePhase(o.formattedPrice, o.priceAmountMicros, o.priceCurrencyCode, "", 1, false)), o.offerTags.orEmpty())
            }
        }
        return StoreProduct(productId, if (isSub) ProductType.SUBSCRIPTION else ProductType.ONE_TIME, name.ifBlank { title }, description, offers)
    }

    private fun BillingResult.toErrorKind(): ErrorKind = when (responseCode) {
        BillingResponseCode.USER_CANCELED -> ErrorKind.CANCELLED
        BillingResponseCode.NETWORK_ERROR, BillingResponseCode.SERVICE_UNAVAILABLE, BillingResponseCode.SERVICE_DISCONNECTED, BillingResponseCode.SERVICE_TIMEOUT -> ErrorKind.NETWORK
        BillingResponseCode.BILLING_UNAVAILABLE, BillingResponseCode.FEATURE_NOT_SUPPORTED -> ErrorKind.NOT_SUPPORTED
        BillingResponseCode.ITEM_UNAVAILABLE, BillingResponseCode.DEVELOPER_ERROR -> ErrorKind.NOT_CONFIGURED
        else -> ErrorKind.UNKNOWN
    }

    private fun BillingResult.toFailure(): BillingFailure = when (responseCode) {
        BillingResponseCode.NETWORK_ERROR, BillingResponseCode.SERVICE_UNAVAILABLE, BillingResponseCode.SERVICE_TIMEOUT -> BillingFailure.NETWORK
        BillingResponseCode.SERVICE_DISCONNECTED -> BillingFailure.NOT_CONNECTED
        BillingResponseCode.BILLING_UNAVAILABLE, BillingResponseCode.FEATURE_NOT_SUPPORTED -> BillingFailure.BILLING_UNAVAILABLE
        BillingResponseCode.ITEM_UNAVAILABLE -> BillingFailure.ITEM_UNAVAILABLE
        BillingResponseCode.DEVELOPER_ERROR -> BillingFailure.DEVELOPER_ERROR
        else -> BillingFailure.UNKNOWN
    }

    private companion object {
        const val TAG = "PlayBilling"
    }
}
