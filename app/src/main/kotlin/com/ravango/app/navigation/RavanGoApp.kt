package com.ravango.app.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import com.ravango.app.R
import com.ravango.core.designsystem.component.RgConfirmDialog
import com.ravango.core.navigation.HomeRoute
import com.ravango.core.navigation.OnboardingRoute
import com.ravango.core.navigation.ScriptEditorRoute
import com.ravango.feature.account.accountDestinations
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
import com.ravango.core.ui.R as UiR

@Composable
fun RavanGoApp(
    onboardingCompleted: Boolean,
    sharedText: kotlinx.coroutines.flow.MutableStateFlow<String?>,
    previousCrash: String?,
) {
    val navController = rememberNavController()
    val start: Any = remember { if (onboardingCompleted) HomeRoute else OnboardingRoute }

    NavHost(
        navController = navController,
        startDestination = start,
        enterTransition = { fadeIn(tween(260)) + scaleIn(tween(320), initialScale = 0.97f) },
        exitTransition = { fadeOut(tween(200)) },
        popEnterTransition = { fadeIn(tween(260)) },
        popExitTransition = { fadeOut(tween(200)) + scaleOut(tween(260), targetScale = 0.97f) },
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

    var showCrashNotice by remember { mutableStateOf(previousCrash != null) }
    if (showCrashNotice) {
        RgConfirmDialog(
            title = stringResource(R.string.crash_recovered_title),
            message = stringResource(R.string.crash_recovered_message),
            confirmText = stringResource(UiR.string.action_ok),
            dismissText = stringResource(UiR.string.action_close),
            onConfirm = { showCrashNotice = false },
            onDismiss = { showCrashNotice = false },
        )
    }
}
