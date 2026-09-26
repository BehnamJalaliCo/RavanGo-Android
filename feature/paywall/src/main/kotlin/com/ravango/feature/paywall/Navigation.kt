package com.ravango.feature.paywall

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.ravango.core.navigation.PaywallRoute
import com.ravango.core.navigation.PrivacyRoute
import com.ravango.core.navigation.TermsRoute

/** Registers this feature's destinations. Navigation to other features uses routes from :core:navigation. */
fun NavGraphBuilder.paywallDestinations(navController: NavHostController) {
    composable<PaywallRoute> {
        PaywallScreen(
            onClose = { navController.popBackStack() },
            onOpenTerms = { navController.navigate(TermsRoute) },
            onOpenPrivacy = { navController.navigate(PrivacyRoute) },
        )
    }
}
