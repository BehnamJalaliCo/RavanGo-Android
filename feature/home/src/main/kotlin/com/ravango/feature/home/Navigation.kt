package com.ravango.feature.home

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import com.ravango.core.designsystem.motion.RgNavDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.ravango.core.navigation.HomeRoute
import com.ravango.core.navigation.TeleprompterRoute

/** Result key set by feature:scripts on this entry's SavedStateHandle when a script is picked for the prompter. */
const val PICKED_SCRIPT_ID_KEY = "picked_script_id"

/** Registers this feature's destinations. Navigation to other features uses routes from :core:navigation. */
fun NavGraphBuilder.homeDestinations(navController: NavHostController) {
    composable<HomeRoute> { entry ->
        RgNavDestination(this) {
        // Teleprompter quick action: Scripts (pick mode) returns the chosen id here; open the prompter with it.
        val picked by entry.savedStateHandle.getStateFlow<String?>(PICKED_SCRIPT_ID_KEY, null).collectAsStateWithLifecycle()
        LaunchedEffect(picked) {
            val id = picked ?: return@LaunchedEffect
            entry.savedStateHandle.remove<String>(PICKED_SCRIPT_ID_KEY)
            navController.navigate(TeleprompterRoute(id)) { launchSingleTop = true }
        }
        HomeDestination(onNavigate = { route -> navController.navigate(route) { launchSingleTop = true } })
        }
    }
}
