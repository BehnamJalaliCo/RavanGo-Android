package com.ravango.feature.scripts

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
import com.ravango.core.navigation.ScriptsRoute
import com.ravango.core.navigation.ScriptEditorRoute

/** Registers this feature's destinations. Navigation to other features uses routes from :core:navigation. */
fun NavGraphBuilder.scriptsDestinations(navController: NavHostController) {
    composable<ScriptsRoute> { StubScreen("ScriptsRoute") { navController.popBackStack() } }
    composable<ScriptEditorRoute> { StubScreen("ScriptEditorRoute") { navController.popBackStack() } }
}

@Composable
private fun StubScreen(name: String, onBack: () -> Unit) {
    RgScreen(title = name, onBack = onBack) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(name) } }
}
