package com.ravango.platform.billing

import com.ravango.core.common.di.ApplicationScope
import com.ravango.core.common.log.RgLog
import com.ravango.core.database.dao.AiUsageDao
import com.ravango.core.database.entity.AiUsageEntity
import com.ravango.core.model.AiOperation
import com.ravango.core.model.Clock
import com.ravango.core.model.Entitlements
import com.ravango.core.model.service.EntitlementProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [EntitlementProvider]: plan (store ∪ server ∪ offline cache) + [com.ravango.platform.billing.config.MonetizationConfig]
 * → [Entitlements]. AI credits = monthly allowance − this month's local ledger (or the server's metering when
 * higher) + purchased pack balance. Consumption is atomic (mutex) and refunds reverse the exact split.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DefaultEntitlementProvider @Inject constructor(
    private val billing: BillingRepository,
    private val usageDao: AiUsageDao,
    private val clock: Clock,
    @ApplicationScope scope: CoroutineScope,
) : EntitlementProvider {

    private val mutex = Mutex()
    /** Recent reservations, newest last, so a refund returns credits to where they came from. */
    private val recent = ArrayDeque<Pair<AiOperation, CreditReservation>>()

    /** Emits the current period start, and again at every month boundary. */
    private val periodStarts: Flow<Long> = flow {
        while (true) {
            val now = clock.now()
            emit(EntitlementCalculator.periodStart(now))
            delay((EntitlementCalculator.nextPeriodStart(now) - now).coerceAtLeast(1_000))
        }
    }.distinctUntilChanged()

    private val monthlyUsedLocal: Flow<Int> = periodStarts.flatMapLatest { usageDao.observeUsedSince(it) }

    override val entitlements: StateFlow<Entitlements> = combine(
        billing.config,
        billing.ownership,
        monthlyUsedLocal,
        billing.packBalance,
        billing.serverEntitlement,
    ) { config, ownership, localUsed, packs, server ->
        EntitlementCalculator.compute(config, ownership, monthlyUsed(localUsed, server), packs, clock.now())
    }.stateIn(
        scope,
        SharingStarted.Eagerly,
        EntitlementCalculator.compute(com.ravango.platform.billing.config.MonetizationConfig.Default, Ownership(), 0, 0, clock.now()),
    )

    /** Server metering (AI gateway) wins when it has seen more usage this period than this device. */
    private fun monthlyUsed(localUsed: Int, server: com.ravango.platform.billing.server.ServerEntitlement?): Int {
        val serverUsed = server?.takeIf { s -> s.periodStart == null || s.periodStart >= EntitlementCalculator.periodStart(clock.now()) - PERIOD_SKEW_MS }?.aiCreditsUsed
        return maxOf(localUsed, serverUsed ?: 0)
    }

    override suspend fun tryConsumeAiCredits(operation: AiOperation, units: Int): Boolean = mutex.withLock {
        val credits = operation.credits * units.coerceAtLeast(1)
        val now = clock.now()
        val limits = billing.config.value.limitsFor(entitlements.value.plan)
        val used = monthlyUsed(usageDao.usedSince(EntitlementCalculator.periodStart(now)), billing.serverEntitlement.value)
        val reservation = EntitlementCalculator.reserve(credits, limits.aiCreditsPerMonth, used, billing.packBalance.value) ?: return@withLock false
        if (reservation.fromMonthly > 0) usageDao.insert(AiUsageEntity(operation = operation.name, credits = reservation.fromMonthly, createdAt = now))
        if (reservation.fromPacks > 0) billing.adjustPackBalance(-reservation.fromPacks)
        recent.addLast(operation to reservation)
        while (recent.size > MAX_RECENT) recent.removeFirst()
        true
    }

    override suspend fun refundAiCredits(operation: AiOperation, units: Int): Unit = mutex.withLock {
        val credits = operation.credits * units.coerceAtLeast(1)
        val index = recent.indexOfLast { (op, r) -> op == operation && r.total == credits }
        val now = clock.now()
        if (index >= 0) {
            val (_, reservation) = recent.removeAt(index)
            if (reservation.fromMonthly > 0) usageDao.insert(AiUsageEntity(operation = operation.name, credits = -reservation.fromMonthly, createdAt = now))
            if (reservation.fromPacks > 0) billing.adjustPackBalance(reservation.fromPacks)
        } else {
            // Unknown reservation (e.g. process restarted): refund to this month's allowance, never below zero usage.
            val usedThisPeriod = usageDao.usedSince(EntitlementCalculator.periodStart(now))
            val refundable = minOf(credits, usedThisPeriod)
            if (refundable > 0) usageDao.insert(AiUsageEntity(operation = operation.name, credits = -refundable, createdAt = now))
            else RgLog.w(TAG, "refund of $credits credits for $operation ignored (nothing consumed this period)")
        }
    }

    private companion object {
        const val TAG = "Entitlements"
        const val MAX_RECENT = 64
        const val PERIOD_SKEW_MS = 24L * 60 * 60 * 1000
    }
}
