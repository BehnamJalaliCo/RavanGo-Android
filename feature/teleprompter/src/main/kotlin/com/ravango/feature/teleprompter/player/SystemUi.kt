package com.ravango.feature.teleprompter.player

import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ravango.core.ui.findActivity

/** Hides status and navigation bars while in composition; they reappear transiently on an edge swipe. */
@Composable
internal fun ImmersiveSystemBars(enabled: Boolean = true) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        val window = view.context.findActivity()?.window
        if (window == null || !enabled) return@DisposableEffect onDispose { }
        val controller = WindowCompat.getInsetsController(window, view)
        val previousBehavior = controller.systemBarsBehavior
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = previousBehavior
        }
    }
}

/** Locks the current orientation while [locked]; restores the activity's original request on exit. */
@Composable
internal fun OrientationLock(locked: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    val original = remember(activity) { activity.requestedOrientation }
    DisposableEffect(activity, locked) {
        activity.requestedOrientation = if (locked) ActivityInfo.SCREEN_ORIENTATION_LOCKED else ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        onDispose { activity.requestedOrientation = original }
    }
}
