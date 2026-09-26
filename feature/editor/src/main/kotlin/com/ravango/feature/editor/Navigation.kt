package com.ravango.feature.editor

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.ravango.core.navigation.EditorRoute
import com.ravango.core.navigation.ExportRoute
import com.ravango.core.navigation.PaywallRoute
import com.ravango.core.navigation.SettingsRoute
import com.ravango.feature.editor.export.ExportScreen

/** Registers this feature's destinations. Navigation to other features uses routes from :core:navigation. */
fun NavGraphBuilder.editorDestinations(navController: NavHostController) {
    composable<EditorRoute> {
        EditorScreen(
            onBack = { navController.popBackStack() },
            onExport = { projectId -> navController.navigate(ExportRoute(projectId)) },
            onRequirePro = { feature -> navController.navigate(PaywallRoute(source = "editor", feature = feature.name)) },
            onOpenSettings = { navController.navigate(SettingsRoute) },
        )
    }
    composable<ExportRoute> { entry ->
        val route = entry.toRoute<ExportRoute>()
        ExportScreen(
            onBack = { navController.popBackStack() },
            onBackToEditor = {
                // Return to the project's editor (it is usually right below; otherwise open it).
                if (!navController.popBackStack()) navController.navigate(EditorRoute(route.projectId))
            },
            onRequirePro = { feature -> navController.navigate(PaywallRoute(source = "export", feature = feature.name)) },
        )
    }
}
