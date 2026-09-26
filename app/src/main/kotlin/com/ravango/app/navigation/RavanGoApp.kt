package com.ravango.app.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import com.ravango.core.designsystem.motion.RgSharedTransitionLayout
import com.ravango.core.designsystem.motion.RgTransitions
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.navigation.CameraRoute
import com.ravango.core.navigation.EditorRoute
import com.ravango.core.navigation.ExportRoute
import com.ravango.core.navigation.PaywallRoute
import com.ravango.core.navigation.SignInRoute
import com.ravango.core.navigation.TeleprompterRoute
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.composable
import com.ravango.core.navigation.HomeRoute
import com.ravango.core.navigation.LicensesRoute
import com.ravango.core.navigation.OnboardingRoute
import com.ravango.core.navigation.ScriptEditorRoute
import com.ravango.feature.account.accountDestinations
import com.ravango.feature.account.diagnostics.CrashReportPrompt
import com.ravango.feature.ai.aiDestinations
import com.ravango.feature.beauty.beautyDestinations
import com.ravango.feature.camera.cameraDestinations
import com.ravango.feature.editor.editorDestinations
import com.ravango.feature.home.homeDestinations
import com.ravango.feature.onboarding.onboardingDestinations
import com.ravango.feature.paywall.paywallDestinations
import com.ravango.feature.projects.projectsDestinations
import com.ravango.feature.scripts.scriptsDestinations
import com.ravango.feature.teleprompter.teleprompterDestinations
import kotlinx.coroutines.flow.StateFlow

/** Full-screen, always-dark capture/edit surfaces: entered with a fade-through instead of a lateral slide. */
private fun NavDestination.isStudio(): Boolean =
    hasRoute<CameraRoute>() || hasRoute<TeleprompterRoute>() || hasRoute<EditorRoute>() || hasRoute<ExportRoute>()

/** Destinations presented modally (slide up from the bottom, the screen underneath stays in place). */
private fun NavDestination.isModal(): Boolean = hasRoute<PaywallRoute>() || hasRoute<SignInRoute>()

@Composable
fun RavanGoApp(
    onboardingCompleted: Boolean,
    sharedText: kotlinx.coroutines.flow.MutableStateFlow<String?>,
) {
    val navController = rememberNavController()
    val start: Any = remember { if (onboardingCompleted) HomeRoute else OnboardingRoute }

    val reduceMotion = RgTheme.reduceMotion
    // The NavHost paints the theme background itself, so cross-fading screens never reveal the window (no white or
    // black flashes between light screens and the always-dark studio screens).
    RgSharedTransitionLayout(Modifier.fillMaxSize().background(RgTheme.colors.background)) {
        NavHost(
            navController = navController,
            startDestination = start,
            modifier = Modifier.fillMaxSize(),
            enterTransition = {
                when {
                    targetState.destination.isModal() -> RgTransitions.modalEnter(reduceMotion)
                    targetState.destination.isStudio() || initialState.destination.isStudio() || initialState.destination.hasRoute<OnboardingRoute>() ->
                        RgTransitions.fadeThroughEnter(reduceMotion)
                    else -> with(RgTransitions) { sharedAxisEnter(reduceMotion) }
                }
            },
            exitTransition = {
                when {
                    targetState.destination.isModal() -> RgTransitions.underModalExit(reduceMotion)
                    targetState.destination.isStudio() || initialState.destination.isStudio() || initialState.destination.hasRoute<OnboardingRoute>() ->
                        RgTransitions.fadeThroughExit(reduceMotion)
                    else -> with(RgTransitions) { sharedAxisExit(reduceMotion) }
                }
            },
            popEnterTransition = {
                when {
                    initialState.destination.isModal() -> RgTransitions.underModalPopEnter(reduceMotion)
                    targetState.destination.isStudio() || initialState.destination.isStudio() -> RgTransitions.fadeThroughPopEnter(reduceMotion)
                    else -> with(RgTransitions) { sharedAxisPopEnter(reduceMotion) }
                }
            },
            popExitTransition = {
                when {
                    initialState.destination.isModal() -> RgTransitions.modalPopExit(reduceMotion)
                    targetState.destination.isStudio() || initialState.destination.isStudio() -> RgTransitions.fadeThroughPopExit(reduceMotion)
                    else -> with(RgTransitions) { sharedAxisPopExit(reduceMotion) }
                }
            },
        ) {
            onboardingDestinations(navController)
            homeDestinations(navController)
            scriptsDestinations(navController)
            teleprompterDestinations(navController)
            cameraDestinations(navController)
            beautyDestinations(navController)
            editorDestinations(navController)
            aiDestinations(navController)
            projectsDestinations(navController)
            accountDestinations(navController)
            paywallDestinations(navController)
            composable<LicensesRoute> { LicensesScreen(onBack = { navController.popBackStack() }) }
        }
    }

    // Text shared from another app opens the script editor pre-filled.
    LaunchedEffect(sharedText) {
        sharedText.collect { text ->
            if (!text.isNullOrBlank() && onboardingCompleted) {
                navController.navigate(ScriptEditorRoute(initialText = text.take(200_000)))
                sharedText.value = null
            }
        }
    }

    // "RavanGo closed unexpectedly last time": view / share the on-device crash report (nothing is uploaded).
    CrashReportPrompt()
}
