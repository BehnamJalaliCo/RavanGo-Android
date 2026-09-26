package com.ravango.core.designsystem.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ravango.core.designsystem.theme.Motion
import com.ravango.core.designsystem.theme.RgTheme
import kotlinx.coroutines.delay

/** How long the entrance window stays open after content is ready: the longest stagger plus the spring settle. */
private const val ENTRANCE_WINDOW_MS = Motion.StaggerMaxMs + 700L

/**
 * True during the first moments a screen is shown — once per back-stack entry (the flag is saved), so items animate
 * in on the first visit but not when they scroll back into view, on recomposition, or when the user comes back to
 * the screen. The window opens once [ready] (content loaded). Always false with reduce motion.
 */
@Composable
fun rememberEntranceActive(ready: Boolean = true): Boolean {
    val reduceMotion = RgTheme.reduceMotion
    var active by rememberSaveable { mutableStateOf(!reduceMotion) }
    LaunchedEffect(ready) {
        if (ready && active) {
            delay(ENTRANCE_WINDOW_MS)
            active = false
        }
    }
    return active && !reduceMotion
}

/**
 * Staggered, springy entrance: the element rises by [rise], scales up from 96 % and fades in, starting
 * `index × [Motion.StaggerStepMs]` after composition (capped at [Motion.StaggerMaxMs]). All work happens in the
 * graphics layer (no relayout, no recomposition per frame). No-op when [enabled] is false.
 */
fun Modifier.staggeredEntrance(index: Int, enabled: Boolean, rise: Dp = 24.dp): Modifier = if (!enabled) this else composed {
    val progress = remember { Animatable(0f) }
    val risePx = with(LocalDensity.current) { rise.toPx() }
    LaunchedEffect(Unit) {
        delay((index * Motion.StaggerStepMs).coerceAtMost(Motion.StaggerMaxMs).toLong())
        progress.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = 240f))
    }
    graphicsLayer {
        val p = progress.value
        alpha = (p * 1.6f).coerceIn(0f, 1f)
        translationY = (1f - p) * risePx
        val s = 0.96f + 0.04f * p
        scaleX = s
        scaleY = s
    }
}

/**
 * Horizontal variant for carousels: items slide in from the end edge (direction-aware via [rtl]) with the same
 * stagger and spring.
 */
fun Modifier.staggeredSlideIn(index: Int, enabled: Boolean, rtl: Boolean, distance: Dp = 40.dp): Modifier = if (!enabled) this else composed {
    val progress = remember { Animatable(0f) }
    val px = with(LocalDensity.current) { distance.toPx() } * if (rtl) -1f else 1f
    LaunchedEffect(Unit) {
        delay((index * Motion.StaggerStepMs + 80).coerceAtMost(Motion.StaggerMaxMs).toLong())
        progress.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 260f))
    }
    graphicsLayer {
        val p = progress.value
        alpha = (p * 1.5f).coerceIn(0f, 1f)
        translationX = (1f - p) * px
    }
}
