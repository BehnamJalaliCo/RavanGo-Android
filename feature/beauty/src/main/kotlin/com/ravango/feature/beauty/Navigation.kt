package com.ravango.feature.beauty

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.ravango.core.navigation.BeautyPresetsRoute
import com.ravango.core.navigation.PaywallRoute

/** Registers this feature's destinations. Navigation to other features uses routes from :core:navigation. */
fun NavGraphBuilder.beautyDestinations(navController: NavHostController) {
    composable<BeautyPresetsRoute> {
        BeautyPresetsRoute(
            onBack = { navController.popBackStack() },
            onRequirePro = { feature -> navController.navigate(PaywallRoute(source = "beauty_presets", feature = feature.name)) },
        )
    }
}
