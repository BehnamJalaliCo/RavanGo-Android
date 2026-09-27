package com.ravango.platform.billing.bazaar

import com.google.common.truth.Truth.assertThat
import com.ravango.platform.billing.BillingRepository
import com.ravango.platform.billing.PlanChoice
import com.ravango.platform.billing.ProductType
import com.ravango.platform.billing.bazaar.BazaarBillingProvider.Companion.SkuPrice
import com.ravango.platform.billing.config.MonetizationConfig
import com.ravango.platform.billing.config.ProductIds
import org.junit.Test

class BazaarBillingMappingTest {
    private val ids = ProductIds()

    @Test fun `parses Persian, Latin and Arabic-Indic prices`() {
        assertThat(BazaarBillingProvider.parseAmount("۴۹٬۰۰۰ تومان")).isEqualTo(49_000)
        assertThat(BazaarBillingProvider.parseAmount("49,000 Toman")).isEqualTo(49_000)
        assertThat(BazaarBillingProvider.parseAmount("٤٩٠٬٠٠٠ ریال")).isEqualTo(490_000)
        assertThat(BazaarBillingProvider.parseAmount("رایگان")).isNull()
    }

    @Test fun `unparsed price is never free`() {
        val phase = BazaarBillingProvider.pricePhase("قیمت نامشخص", "P1M", recurring = true)
        assertThat(phase.isFree).isFalse()
        assertThat(phase.formattedPrice).isEqualTo("قیمت نامشخص")
    }

    @Test fun `rial prices are tagged IRR, others IRT`() {
        assertThat(BazaarBillingProvider.pricePhase("۴۹۰٬۰۰۰ ریال", "", false).currencyCode).isEqualTo("IRR")
        assertThat(BazaarBillingProvider.pricePhase("۴۹٬۰۰۰ تومان", "", false).currencyCode).isEqualTo("IRT")
    }

    @Test fun `subscription SKUs map onto the app subscription id`() {
        assertThat(BazaarBillingProvider.appProductId(ids.bazaarMonthlySku, ids)).isEqualTo(ids.subscriptionId)
        assertThat(BazaarBillingProvider.appProductId(ids.bazaarYearlySku, ids)).isEqualTo(ids.subscriptionId)
        assertThat(BazaarBillingProvider.appProductId(ids.lifetimeProductId, ids)).isEqualTo(ids.lifetimeProductId)
    }

    @Test fun `two Bazaar SKUs become monthly and yearly plans on the paywall`() {
        val product = BazaarBillingProvider.subscriptionProductFrom(
            listOf(
                SkuPrice(ids.bazaarMonthlySku, "روان‌گو پرو ماهانه", "", "۴۹٬۰۰۰ تومان"),
                SkuPrice(ids.bazaarYearlySku, "روان‌گو پرو سالانه", "", "۳۹۰٬۰۰۰ تومان"),
            ),
            ids,
        )!!
        assertThat(product.type).isEqualTo(ProductType.SUBSCRIPTION)
        assertThat(product.productId).isEqualTo(ids.subscriptionId)

        val catalog = BillingRepository.buildCatalog(listOf(product), MonetizationConfig.Default)
        assertThat(catalog.monthly!!.offer.offerToken).isEqualTo(ids.bazaarMonthlySku)
        assertThat(catalog.yearly!!.offer.offerToken).isEqualTo(ids.bazaarYearlySku)
        assertThat(catalog.monthly!!.choice).isEqualTo(PlanChoice.MONTHLY)
        assertThat(catalog.yearly!!.trialDays).isNull()
        // 12 × 49,000 = 588,000 vs 390,000 → 34% saving.
        assertThat(catalog.yearlySavingsPercent).isEqualTo(34)
    }

    @Test fun `missing SKUs yield no subscription product`() {
        assertThat(BazaarBillingProvider.subscriptionProductFrom(emptyList(), ids)).isNull()
    }
}
