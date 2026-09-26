package com.ravango.feature.scripts.common

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import com.ravango.core.designsystem.theme.Radius
import com.ravango.core.designsystem.theme.RgTheme

/**
 * Placeholder block for loading layouts. Gently pulses, and stays still when the user (or a screenshot test) asks
 * for reduced motion.
 */
@Composable
internal fun SkeletonBlock(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(Radius.xs)) {
    val alpha = if (RgTheme.reduceMotion) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "skeleton")
        val a by transition.animateFloat(0.55f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha")
        a
    }
    Box(modifier.graphicsLayer { this.alpha = alpha }.clip(shape).background(RgTheme.colors.surfaceMuted))
}
