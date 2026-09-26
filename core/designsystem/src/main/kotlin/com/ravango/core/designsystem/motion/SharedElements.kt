package com.ravango.core.designsystem.motion

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shape
import com.ravango.core.designsystem.theme.Motion
import com.ravango.core.designsystem.theme.Radius
import com.ravango.core.designsystem.theme.RgTheme

/*
 * Shared-element ("container transform") plumbing.
 *
 * The app wraps its NavHost in [RgSharedTransitionLayout]; each destination that takes part wraps its content in
 * [RgNavDestination] (passing the `composable {}` lambda's AnimatedContentScope). Screens then tag matching elements
 * with [Modifier.rgSharedBounds] using the same key on both sides (see [SharedKeys]). Outside that setup — previews,
 * screenshot tests, bottom sheets — or with reduce motion, the modifiers are no-ops, so screens stay reusable.
 */

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/** The AnimatedVisibilityScope of the current navigation destination (its enter/exit transition). */
val LocalNavAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** Keys shared between a source and a destination. */
object SharedKeys {
    fun project(id: String): Any = "rg-project-$id"
    fun script(id: String): Any = "rg-script-$id"
    fun template(id: String): Any = "rg-template-$id"
}

/** Hosts shared-element transitions for everything inside (typically the app's NavHost). */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun RgSharedTransitionLayout(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    SharedTransitionLayout(modifier) {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) { content() }
    }
}

/** Provides the destination's [AnimatedVisibilityScope] so [rgSharedBounds] can pair elements across screens. */
@Composable
fun RgNavDestination(animatedVisibilityScope: AnimatedVisibilityScope, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides animatedVisibilityScope, content = content)
}

/** Container-transform spring: settles without overshoot so rounded corners never wobble. */
@OptIn(ExperimentalSharedTransitionApi::class)
private val SharedBoundsTransform = BoundsTransform { _, _ ->
    spring(dampingRatio = 0.92f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = Rect.VisibilityThreshold)
}

/**
 * Morphs this element's bounds into the element with the same [key] on the other screen (card → detail). Content
 * cross-fades during the morph. The element is clipped to [shape] while it travels in the overlay.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
fun Modifier.rgSharedBounds(
    key: Any,
    shape: Shape = RoundedCornerShape(Radius.lg),
    enter: EnterTransition = fadeIn(tween(Motion.Duration.Medium, delayMillis = 40)),
    exit: ExitTransition = fadeOut(tween(Motion.Duration.Short)),
    zIndex: Float = 0f,
): Modifier = composed {
    val shared = LocalSharedTransitionScope.current
    val visibility = LocalNavAnimatedVisibilityScope.current
    if (shared == null || visibility == null || RgTheme.reduceMotion) return@composed Modifier
    with(shared) {
        Modifier.sharedBounds(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = visibility,
            enter = enter,
            exit = exit,
            boundsTransform = SharedBoundsTransform,
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            zIndexInOverlay = zIndex,
            clipInOverlayDuringTransition = OverlayClip(shape),
        )
    }
}

/**
 * Shares one visual element (same content on both sides, e.g. a thumbnail image) between screens. Unlike
 * [rgSharedBounds] the content does not cross-fade; it scales to the new bounds.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
fun Modifier.rgSharedElement(key: Any, shape: Shape = RoundedCornerShape(Radius.lg)): Modifier = composed {
    val shared = LocalSharedTransitionScope.current
    val visibility = LocalNavAnimatedVisibilityScope.current
    if (shared == null || visibility == null || RgTheme.reduceMotion) return@composed Modifier
    with(shared) {
        Modifier.sharedElement(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = visibility,
            boundsTransform = SharedBoundsTransform,
            clipInOverlayDuringTransition = OverlayClip(shape),
        )
    }
}

/**
 * Keeps an element (top bar, floating button) above shared elements while they travel, and fades it with the
 * destination transition, so a card morphing into the screen does not draw over the chrome.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
fun Modifier.rgRenderAboveSharedElements(zIndex: Float = 1f): Modifier = composed {
    val shared = LocalSharedTransitionScope.current
    if (shared == null || RgTheme.reduceMotion) return@composed Modifier
    with(shared) { Modifier.renderInSharedTransitionScopeOverlay(zIndexInOverlay = zIndex) }
}
