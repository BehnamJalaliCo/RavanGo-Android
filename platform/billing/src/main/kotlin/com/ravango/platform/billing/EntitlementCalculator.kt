package com.ravango.platform.billing

import com.ravango.core.model.Entitlements
import com.ravango.core.model.Plan
import com.ravango.platform.billing.config.MonetizationConfig
import com.ravango.platform.billing.config.ProductIds
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Where an ownership claim came from. */
@Serializable
enum class OwnershipSource { NONE, STORE, SERVER, CACHE }

/** What the user owns right now (plan + trial/expiry), independent of limits. */
@Serializable
data class Ownership(
    val plan: Plan = Plan.FREE,
    val isTrial: Boolean = false,
    val expiresAt: Long? = null,
    val source: OwnershipSource = OwnershipSource.NONE,
    /** When this ownership was last confirmed by the store or server (epoch ms). */
    val confirmedAt: Long = 0,
) {
    fun isExpired(now: Long): Boolean = expiresAt != null && now > expiresAt
}

/** How a credit consumption was split between the monthly allowance and purchased packs. */
data class CreditReservation(val fromMonthly: Int, val fromPacks: Int) {
    val total: Int get() = fromMonthly + fromPacks
}

/** Pure entitlement and credit math (unit tested). */
object EntitlementCalculator {

    private val planRank = mapOf(Plan.FREE to 0, Plan.LIFETIME to 1, Plan.PRO to 2)

    fun compute(config: MonetizationConfig, ownership: Ownership, monthlyUsed: Int, packBalance: Int, now: Long): Entitlements {
        val plan = if (ownership.isExpired(now)) Plan.FREE else ownership.plan
        val limits = config.limitsFor(plan)
        val monthlyRemaining = max(0, limits.aiCreditsPerMonth - monthlyUsed)
        return Entitlements(
            plan = plan,
            features = limits.featureSet(),
            aiCreditsPerMonth = limits.aiCreditsPerMonth,
            aiCreditsRemaining = monthlyRemaining + max(0, packBalance),
            cloudQuotaBytes = limits.cloudQuotaBytes,
            maxRecordShortSide = limits.maxRecordShortSide,
            maxRecordFps = limits.maxRecordFps,
            maxExportShortSide = limits.maxExportShortSide,
            maxExportFps = limits.maxExportFps,
            maxSavedPresets = limits.presetLimit,
            watermarkOnExport = limits.watermarkOnExport,
            isTrial = plan != Plan.FREE && ownership.isTrial,
            expiresAt = if (plan == Plan.FREE) null else ownership.expiresAt,
        )
    }

    /** Monthly allowance is spent first (it resets), purchased packs last (they never expire). Null = not enough. */
    fun reserve(credits: Int, allowance: Int, monthlyUsed: Int, packBalance: Int): CreditReservation? {
        if (credits <= 0) return CreditReservation(0, 0)
        val monthlyRemaining = max(0, allowance - monthlyUsed)
        if (monthlyRemaining + max(0, packBalance) < credits) return null
        val fromMonthly = min(credits, monthlyRemaining)
        return CreditReservation(fromMonthly, credits - fromMonthly)
    }

    /** Plan owned according to active store purchases. A Pro subscription outranks Lifetime (bigger AI allowance). */
    fun ownershipFromPurchases(purchases: List<PurchaseRecord>, products: ProductIds, now: Long): Ownership {
        val active = purchases.filter { it.state == PurchaseState.PURCHASED }
        val plan = when {
            active.any { products.subscriptionId in it.productIds } -> Plan.PRO
            active.any { products.lifetimeProductId in it.productIds } -> Plan.LIFETIME
            else -> Plan.FREE
        }
        return Ownership(plan = plan, source = if (plan == Plan.FREE) OwnershipSource.NONE else OwnershipSource.STORE, confirmedAt = now)
    }

    /**
     * Combines the store's view with the server's. The server is trusted when it grants a plan (purchases on
     * other devices/stores, promotional grants, trial/expiry info); a fresh local store purchase still counts
     * while the server has not verified it yet.
     */
    fun merge(store: Ownership?, server: Ownership?, cached: Ownership?, now: Long, cacheGraceMs: Long): Ownership {
        val candidates = buildList {
            store?.let(::add)
            server?.takeIf { !it.isExpired(now) }?.let(::add)
            if (store == null && server == null) cached?.takeIf { !it.isExpired(now) && now - it.confirmedAt <= cacheGraceMs }?.let { add(it.copy(source = OwnershipSource.CACHE)) }
        }
        return candidates.maxWithOrNull(compareBy<Ownership> { planRank[it.plan] ?: 0 }.thenBy { if (it.source == OwnershipSource.SERVER) 1 else 0 })
            ?: Ownership(confirmedAt = now)
    }

    /** Start of the current credit period (calendar month, local time). */
    fun periodStart(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        return t.withDayOfMonth(1).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
    }

    fun nextPeriodStart(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        return t.withDayOfMonth(1).plusMonths(1).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** Yearly vs 12× monthly savings in whole percent; null when there is no saving. */
    fun savingsPercent(monthlyMicros: Long, yearlyMicros: Long): Int? {
        if (monthlyMicros <= 0 || yearlyMicros <= 0) return null
        val pct = ((1.0 - yearlyMicros.toDouble() / (monthlyMicros * 12.0)) * 100).roundToInt()
        return pct.takeIf { it > 0 }
    }

    /** Days in an ISO-8601 billing period such as P7D, P1W, P1M, P1Y (months ≈ 30 days). */
    fun periodDays(iso: String?): Int? {
        if (iso.isNullOrBlank()) return null
        val match = Regex("^P(?:(\\d+)Y)?(?:(\\d+)M)?(?:(\\d+)W)?(?:(\\d+)D)?$").matchEntire(iso.trim().uppercase()) ?: return null
        val (y, m, w, d) = match.destructured
        val days = (y.toIntOrNull() ?: 0) * 365 + (m.toIntOrNull() ?: 0) * 30 + (w.toIntOrNull() ?: 0) * 7 + (d.toIntOrNull() ?: 0)
        return days.takeIf { it > 0 }
    }
}
