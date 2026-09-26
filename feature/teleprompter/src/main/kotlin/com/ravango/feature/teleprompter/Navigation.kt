package com.ravango.feature.teleprompter

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.ravango.core.navigation.CameraRoute
import com.ravango.core.navigation.PaywallRoute
import com.ravango.core.navigation.TeleprompterRoute
import com.ravango.core.navigation.TeleprompterSettingsRoute
import com.ravango.feature.teleprompter.player.PrompterRoute
import com.ravango.feature.teleprompter.settings.PrompterSettingsRoute

/** Registers this feature's destinations. Navigation to other features uses routes from :core:navigation. */
fun NavGraphBuilder.teleprompterDestinations(navController: NavHostController) {
    composable<TeleprompterRoute> {
        PrompterRoute(
            onBack = { navController.popBackStack() },
            onOpenSettings = { id -> navController.navigate(TeleprompterSettingsRoute(scriptId = id)) },
            onRecord = { id -> navController.navigate(CameraRoute(scriptId = id)) },
            onRequirePro = { feature -> navController.navigate(PaywallRoute(source = "teleprompter", feature = feature.name)) },
        )
    }
    composable<TeleprompterSettingsRoute> {
        PrompterSettingsRoute(onBack = { navController.popBackStack() })
    }
}
