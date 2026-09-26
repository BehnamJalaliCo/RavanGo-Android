package com.ravango.platform.billing

import android.app.Activity
import com.ravango.core.common.AppConfig
import com.ravango.core.common.di.ApplicationScope
import com.ravango.core.common.log.RgLog
import com.ravango.core.common.result.ErrorKind
import com.ravango.core.common.result.Outcome
import com.ravango.core.datastore.PreferencesDataSource
import com.ravango.core.model.Clock
import com.ravango.core.model.Plan
import com.ravango.core.model.service.AuthState
import com.ravango.platform.auth.AuthRepository
import com.ravango.platform.billing.config.MonetizationConfig
import com.ravango.platform.billing.config.MonetizationConfigRepository
import com.ravango.platform.billing.server.EntitlementServer
import com.ravango.platform.billing.server.ServerEntitlement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

enum class PlanChoice { MONTHLY, YEARLY, LIFETIME }

/** A purchasable plan with its live store price. */
data class PlanOffer(
    val choice: PlanChoice,
    val product: StoreProduct,
    val offer: StoreOffer,
    /** Recurring (or one-time) price. */
    val price: PricePhase,
    /** Free-trial length in days when the store offers one to this user. */
    val trialDays: Int?,
)

data class PackOffer(val product: StoreProduct, val offer: StoreOffer, val credits: Int, val price: PricePhase)

data class PaywallCatalog(
    val monthly: PlanOffer?,
    val yearly: PlanOffer?,
    val lifetime: PlanOffer?,
    val packs: List<PackOffer>,
    val yearlySavingsPercent: Int?,
) {
    val isEmpty: Boolean get() = monthly == null && yearly == null && lifetime == null && packs.isEmpty()
    fun offer(choice: PlanChoice): PlanOffer? = when (choice) {
        PlanChoice.MONTHLY -> monthly
        PlanChoice.YEARLY -> yearly
        PlanChoice.LIFETIME -> lifetime
    }
}

/** Events for the paywall beyond the raw store events. */
sealed interface BillingNotice {
    data class PlanActivated(val plan: Plan) : BillingNotice
    data class CreditsAdded(val credits: Int) : BillingNotice
    data class Pending(val productIds: List<String>) : BillingNotice
    data object Cancelled : BillingNotice
    data object AlreadyOwned : BillingNotice
    data class Failed(val reason: BillingFailure) : BillingNotice
}

/**
 * Purchases, ownership and AI credit packs. Processes every store purchase exactly once: subscriptions/lifetime
 * are verified server-side when possible and acknowledged; credit packs are credited (idempotently by purchase
 * token) and consumed. Ownership = store ∪ server, with a cached copy for offline starts.
 *
 * Trust model: with Supabase configured and the `verify-purchase` function deployed, the server validates tokens
 * with Google and is the source of truth. Without it, an acknowledged Play purchase is trusted locally — a
 * modified client could fake that, which only affects that one device (documented in docs/MONETIZATION.md).
 */
@Singleton
class BillingRepository @Inject constructor(
    private val provider: BillingProvider,
    private val configRepository: MonetizationConfigRepository,
    private val server: EntitlementServer,
    private val auth: AuthRepository,
    private val preferences: PreferencesDataSource,
    private val appConfig: AppConfig,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
) {
    val storeName: String get() = provider.storeName
    val availability: StateFlow<BillingAvailability> get() = provider.availability
    val config: StateFlow<MonetizationConfig> get() = configRepository.config

    private val _notices = MutableSharedFlow<BillingNotice>(extraBufferCapacity = 16)
    val notices: SharedFlow<BillingNotice> = _notices.asSharedFlow()

    private val storeOwnership = MutableStateFlow<Ownership?>(null)
    private val _serverEntitlement = MutableStateFlow<ServerEntitlement?>(null)
    val serverEntitlement: StateFlow<ServerEntitlement?> = _serverEntitlement.asStateFlow()
    private val cachedOwnership = MutableStateFlow<Ownership?>(null)

    private val _packBalance = MutableStateFlow(0)
    val packBalance: StateFlow<Int> = _packBalance.asStateFlow()

    private val processMutex = Mutex()
    private val packMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val ownership: StateFlow<Ownership> = combine(storeOwnership, _serverEntitlement, cachedOwnership) { store, srv, cached ->
        EntitlementCalculator.merge(store, srv?.toOwnership(clock.now()), cached, clock.now(), CACHE_GRACE_MS)
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, Ownership())

    private var catalogCache: PaywallCatalog? = null

    init {
        scope.launch {
            _packBalance.value = preferences.observeString(KEY_PACKS).first()?.toIntOrNull() ?: 0
            cachedOwnership.value = preferences.observeString(KEY_OWNERSHIP).first()?.let { raw -> runCatching { json.decodeFromString(Ownership.serializer(), raw) }.getOrNull() }
        }
        scope.launch {
            ownership.collect { current ->
                if (current.source == OwnershipSource.STORE || current.source == OwnershipSource.SERVER) {
                    preferences.putString(KEY_OWNERSHIP, json.encodeToString(Ownership.serializer(), current))
                }
            }
        }
        scope.launch {
            provider.events.collect { event -> onStoreEvent(event) }
        }
        scope.launch {
            provider.purchases.collect { list -> if (storeOwnership.value != null || list.isNotEmpty()) updateStoreOwnership(list) }
        }
        scope.launch {
            auth.authState.map { (it as? AuthState.SignedIn)?.user?.id }.distinctUntilChanged().collect { userId ->
                if (userId == null) _serverEntitlement.value = null else refreshServerEntitlement()
            }
        }
    }

    /** Startup/refresh: remote config, store connection, owned purchases, server entitlement. */
    suspend fun refresh() {
        configRepository.refresh()
        if (provider.connect()) restoreInternal()
        refreshServerEntitlement()
    }

    private fun updateStoreOwnership(purchases: List<PurchaseRecord>) {
        storeOwnership.value = EntitlementCalculator.ownershipFromPurchases(purchases, config.value.products, clock.now())
    }

    private suspend fun restoreInternal(): Outcome<List<PurchaseRecord>> {
        val result = provider.restore()
        if (result is Outcome.Success) {
            updateStoreOwnership(result.value)
            result.value.filter { it.state == PurchaseState.PURCHASED }.forEach { process(it, interactive = false) }
        }
        return result
    }

    /** "Restore purchases": re-reads the store and the server; returns the resulting plan. */
    suspend fun restore(): Outcome<Plan> {
        val storeResult = restoreInternal()
        refreshServerEntitlement()
        return when {
            storeResult is Outcome.Failure && _serverEntitlement.value == null -> storeResult
            else -> Outcome.Success(ownership.value.plan)
        }
    }

    suspend fun refreshServerEntitlement() {
        if (!server.isConfigured) return
        val user = (auth.authState.value as? AuthState.SignedIn)?.user ?: return
        val token = auth.accessToken() ?: return
        try {
            val entitlement = server.fetch(user.id, token)
            _serverEntitlement.value = entitlement
            entitlement?.aiPackBalance?.let { setPackBalance(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RgLog.w(TAG, "server entitlement fetch failed", e)
        }
    }

    suspend fun loadCatalog(): Outcome<PaywallCatalog> {
        val products = config.value.products
        val result = provider.queryProducts(
            subscriptionIds = listOf(products.subscriptionId),
            oneTimeIds = listOf(products.lifetimeProductId) + products.creditPacks.map { it.productId },
        )
        if (result is Outcome.Failure) return result
        val list = (result as Outcome.Success).value
        val catalog = buildCatalog(list, config.value)
        catalogCache = catalog
        return Outcome.Success(catalog)
    }

    suspend fun purchasePlan(activity: Activity, choice: PlanChoice): Outcome<Unit> {
        val catalog = catalogCache ?: (loadCatalog() as? Outcome.Success)?.value ?: return Outcome.Failure(ErrorKind.NOT_SUPPORTED)
        val offer = catalog.offer(choice) ?: return Outcome.Failure(ErrorKind.NOT_CONFIGURED, "product not available")
        return provider.launchPurchase(activity, offer.product, offer.offer, obfuscatedAccountId())
    }

    suspend fun purchasePack(activity: Activity, pack: PackOffer): Outcome<Unit> =
        provider.launchPurchase(activity, pack.product, pack.offer, obfuscatedAccountId())

    fun manageSubscriptionUrl(): String? = provider.manageSubscriptionUrl(config.value.products.subscriptionId)

    /** Adjusts purchased-pack credits (negative to spend). Persisted; never below zero. */
    suspend fun adjustPackBalance(delta: Int): Unit = packMutex.withLock {
        val next = (_packBalance.value + delta).coerceAtLeast(0)
        _packBalance.value = next
        preferences.putString(KEY_PACKS, next.toString())
    }

    private suspend fun setPackBalance(value: Int): Unit = packMutex.withLock {
        _packBalance.value = value.coerceAtLeast(0)
        preferences.putString(KEY_PACKS, _packBalance.value.toString())
    }

    private suspend fun onStoreEvent(event: PurchaseEvent) {
        when (event) {
            is PurchaseEvent.Purchased -> process(event.record, interactive = true)
            is PurchaseEvent.Pending -> _notices.tryEmit(BillingNotice.Pending(event.productIds))
            PurchaseEvent.Cancelled -> _notices.tryEmit(BillingNotice.Cancelled)
            PurchaseEvent.AlreadyOwned -> _notices.tryEmit(BillingNotice.AlreadyOwned)
            is PurchaseEvent.Failed -> _notices.tryEmit(BillingNotice.Failed(event.reason))
        }
    }

    /** Idempotent handling of one completed purchase. */
    private suspend fun process(purchase: PurchaseRecord, interactive: Boolean): Unit = processMutex.withLock {
        if (purchase.state != PurchaseState.PURCHASED) return@withLock
        val products = config.value.products
        val productId = purchase.productIds.firstOrNull() ?: return@withLock
        val pack = products.packFor(productId)
        val type = if (productId == products.subscriptionId) ProductType.SUBSCRIPTION else ProductType.ONE_TIME
        val verification = verifyWithServer(purchase, productId, type)
        verification?.entitlement?.let { _serverEntitlement.value = it }
        if (verification != null && !verification.valid) {
            RgLog.w(TAG, "server rejected purchase of $productId: ${verification.reason}")
            if (interactive) _notices.tryEmit(BillingNotice.Failed(BillingFailure.UNKNOWN))
            return@withLock
        }

        if (pack != null) {
            val processed = processedTokens()
            if (purchase.purchaseToken !in processed) {
                val credits = pack.credits * purchase.quantity.coerceAtLeast(1)
                val serverCredits = verification?.creditsAdded ?: 0
                val serverBalance = verification?.entitlement?.aiPackBalance
                when {
                    serverCredits > 0 && serverBalance != null -> setPackBalance(serverBalance)
                    serverCredits > 0 -> adjustPackBalance(serverCredits)
                    else -> adjustPackBalance(credits)
                }
                // Credit first, then consume: a crash in between can never lose the user's credits.
                markProcessed(purchase.purchaseToken)
                _notices.tryEmit(BillingNotice.CreditsAdded(if (serverCredits > 0) serverCredits else credits))
            }
            val consumed = provider.consume(purchase)
            if (consumed is Outcome.Failure) RgLog.w(TAG, "consume failed (will retry on next restore): ${consumed.message}")
        } else {
            if (!purchase.acknowledged) {
                val ack = provider.acknowledge(purchase)
                if (ack is Outcome.Failure) RgLog.w(TAG, "acknowledge failed (will retry): ${ack.message}")
            }
            updateStoreOwnership(provider.purchases.value)
            if (interactive) {
                val plan = if (productId == products.subscriptionId) Plan.PRO else Plan.LIFETIME
                _notices.tryEmit(BillingNotice.PlanActivated(plan))
            }
        }
    }

    private suspend fun verifyWithServer(purchase: PurchaseRecord, productId: String, type: ProductType) = run {
        if (!server.isConfigured) return@run null
        val token = auth.accessToken() ?: return@run null
        try {
            server.verify(purchase, productId, type, appConfig.distribution, token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline or server error: fall back to local trust; the next restore verifies again.
            RgLog.w(TAG, "purchase verification unavailable", e)
            null
        }
    }

    private suspend fun processedTokens(): List<String> =
        preferences.observeString(KEY_PROCESSED).first()?.let { runCatching { json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() } ?: emptyList()

    private suspend fun markProcessed(token: String) {
        val next = (processedTokens() + token).takeLast(MAX_PROCESSED)
        preferences.putString(KEY_PROCESSED, json.encodeToString(ListSerializer(String.serializer()), next))
    }

    private fun obfuscatedAccountId(): String? = auth.currentUser?.id?.let { id ->
        MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }.take(64)
    }

    private fun ServerEntitlement.toOwnership(now: Long) = Ownership(plan, isTrial, expiresAt, OwnershipSource.SERVER, now)

    companion object {
        private const val TAG = "Billing"
        private const val KEY_PACKS = "billing.packBalance"
        private const val KEY_OWNERSHIP = "billing.ownership.v1"
        private const val KEY_PROCESSED = "billing.processedTokens"
        private const val MAX_PROCESSED = 200
        /** How long a cached paid plan is honoured while neither the store nor the server can be reached. */
        private const val CACHE_GRACE_MS = 7L * 24 * 60 * 60 * 1000

        /** Picks monthly/yearly/lifetime/pack offers from the store products (pure; unit tested). */
        fun buildCatalog(products: List<StoreProduct>, config: MonetizationConfig): PaywallCatalog {
            val ids = config.products
            val sub = products.firstOrNull { it.productId == ids.subscriptionId }
            fun planOffer(choice: PlanChoice, basePlanId: String): PlanOffer? {
                val offers = sub?.offers?.filter { it.basePlanId == basePlanId }.orEmpty()
                // Prefer the configured trial offer (Play only returns offers the user is eligible for), then any
                // offer with a free phase, then the base plan itself.
                val chosen = offers.firstOrNull { it.offerId == ids.yearlyTrialOfferId && choice == PlanChoice.YEARLY }
                    ?: offers.firstOrNull { it.freeTrialPhase != null && choice == PlanChoice.YEARLY }
                    ?: offers.firstOrNull { it.offerId == null }
                    ?: offers.firstOrNull()
                    ?: return null
                val price = chosen.fullPricePhase ?: return null
                return PlanOffer(choice, sub!!, chosen, price, chosen.freeTrialPhase?.let { EntitlementCalculator.periodDays(it.billingPeriod) })
            }
            val monthly = planOffer(PlanChoice.MONTHLY, ids.monthlyBasePlanId)
            val yearly = planOffer(PlanChoice.YEARLY, ids.yearlyBasePlanId)
            val lifetime = products.firstOrNull { it.productId == ids.lifetimeProductId }?.let { p ->
                val offer = p.offers.firstOrNull() ?: return@let null
                PlanOffer(PlanChoice.LIFETIME, p, offer, offer.fullPricePhase ?: return@let null, null)
            }
            val packs = ids.creditPacks.mapNotNull { pack ->
                val p = products.firstOrNull { it.productId == pack.productId } ?: return@mapNotNull null
                val offer = p.offers.firstOrNull() ?: return@mapNotNull null
                PackOffer(p, offer, pack.credits, offer.fullPricePhase ?: return@mapNotNull null)
            }
            val savings = if (monthly != null && yearly != null && monthly.price.currencyCode == yearly.price.currencyCode) {
                EntitlementCalculator.savingsPercent(monthly.price.priceMicros, yearly.price.priceMicros)
            } else {
                null
            }
            return PaywallCatalog(monthly, yearly, lifetime, packs, savings)
        }
    }
}
