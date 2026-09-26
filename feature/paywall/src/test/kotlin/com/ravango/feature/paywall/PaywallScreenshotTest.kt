package com.ravango.feature.paywall

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.ravango.core.model.Entitlements
import com.ravango.core.model.Plan
import com.ravango.core.model.ProFeature
import com.ravango.core.testing.captureAllVariants
import com.ravango.platform.billing.BillingAvailability
import com.ravango.platform.billing.PackOffer
import com.ravango.platform.billing.PaywallCatalog
import com.ravango.platform.billing.PlanChoice
import com.ravango.platform.billing.PlanOffer
import com.ravango.platform.billing.PricePhase
import com.ravango.platform.billing.ProductType
import com.ravango.platform.billing.StoreOffer
import com.ravango.platform.billing.StoreProduct
import com.ravango.platform.billing.UnavailableReason
import org.junit.Test
import java.util.Locale
import androidx.compose.ui.platform.LocalConfiguration
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private fun price(text: String, micros: Long, period: String) = PricePhase(text, micros, "USD", period, 0, recurring = period.isNotEmpty())

private fun plan(choice: PlanChoice, id: String, price: PricePhase, trial: Int? = null): PlanOffer {
    val offer = StoreOffer(id, null, null, null, listOf(price))
    return PlanOffer(choice, StoreProduct(id, if (choice == PlanChoice.LIFETIME) ProductType.ONE_TIME else ProductType.SUBSCRIPTION, id, "", listOf(offer)), offer, price, trial)
}

private fun pack(credits: Int, text: String, micros: Long): PackOffer {
    val p = price(text, micros, "")
    val offer = StoreOffer("credits_$credits", null, null, null, listOf(p))
    return PackOffer(StoreProduct("credits_$credits", ProductType.ONE_TIME, "", "", listOf(offer)), offer, credits, p)
}

private val catalog = PaywallCatalog(
    monthly = plan(PlanChoice.MONTHLY, "pro_monthly", price("$4.99", 4_990_000, "P1M")),
    yearly = plan(PlanChoice.YEARLY, "pro_yearly", price("$29.99", 29_990_000, "P1Y"), trial = 7),
    lifetime = plan(PlanChoice.LIFETIME, "lifetime", price("$59.99", 59_990_000, "")),
    packs = listOf(pack(100, "$1.99", 1_990_000), pack(500, "$6.99", 6_990_000)),
    yearlySavingsPercent = 50,
)

private val available = PaywallUiState(
    availability = BillingAvailability.Available,
    storeName = "Google Play",
    loadingCatalog = false,
    catalog = catalog,
    selected = PlanChoice.YEARLY,
    entitlements = Entitlements(aiCreditsRemaining = 12),
)

@Composable
private fun Paywall(state: PaywallUiState) = PaywallContent(
    state = state,
    snackbar = remember { SnackbarHostState() },
    onClose = {}, onRestore = {}, onSelect = {}, onRetry = {}, onManage = {}, onBuyPack = {}, onTerms = {}, onPrivacy = {}, onPurchase = {},
)

/**
 * Robolectric changes the resource locale per variant but not [Locale.getDefault], which the app's number/byte
 * formatters use (on a device the per-app language updates both). Keep them in sync so digits render as on device.
 */
@Composable
private fun SyncLocale() {
    Locale.setDefault(LocalConfiguration.current.locales[0])
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PaywallScreenshotTest {

    @Test
    fun free() = captureAllVariants("paywall-free") { SyncLocale(); Paywall(available) }

    @Test
    fun freeFull() = captureAllVariants("paywall-full", qualifiers = "w412dp-h2400dp") { SyncLocale(); Paywall(available) }

    @Test
    fun featureGate() = captureAllVariants("paywall-feature") { SyncLocale(); Paywall(available.copy(feature = ProFeature.RECORD_4K, selected = PlanChoice.LIFETIME)) }

    @Test
    fun loading() = captureAllVariants("paywall-loading") { SyncLocale();
        Paywall(PaywallUiState(availability = BillingAvailability.Connecting, storeName = "Google Play", loadingCatalog = true))
    }

    @Test
    fun unavailable() = captureAllVariants("paywall-unavailable") { SyncLocale();
        Paywall(
            PaywallUiState(
                availability = BillingAvailability.Unavailable(UnavailableReason.STORE_NOT_SUPPORTED),
                storeName = "Cafe Bazaar",
                loadingCatalog = false,
            ),
        )
    }

    @Test
    fun pro() = captureAllVariants("paywall-pro") { SyncLocale();
        Paywall(available.copy(entitlements = Entitlements(plan = Plan.PRO, aiCreditsPerMonth = 500, aiCreditsRemaining = 420), canManageSubscription = true))
    }

    @Test
    fun smallPhone() = captureAllVariants("paywall-small", qualifiers = "w360dp-h740dp") { SyncLocale(); Paywall(available) }
}
