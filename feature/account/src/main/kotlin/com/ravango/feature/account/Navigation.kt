package com.ravango.feature.account

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.ravango.core.model.ProFeature
import com.ravango.core.navigation.AboutRoute
import com.ravango.core.navigation.AccountRoute
import com.ravango.core.navigation.CloudRoute
import com.ravango.core.navigation.LicensesRoute
import com.ravango.core.navigation.PaywallRoute
import com.ravango.core.navigation.PrivacyRoute
import com.ravango.core.navigation.SettingsRoute
import com.ravango.core.navigation.SignInRoute
import com.ravango.core.navigation.TermsRoute
import com.ravango.feature.account.about.AboutScreen
import com.ravango.feature.account.about.TermsScreen
import com.ravango.feature.account.account.AccountScreen
import com.ravango.feature.account.cloud.CloudScreen
import com.ravango.feature.account.privacy.PrivacyScreen
import com.ravango.feature.account.settings.SettingsScreen
import com.ravango.feature.account.signin.SignInScreen

/** Registers this feature's destinations. Navigation to other features uses routes from :core:navigation. */
fun NavGraphBuilder.accountDestinations(navController: NavHostController) {
    val back: () -> Unit = { navController.popBackStack() }
    fun paywall(source: String, feature: ProFeature? = null) = navController.navigate(PaywallRoute(source = source, feature = feature?.name))

    composable<AccountRoute> {
        AccountScreen(
            onBack = back,
            onSignIn = { navController.navigate(SignInRoute) },
            onPaywall = { paywall("account") },
            onCloud = { navController.navigate(CloudRoute) },
            onSettings = { navController.navigate(SettingsRoute) },
            onPrivacy = { navController.navigate(PrivacyRoute) },
            onTerms = { navController.navigate(TermsRoute) },
            onAbout = { navController.navigate(AboutRoute) },
        )
    }
    composable<SignInRoute> {
        SignInScreen(onBack = back, onSignedIn = back)
    }
    composable<SettingsRoute> {
        SettingsScreen(onBack = back, onRequirePro = { paywall("settings", it) })
    }
    composable<CloudRoute> {
        CloudScreen(
            onBack = back,
            onSignIn = { navController.navigate(SignInRoute) },
            onRequirePro = { paywall("cloud", it) },
        )
    }
    composable<PrivacyRoute> { PrivacyScreen(onBack = back) }
    composable<TermsRoute> { TermsScreen(onBack = back) }
    composable<AboutRoute> {
        AboutScreen(
            onBack = back,
            onLicenses = { navController.navigate(LicensesRoute) },
            onPrivacy = { navController.navigate(PrivacyRoute) },
            onTerms = { navController.navigate(TermsRoute) },
        )
    }
}
