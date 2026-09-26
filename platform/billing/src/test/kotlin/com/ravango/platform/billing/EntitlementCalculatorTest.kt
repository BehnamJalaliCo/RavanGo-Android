package com.ravango.platform.billing

import com.google.common.truth.Truth.assertThat
import com.ravango.core.model.Plan
import com.ravango.core.model.ProFeature
import com.ravango.platform.billing.config.MonetizationConfig
import com.ravango.platform.billing.config.ProductIds
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class EntitlementCalculatorTest {

    private val config = MonetizationConfig.Default
    private val now = 1_790_000_000_000L

    @Test
    fun `free plan limits`() {
        val e = EntitlementCalculator.compute(config, Ownership(), monthlyUsed = 5, packBalance = 0, now = now)
        assertThat(e.plan).isEqualTo(Plan.FREE)
        assertThat(e.features).isEmpty()
        assertThat(e.aiCreditsPerMonth).isEqualTo(20)
        assertThat(e.aiCreditsRemaining).isEqualTo(15)
        assertThat(e.maxRecordShortSide).isEqualTo(1080)
        assertThat(e.maxRecordFps).isEqualTo(30)
        assertThat(e.maxSavedPresets).isEqualTo(3)
        assertThat(e.watermarkOnExport).isTrue()
        assertThat(e.cloudQuotaBytes).isEqualTo(0)
        assertThat(e.has(ProFeature.FLOATING_PROMPTER)).isFalse()
    }

    @Test
    fun `pro plan unlocks everything`() {
        val e = EntitlementCalculator.compute(config, Ownership(Plan.PRO, isTrial = true, expiresAt = now + 1000), 0, 0, now)
        assertThat(e.plan).isEqualTo(Plan.PRO)
        assertThat(e.features).containsExactlyElementsIn(ProFeature.entries)
        assertThat(e.aiCreditsPerMonth).isEqualTo(1000)
        assertThat(e.cloudQuotaBytes).isEqualTo(50L * 1024 * 1024 * 1024)
        assertThat(e.maxExportShortSide).isEqualTo(2160)
        assertThat(e.maxExportFps).isEqualTo(60)
        assertThat(e.maxSavedPresets).isEqualTo(Int.MAX_VALUE)
        assertThat(e.watermarkOnExport).isFalse()
        assertThat(e.isTrial).isTrue()
        assertThat(e.expiresAt).isEqualTo(now + 1000)
    }

    @Test
    fun `lifetime has all features but a smaller AI allowance and quota`() {
        val e = EntitlementCalculator.compute(config, Ownership(Plan.LIFETIME), 50, 30, now)
        assertThat(e.features).containsExactlyElementsIn(ProFeature.entries)
        assertThat(e.aiCreditsPerMonth).isEqualTo(200)
        assertThat(e.aiCreditsRemaining).isEqualTo(150 + 30)
        assertThat(e.cloudQuotaBytes).isEqualTo(20L * 1024 * 1024 * 1024)
    }

    @Test
    fun `expired ownership falls back to free`() {
        val e = EntitlementCalculator.compute(config, Ownership(Plan.PRO, expiresAt = now - 1), 0, 0, now)
        assertThat(e.plan).isEqualTo(Plan.FREE)
        assertThat(e.expiresAt).isNull()
    }

    @Test
    fun `overused allowance never goes negative and packs still count`() {
        val e = EntitlementCalculator.compute(config, Ownership(), monthlyUsed = 35, packBalance = 200, now = now)
        assertThat(e.aiCreditsRemaining).isEqualTo(200)
    }

    @Test
    fun `credits come from the monthly allowance first then packs`() {
        assertThat(EntitlementCalculator.reserve(3, allowance = 20, monthlyUsed = 0, packBalance = 0)).isEqualTo(CreditReservation(3, 0))
        assertThat(EntitlementCalculator.reserve(5, allowance = 20, monthlyUsed = 18, packBalance = 10)).isEqualTo(CreditReservation(2, 3))
        assertThat(EntitlementCalculator.reserve(5, allowance = 20, monthlyUsed = 25, packBalance = 10)).isEqualTo(CreditReservation(0, 5))
        assertThat(EntitlementCalculator.reserve(5, allowance = 20, monthlyUsed = 18, packBalance = 2)).isNull()
        assertThat(EntitlementCalculator.reserve(0, allowance = 0, monthlyUsed = 0, packBalance = 0)).isEqualTo(CreditReservation(0, 0))
    }

    @Test
    fun `store purchases map to plans with pro outranking lifetime`() {
        val ids = ProductIds()
        fun purchase(id: String, state: PurchaseState = PurchaseState.PURCHASED) =
            PurchaseRecord(listOf(id), "t-$id", null, state, true, true, 0, 1, "com.ravango.app")
        assertThat(EntitlementCalculator.ownershipFromPurchases(emptyList(), ids, now).plan).isEqualTo(Plan.FREE)
        assertThat(EntitlementCalculator.ownershipFromPurchases(listOf(purchase(ids.lifetimeProductId)), ids, now).plan).isEqualTo(Plan.LIFETIME)
        assertThat(EntitlementCalculator.ownershipFromPurchases(listOf(purchase(ids.lifetimeProductId), purchase(ids.subscriptionId)), ids, now).plan).isEqualTo(Plan.PRO)
        assertThat(EntitlementCalculator.ownershipFromPurchases(listOf(purchase(ids.subscriptionId, PurchaseState.PENDING)), ids, now).plan).isEqualTo(Plan.FREE)
        assertThat(EntitlementCalculator.ownershipFromPurchases(listOf(purchase("ai_credits_200")), ids, now).plan).isEqualTo(Plan.FREE)
    }

    @Test
    fun `ownership merge trusts server grants, store purchases and a recent cache`() {
        val grace = 7L * 86_400_000
        val store = Ownership(Plan.FREE, source = OwnershipSource.NONE, confirmedAt = now)
        val server = Ownership(Plan.PRO, isTrial = true, expiresAt = now + 10_000, source = OwnershipSource.SERVER)
        assertThat(EntitlementCalculator.merge(store, server, null, now, grace).plan).isEqualTo(Plan.PRO)
        // Fresh Play purchase not yet verified by the server still counts.
        val storePro = Ownership(Plan.PRO, source = OwnershipSource.STORE, confirmedAt = now)
        val serverFree = Ownership(Plan.FREE, source = OwnershipSource.SERVER)
        assertThat(EntitlementCalculator.merge(storePro, serverFree, null, now, grace).plan).isEqualTo(Plan.PRO)
        // Offline start: cache honoured within the grace period only.
        val cached = Ownership(Plan.LIFETIME, source = OwnershipSource.STORE, confirmedAt = now - 1000)
        assertThat(EntitlementCalculator.merge(null, null, cached, now, grace).plan).isEqualTo(Plan.LIFETIME)
        assertThat(EntitlementCalculator.merge(null, null, cached.copy(confirmedAt = now - grace - 1), now, grace).plan).isEqualTo(Plan.FREE)
        // Once the store answered, the cache is ignored.
        assertThat(EntitlementCalculator.merge(store, null, cached, now, grace).plan).isEqualTo(Plan.FREE)
        // Expired server grants are ignored.
        assertThat(EntitlementCalculator.merge(store, server.copy(expiresAt = now - 1), null, now, grace).plan).isEqualTo(Plan.FREE)
    }

    @Test
    fun `credit period is the calendar month`() {
        val zone = ZoneId.of("Asia/Tehran")
        val t = LocalDateTime.of(2026, 9, 26, 15, 30).atZone(zone).toInstant().toEpochMilli()
        val start = EntitlementCalculator.periodStart(t, zone)
        assertThat(start).isEqualTo(LocalDateTime.of(2026, 9, 1, 0, 0).atZone(zone).toInstant().toEpochMilli())
        assertThat(EntitlementCalculator.nextPeriodStart(t, zone)).isEqualTo(LocalDateTime.of(2026, 10, 1, 0, 0).atZone(zone).toInstant().toEpochMilli())
    }

    @Test
    fun `savings and trial periods`() {
        assertThat(EntitlementCalculator.savingsPercent(4_990_000, 29_990_000)).isEqualTo(50)
        assertThat(EntitlementCalculator.savingsPercent(5_000_000, 60_000_000)).isNull()
        assertThat(EntitlementCalculator.savingsPercent(0, 10)).isNull()
        assertThat(EntitlementCalculator.periodDays("P7D")).isEqualTo(7)
        assertThat(EntitlementCalculator.periodDays("P1W")).isEqualTo(7)
        assertThat(EntitlementCalculator.periodDays("P1M")).isEqualTo(30)
        assertThat(EntitlementCalculator.periodDays("P1Y")).isEqualTo(365)
        assertThat(EntitlementCalculator.periodDays("bogus")).isNull()
        assertThat(EntitlementCalculator.periodDays(null)).isNull()
    }
}
