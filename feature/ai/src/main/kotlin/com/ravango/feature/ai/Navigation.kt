package com.ravango.feature.ai

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.ravango.core.navigation.AiStudioRoute
import com.ravango.core.navigation.PaywallRoute
import com.ravango.core.navigation.ProjectsRoute
import com.ravango.core.navigation.SettingsRoute
import com.ravango.core.navigation.SignInRoute
import com.ravango.core.navigation.TeleprompterRoute

/** Registers this feature's destinations. Navigation to other features uses routes from :core:navigation. */
fun NavGraphBuilder.aiDestinations(navController: NavHostController) {
    composable<AiStudioRoute> {
        AiStudioScreen(
            onBack = { navController.popBackStack() },
            onOpenTeleprompter = { id -> navController.navigate(TeleprompterRoute(id)) },
            onOpenSettings = { navController.navigate(SettingsRoute) },
            onOpenPaywall = { navController.navigate(PaywallRoute(source = "ai_studio")) },
            onSignIn = { navController.navigate(SignInRoute) },
            onOpenProjects = { navController.navigate(ProjectsRoute()) },
        )
    }
}
