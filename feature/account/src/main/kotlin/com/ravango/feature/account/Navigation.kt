package com.ravango.feature.account

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.ravango.core.designsystem.component.RgScreen
import com.ravango.core.navigation.AccountRoute
import com.ravango.core.navigation.SignInRoute
import com.ravango.core.navigation.SettingsRoute
import com.ravango.core.navigation.CloudRoute
import com.ravango.core.navigation.PrivacyRoute
import com.ravango.core.navigation.TermsRoute
import com.ravango.core.navigation.AboutRoute

/** Registers this feature's destinations. Navigation to other features uses routes from :core:navigation. */
fun NavGraphBuilder.accountDestinations(navController: NavHostController) {
    composable<AccountRoute> { StubScreen("AccountRoute") { navController.popBackStack() } }
    composable<SignInRoute> { StubScreen("SignInRoute") { navController.popBackStack() } }
    composable<SettingsRoute> { StubScreen("SettingsRoute") { navController.popBackStack() } }
    composable<CloudRoute> { StubScreen("CloudRoute") { navController.popBackStack() } }
    composable<PrivacyRoute> { StubScreen("PrivacyRoute") { navController.popBackStack() } }
    composable<TermsRoute> { StubScreen("TermsRoute") { navController.popBackStack() } }
    composable<AboutRoute> { StubScreen("AboutRoute") { navController.popBackStack() } }
}

@Composable
private fun StubScreen(name: String, onBack: () -> Unit) {
    RgScreen(title = name, onBack = onBack) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(name) } }
}
