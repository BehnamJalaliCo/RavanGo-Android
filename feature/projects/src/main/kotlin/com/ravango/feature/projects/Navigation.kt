package com.ravango.feature.projects

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.ravango.core.navigation.EditorRoute
import com.ravango.core.navigation.ProjectsRoute
import com.ravango.core.navigation.TemplatesRoute
import com.ravango.feature.projects.list.ProjectsRoute as ProjectsScreenRoute
import com.ravango.feature.projects.templates.TemplatesRoute as TemplatesScreenRoute

/** Registers this feature's destinations. Navigation to other features uses routes from :core:navigation. */
fun NavGraphBuilder.projectsDestinations(navController: NavHostController) {
    composable<ProjectsRoute> {
        ProjectsScreenRoute(
            onBack = { navController.popBackStack() },
            onOpenEditor = { id -> navController.navigate(EditorRoute(id)) { launchSingleTop = true } },
        )
    }
    composable<TemplatesRoute> {
        TemplatesScreenRoute(
            onBack = { navController.popBackStack() },
            onNavigate = { route -> navController.navigate(route) { launchSingleTop = true } },
        )
    }
}
