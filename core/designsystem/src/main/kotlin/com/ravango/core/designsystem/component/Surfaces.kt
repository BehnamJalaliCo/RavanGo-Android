package com.ravango.core.designsystem.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ravango.core.designsystem.theme.HapticEvent
import com.ravango.core.designsystem.theme.Motion
import com.ravango.core.designsystem.theme.Radius
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.core.designsystem.theme.rememberHaptics
import kotlin.math.cos
import kotlin.math.sin

/**
 * Soft, slowly drifting pastel blobs behind content. Static when the user enables "reduce motion".
 */
@Composable
fun GradientBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = RgTheme.colors
    val reduceMotion = RgTheme.reduceMotion
    val phase = if (reduceMotion) {
        0.25f
    } else {
        val transition = rememberInfiniteTransition(label = "blobs")
        val p by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Restart),
            label = "phase",
        )
        p
    }
    Box(
        modifier
            .fillMaxSize()
            .background(colors.background)
            .drawBehind {
                val w = size.width
                val h = size.height
                val alpha = if (colors.isDark) 0.55f else 0.85f
                colors.blobs.forEachIndexed { i, c ->
                    val angle = (phase + i * 0.25f) * 2f * Math.PI.toFloat()
                    val cx = w * (0.2f + 0.6f * ((i % 2) + 0.35f * cos(angle)) / 1.35f)
                    val cy = h * (0.12f + 0.22f * i + 0.05f * sin(angle * 1.3f))
                    val r = maxOf(w, h) * (0.38f + 0.04f * sin(angle))
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(c.copy(alpha = alpha), c.copy(alpha = 0f)),
                            center = Offset(cx, cy),
                            radius = r,
                        ),
                        radius = r,
                        center = Offset(cx, cy),
                    )
                }
            },
        content = content,
    )
}

/**
 * Frosted "glass" surface: translucent fill, luminous edge and a top highlight. Works on every API level
 * (no RenderEffect dependency) and reads well over camera preview and gradients.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Radius.lg),
    contentPadding: PaddingValues = PaddingValues(Spacing.lg),
    tint: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = RgTheme.colors
    Box(
        modifier
            .clip(shape)
            .background(tint ?: colors.glassFill)
            .background(Brush.verticalGradient(listOf(colors.glassHighlight, Color.Transparent)))
            .border(1.dp, Brush.verticalGradient(listOf(colors.glassStroke, colors.glassStroke.copy(alpha = 0.05f))), shape)
            .padding(contentPadding),
        content = content,
    )
}

/** Adds spring press-scale + haptic tick to any clickable surface. */
fun Modifier.pressable(
    enabled: Boolean = true,
    role: Role = Role.Button,
    haptic: HapticEvent? = HapticEvent.TAP,
    onClick: () -> Unit,
): Modifier = pressable(shape = null, enabled = enabled, role = role, haptic = haptic, onClick = onClick)

/**
 * [pressable] that also knows the surface [shape], so it can draw a soft pressed overlay and a keyboard/D-pad
 * focus ring (2dp accent) that follows the outline. Used by every design-system control.
 */
fun Modifier.pressable(
    shape: Shape?,
    enabled: Boolean = true,
    role: Role = Role.Button,
    haptic: HapticEvent? = HapticEvent.TAP,
    pressedOverlay: Color? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) Motion.PressScale else 1f, Motion.snappy(), label = "press")
    val haptics = rememberHaptics()
    val colors = RgTheme.colors
    val overlay = pressedOverlay ?: (if (colors.isDark) Color.White.copy(alpha = 0.08f) else colors.textPrimary.copy(alpha = 0.06f))
    val overlayAlpha by animateFloatAsState(if (pressed && enabled && shape != null) 1f else 0f, Motion.quick(120), label = "overlay")
    val ring = colors.accent
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .then(
            if (shape == null) Modifier else Modifier.drawWithContent {
                drawContent()
                if (overlayAlpha > 0f) {
                    drawOutline(shape.createOutline(size, layoutDirection, this), overlay, alpha = overlayAlpha)
                }
                if (focused) {
                    drawOutline(shape.createOutline(size, layoutDirection, this), ring, style = Stroke(2.dp.toPx()))
                }
            },
        )
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = role,
        ) {
            haptic?.let(haptics::perform)
            onClick()
        }
}

/**
 * Soft, diffuse, tinted drop shadow (brand-colored in light mode, neutral in dark). Renders as a real platform
 * shadow; pass [elevation] from [com.ravango.core.designsystem.theme.Elevation].
 */
@Composable
fun Modifier.softShadow(elevation: Dp, shape: Shape, tint: Color = RgTheme.colors.shadowTint): Modifier =
    if (elevation <= 0.dp) this else this.shadow(elevation, shape, clip = false, ambientColor = tint.copy(alpha = 0.10f), spotColor = tint.copy(alpha = 0.22f))

/** Standard content card with soft shadow. */
@Composable
fun RgCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(Radius.lg),
    color: Color = RgTheme.colors.surface,
    elevation: Dp = 0.dp,
    border: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(Spacing.lg),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = RgTheme.colors
    var m = modifier.softShadow(elevation, shape)
    m = m.clip(shape).background(color)
    // Light mode: a hairline defines the white card against the pastel wash. Dark mode: a faint top-lit edge.
    if (border) {
        m = if (colors.isDark) {
            m.border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.09f), Color.White.copy(alpha = 0.03f))), shape)
        } else {
            m.border(1.dp, colors.outline, shape)
        }
    }
    if (onClick != null) m = m.pressable(shape = shape, onClick = onClick)
    Column(m.padding(contentPadding), content = content)
}
