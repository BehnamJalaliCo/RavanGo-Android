package com.ravango.feature.camera

import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.ravango.core.navigation.BeautyPresetsRoute
import com.ravango.core.navigation.CameraRoute
import com.ravango.core.navigation.EditorRoute
import com.ravango.core.navigation.PaywallRoute
import com.ravango.core.navigation.ScriptsRoute

/** Key the scripts picker writes into this entry's SavedStateHandle. */
const val PICKED_SCRIPT_ID = "picked_script_id"

/** Registers this feature's destinations. Navigation to other features uses routes from :core:navigation. */
fun NavGraphBuilder.cameraDestinations(navController: NavHostController) {
    composable<CameraRoute> { entry ->
        val viewModel: CameraViewModel = hiltViewModel()
        val picked by entry.savedStateHandle.getStateFlow<String?>(PICKED_SCRIPT_ID, null).collectAsStateWithLifecycle()
        CameraStudioScreen(
            viewModel = viewModel,
            pickedScriptId = picked,
            onPickedScriptConsumed = { entry.savedStateHandle[PICKED_SCRIPT_ID] = null },
            onClose = { navController.popBackStack() },
            onOpenEditor = { projectId ->
                navController.navigate(EditorRoute(projectId)) {
                    popUpTo<CameraRoute> { inclusive = true }
                }
            },
            onPickScript = { navController.navigate(ScriptsRoute(pickForPrompter = true)) },
            onOpenBeautyPresets = { navController.navigate(BeautyPresetsRoute) },
            onRequirePro = { feature -> navController.navigate(PaywallRoute(source = "camera", feature = feature.name)) },
        )
    }
}
