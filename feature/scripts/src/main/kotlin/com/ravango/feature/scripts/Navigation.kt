package com.ravango.feature.scripts

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.ravango.core.navigation.AiStudioRoute
import com.ravango.core.navigation.CameraRoute
import com.ravango.core.navigation.PaywallRoute
import com.ravango.core.navigation.ScriptEditorRoute
import com.ravango.core.navigation.ScriptsRoute
import com.ravango.core.navigation.SettingsRoute
import com.ravango.core.navigation.TeleprompterRoute
import com.ravango.core.navigation.TeleprompterSettingsRoute
import com.ravango.feature.scripts.editor.EditorNavAction
import com.ravango.feature.scripts.editor.ScriptEditorRoute as ScriptEditorScreenRoute
import com.ravango.feature.scripts.library.ScriptsRoute as ScriptsScreenRoute

/** Key under which a script picked with `ScriptsRoute(pickForPrompter = true)` is returned to the caller. */
const val PICKED_SCRIPT_ID_KEY = "picked_script_id"

/** Registers this feature's destinations. Navigation to other features uses routes from :core:navigation. */
fun NavGraphBuilder.scriptsDestinations(navController: NavHostController) {
    composable<ScriptsRoute> {
        ScriptsScreenRoute(
            onBack = { navController.popBackStack() },
            onOpenEditor = { id -> navController.navigate(ScriptEditorRoute(scriptId = id)) },
            onNewScript = { folderId -> navController.navigate(ScriptEditorRoute(folderId = folderId)) },
            onOpenPrompter = { id -> navController.navigate(TeleprompterRoute(scriptId = id)) },
            onRecord = { id -> navController.navigate(CameraRoute(scriptId = id)) },
            onPicked = { id ->
                navController.previousBackStackEntry?.savedStateHandle?.set(PICKED_SCRIPT_ID_KEY, id)
                navController.popBackStack()
            },
        )
    }
    composable<ScriptEditorRoute> {
        ScriptEditorScreenRoute(
            onBack = { navController.popBackStack() },
            onNavigate = { action, id ->
                when (action) {
                    EditorNavAction.PROMPTER -> navController.navigate(TeleprompterRoute(scriptId = id))
                    EditorNavAction.RECORD -> navController.navigate(CameraRoute(scriptId = id))
                    EditorNavAction.PROMPTER_SETTINGS -> navController.navigate(TeleprompterSettingsRoute(scriptId = id))
                    EditorNavAction.AI_STUDIO -> navController.navigate(AiStudioRoute(scriptId = id))
                    EditorNavAction.FLOATING -> Unit // handled in the screen (permission + service start)
                }
            },
            onOpenPaywall = { feature -> navController.navigate(PaywallRoute(source = "script_editor", feature = feature)) },
            onOpenSettings = { navController.navigate(SettingsRoute) },
        )
    }
}
