package com.ravango.feature.home.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.ravango.core.designsystem.theme.Motion
import com.ravango.core.designsystem.theme.RgTheme
import kotlinx.coroutines.delay

private const val STEP_MS = 55
private const val MAX_DELAY_MS = 440
private const val DURATION_MS = 420

/**
 * True during the first moments a screen is shown (once per saved screen state), so list items animate in on first
 * appearance but not every time they are scrolled back into view. The window starts once [ready] (content loaded).
 * Always false with reduced motion.
 */
@Composable
fun rememberEntranceActive(ready: Boolean = true): Boolean {
    val reduceMotion = RgTheme.reduceMotion
    var active by rememberSaveable { mutableStateOf(!reduceMotion) }
    LaunchedEffect(ready) {
        if (ready && active) {
            delay((MAX_DELAY_MS + DURATION_MS + 60).toLong())
            active = false
        }
    }
    return active && !reduceMotion
}

/** Staggered fade-and-rise entrance, delayed by `index * 55ms` (capped). No-op when [enabled] is false. */
fun Modifier.staggeredEntrance(index: Int, enabled: Boolean): Modifier = if (!enabled) this else composed {
    val progress = remember { Animatable(0f) }
    val rise = with(LocalDensity.current) { 18.dp.toPx() }
    LaunchedEffect(Unit) {
        delay((index * STEP_MS).coerceAtMost(MAX_DELAY_MS).toLong())
        progress.animateTo(1f, tween(DURATION_MS, easing = Motion.EmphasizedEasing))
    }
    graphicsLayer {
        val p = progress.value
        alpha = p
        translationY = (1f - p) * rise
    }
}
