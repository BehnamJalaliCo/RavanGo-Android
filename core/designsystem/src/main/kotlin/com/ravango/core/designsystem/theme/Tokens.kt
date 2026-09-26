package com.ravango.core.designsystem.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Spacing scale (4dp grid). */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
    val huge = 48.dp
    /** Standard horizontal screen gutter. */
    val gutter = 20.dp
}

/** Soft, generous corner radii — a signature of the RavanGo look. */
object Radius {
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 22.dp
    val xl = 28.dp
    val xxl = 36.dp
    val pill = 999.dp
}

val RgShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.xs),
    small = RoundedCornerShape(Radius.sm),
    medium = RoundedCornerShape(Radius.md),
    large = RoundedCornerShape(Radius.lg),
    extraLarge = RoundedCornerShape(Radius.xl),
)

/** Motion tokens. Springs everywhere for a physical, responsive feel. */
object Motion {
    val EmphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val StandardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    fun <T> snappy(): AnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
    fun <T> bouncy(): AnimationSpec<T> = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
    fun <T> gentle(): AnimationSpec<T> = spring(dampingRatio = 1f, stiffness = Spring.StiffnessLow)
    fun <T> quick(durationMs: Int = 180): AnimationSpec<T> = tween(durationMs, easing = StandardEasing)
    fun <T> emphasized(durationMs: Int = 420): AnimationSpec<T> = tween(durationMs, easing = EmphasizedEasing)

    /** Scale applied to pressed interactive surfaces. */
    const val PressScale = 0.96f
}

object Elevation {
    val none = 0.dp
    val low = 2.dp
    val mid = 8.dp
    val high = 18.dp
}
