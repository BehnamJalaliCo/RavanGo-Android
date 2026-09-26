package com.ravango.platform.billing

import com.google.common.truth.Truth.assertThat
import com.ravango.core.model.Plan
import com.ravango.core.model.ProFeature
import com.ravango.platform.billing.config.MonetizationConfig
import kotlinx.serialization.json.Json
import org.junit.Test

class MonetizationConfigTest {

    private fun parse(json: String) = Json.parseToJsonElement(json)

    @Test
    fun `defaults are valid and round trip`() {
        val d = MonetizationConfig.Default
        assertThat(d.isValid()).isTrue()
        assertThat(MonetizationConfig.decode(MonetizationConfig.encode(d))).isEqualTo(d)
    }

    @Test
    fun `partial override deep merges onto defaults`() {
        val override = parse(
            """
            {
              "version": 2,
              "free": { "aiCreditsPerMonth": 30, "features": ["FLOATING_PROMPTER", "SOMETHING_FROM_THE_FUTURE"] },
              "lifetime": { "aiCreditsPerMonth": 300 },
              "products": { "creditPacks": [ { "productId": "ai_credits_500", "credits": 500 } ] }
            }
            """,
        )
        val merged = MonetizationConfig.applyOverride(MonetizationConfig.Default, override)!!
        assertThat(merged.version).isEqualTo(2)
        assertThat(merged.free.aiCreditsPerMonth).isEqualTo(30)
        // Untouched fields keep their defaults.
        assertThat(merged.free.maxSavedPresets).isEqualTo(3)
        assertThat(merged.pro).isEqualTo(MonetizationConfig.Default.pro)
        assertThat(merged.lifetime.aiCreditsPerMonth).isEqualTo(300)
        assertThat(merged.lifetime.cloudQuotaBytes).isEqualTo(MonetizationConfig.Default.lifetime.cloudQuotaBytes)
        assertThat(merged.products.subscriptionId).isEqualTo("ravango_pro")
        assertThat(merged.products.creditPacks.single().credits).isEqualTo(500)
        // Unknown feature names are ignored, known ones applied: business rules change without an update.
        assertThat(merged.free.featureSet()).containsExactly(ProFeature.FLOATING_PROMPTER)
        val e = EntitlementCalculator.compute(merged, Ownership(Plan.FREE), 0, 0, 0)
        assertThat(e.has(ProFeature.FLOATING_PROMPTER)).isTrue()
        assertThat(e.aiCreditsRemaining).isEqualTo(30)
    }

    @Test
    fun `invalid overrides are rejected`() {
        assertThat(MonetizationConfig.applyOverride(MonetizationConfig.Default, parse("""{"pro": {"aiCreditsPerMonth": -5}}"""))).isNull()
        assertThat(MonetizationConfig.applyOverride(MonetizationConfig.Default, parse("""{"products": {"subscriptionId": ""}}"""))).isNull()
        assertThat(MonetizationConfig.applyOverride(MonetizationConfig.Default, parse("""[1,2,3]"""))).isNull()
        assertThat(MonetizationConfig.applyOverride(MonetizationConfig.Default, parse("""{"free": {"maxRecordFps": "fast"}}"""))).isNull()
        assertThat(MonetizationConfig.decode("not json")).isNull()
    }

    @Test
    fun `unlimited presets are represented as -1`() {
        assertThat(MonetizationConfig.Default.pro.presetLimit).isEqualTo(Int.MAX_VALUE)
        assertThat(MonetizationConfig.Default.free.presetLimit).isEqualTo(3)
    }

    @Test
    fun `catalog picks the trial offer for yearly and computes savings`() {
        fun phase(price: Long, period: String, formatted: String = "x") = PricePhase(formatted, price, "USD", period, 0, price > 0)
        val sub = StoreProduct(
            "ravango_pro", ProductType.SUBSCRIPTION, "RavanGo Pro", "",
            listOf(
                StoreOffer("ravango_pro", "monthly", null, "m", listOf(phase(4_990_000, "P1M"))),
                StoreOffer("ravango_pro", "yearly", null, "y", listOf(phase(29_990_000, "P1Y"))),
                StoreOffer("ravango_pro", "yearly", "yearly-free-trial", "yt", listOf(phase(0, "P7D"), phase(29_990_000, "P1Y"))),
            ),
        )
        val lifetime = StoreProduct("ravango_lifetime", ProductType.ONE_TIME, "Lifetime", "", listOf(StoreOffer("ravango_lifetime", null, null, "l", listOf(phase(79_990_000, "")))))
        val pack = StoreProduct("ai_credits_200", ProductType.ONE_TIME, "200", "", listOf(StoreOffer("ai_credits_200", null, null, "p", listOf(phase(1_990_000, "")))))
        val catalog = BillingRepository.buildCatalog(listOf(sub, lifetime, pack), MonetizationConfig.Default)
        assertThat(catalog.monthly!!.offer.offerToken).isEqualTo("m")
        assertThat(catalog.yearly!!.offer.offerToken).isEqualTo("yt")
        assertThat(catalog.yearly!!.trialDays).isEqualTo(7)
        assertThat(catalog.yearly!!.price.priceMicros).isEqualTo(29_990_000)
        assertThat(catalog.lifetime!!.price.priceMicros).isEqualTo(79_990_000)
        assertThat(catalog.packs.single().credits).isEqualTo(200)
        assertThat(catalog.yearlySavingsPercent).isEqualTo(50)

        // Not eligible for the trial anymore → Play omits the offer → base plan, no trial badge.
        val noTrial = BillingRepository.buildCatalog(listOf(sub.copy(offers = sub.offers.take(2))), MonetizationConfig.Default)
        assertThat(noTrial.yearly!!.trialDays).isNull()
        assertThat(noTrial.lifetime).isNull()
        assertThat(noTrial.packs).isEmpty()
    }
}
