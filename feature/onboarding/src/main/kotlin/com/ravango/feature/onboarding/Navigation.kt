package com.ravango.feature.onboarding

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.ravango.core.navigation.HomeRoute
import com.ravango.core.navigation.OnboardingRoute

/** Registers this feature's destinations. Navigation to other features uses routes from :core:navigation. */
fun NavGraphBuilder.onboardingDestinations(navController: NavHostController) {
    composable<OnboardingRoute> {
        OnboardingDestination(
            onFinished = {
                navController.navigate(HomeRoute) {
                    popUpTo<OnboardingRoute> { inclusive = true }
                    launchSingleTop = true
                }
            },
        )
    }
}
