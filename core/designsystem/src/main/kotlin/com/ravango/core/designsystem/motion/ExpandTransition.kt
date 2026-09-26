package com.ravango.core.designsystem.motion

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shape
import com.ravango.core.designsystem.theme.Motion
import com.ravango.core.designsystem.theme.Radius
import com.ravango.core.designsystem.theme.RgTheme

/**
 * In-screen container transform: a tile expands into a detail card laid over the screen (and shrinks back into the
 * tile on dismiss). Unlike a modal bottom sheet, which lives in its own window, the card morphs out of the tapped
 * tile, so the user sees exactly where it came from.
 *
 * ```
 * RgExpandHost { // this: RgExpandScope
 *     Grid { ExpandSource(key = item.id, expandedKey = selected?.id) { Tile(item) } }
 *     ExpandTarget(expandedKey = selected?.id, onDismiss = …) { key -> DetailCard(key) }
 * }
 * ```
 * With reduce motion the tile stays put and the card simply fades in.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun RgExpandHost(modifier: Modifier = Modifier, content: @Composable RgExpandScope.() -> Unit) {
    SharedTransitionLayout(modifier) {
        val scope = remember(this) { RgExpandScope(this) }
        scope.content()
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
private val ExpandBoundsTransform = androidx.compose.animation.BoundsTransform { _, _ ->
    spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = Rect.VisibilityThreshold)
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Stable
class RgExpandScope internal constructor(private val shared: SharedTransitionScope) {

    /**
     * The collapsed tile. While its [key] is the [expandedKey], the tile's slot keeps its size (the grid does not
     * reflow) and its content travels into the detail card.
     */
    @Composable
    fun ExpandSource(
        key: Any,
        expandedKey: Any?,
        modifier: Modifier = Modifier,
        shape: Shape = RoundedCornerShape(Radius.xl),
        content: @Composable BoxScope.() -> Unit,
    ) {
        val reduceMotion = RgTheme.reduceMotion
        Box(modifier) {
            AnimatedVisibility(
                visible = reduceMotion || expandedKey != key,
                enter = fadeIn(tween(Motion.Duration.Medium)),
                exit = fadeOut(tween(Motion.Duration.Short)),
            ) {
                Box(
                    with(shared) {
                        if (reduceMotion) Modifier else Modifier.sharedBounds(
                            rememberSharedContentState(key),
                            this@AnimatedVisibility,
                            boundsTransform = ExpandBoundsTransform,
                            clipInOverlayDuringTransition = OverlayClip(shape),
                        )
                    }.clip(shape),
                    content = content,
                )
            }
        }
    }

    /**
     * The expanded card, shown over a dimmed [scrim] while [expandedKey] is non-null. Tapping the scrim calls
     * [onDismiss]; screens should also route the system back gesture to it. [content] receives the key being shown
     * (kept during the exit animation).
     */
    @Composable
    fun ExpandTarget(
        expandedKey: Any?,
        onDismiss: () -> Unit,
        modifier: Modifier = Modifier,
        shape: Shape = RoundedCornerShape(Radius.xl),
        alignment: Alignment = Alignment.BottomCenter,
        content: @Composable BoxScope.(key: Any) -> Unit,
    ) {
        val reduceMotion = RgTheme.reduceMotion
        var lastKey by remember { mutableStateOf(expandedKey) }
        if (expandedKey != null) lastKey = expandedKey
        Box(Modifier.fillMaxSize()) {
            AnimatedVisibility(expandedKey != null, enter = fadeIn(tween(Motion.Duration.Medium)), exit = fadeOut(tween(Motion.Duration.Medium))) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(RgTheme.colors.scrim)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
                )
            }
            AnimatedVisibility(
                visible = expandedKey != null,
                modifier = Modifier.align(alignment),
                enter = fadeIn(tween(Motion.Duration.Short)),
                exit = fadeOut(tween(if (reduceMotion) Motion.Duration.Instant else Motion.Duration.Short)),
            ) {
                val key = lastKey ?: return@AnimatedVisibility
                Box(
                    modifier
                        .then(
                            with(shared) {
                                if (reduceMotion) Modifier else Modifier.sharedBounds(
                                    rememberSharedContentState(key),
                                    this@AnimatedVisibility,
                                    boundsTransform = ExpandBoundsTransform,
                                    clipInOverlayDuringTransition = OverlayClip(shape),
                                    zIndexInOverlay = 2f,
                                )
                            },
                        )
                        .clip(shape),
                ) { content(key) }
            }
        }
    }
}
