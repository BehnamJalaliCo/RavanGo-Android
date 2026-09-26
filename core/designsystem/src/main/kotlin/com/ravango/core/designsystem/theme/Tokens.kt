package com.ravango.core.designsystem.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
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

/**
 * Motion tokens. Springs for anything spatial (position, scale, size) so motion is interruptible and physical; short
 * tweens only for pure effects (alpha, color). Every composable helper in `designsystem.motion` honours
 * [RgTheme.reduceMotion] by snapping or cross-fading instead of moving.
 */
object Motion {
    /** Material "emphasized" curve: quick start, long gentle settle. */
    val EmphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val StandardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    /** Enter curve: arrives fast and glides to rest. */
    val DecelerateEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    /** Exit curve: leaves with intent. */
    val AccelerateEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** Durations (ms) for tween-based effects. */
    object Duration {
        const val Instant = 90
        const val Short = 160
        const val Medium = 260
        const val Long = 380
        const val ExtraLong = 520
    }

    /** Stagger for list/grid entrances: step per item, capped so long lists never feel slow. */
    const val StaggerStepMs = 45
    const val StaggerMaxMs = 360

    fun <T> snappy(): AnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
    fun <T> bouncy(): AnimationSpec<T> = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
    fun <T> gentle(): AnimationSpec<T> = spring(dampingRatio = 1f, stiffness = Spring.StiffnessLow)
    fun <T> quick(durationMs: Int = 180): AnimationSpec<T> = tween(durationMs, easing = StandardEasing)
    fun <T> emphasized(durationMs: Int = 420): AnimationSpec<T> = tween(durationMs, easing = EmphasizedEasing)

    /** Default spatial spring (screen content, cards, indicators): critically damped-ish, no visible overshoot. */
    fun <T> spatial(): SpringSpec<T> = spring(dampingRatio = 0.9f, stiffness = 380f)
    /** Small elements (chips, thumbs, badges): a hint of overshoot that reads as "alive". */
    fun <T> spatialBouncy(): SpringSpec<T> = spring(dampingRatio = 0.62f, stiffness = 520f)
    /** Fast spatial spring for indicators that follow a selection. */
    fun <T> spatialFast(): SpringSpec<T> = spring(dampingRatio = 0.82f, stiffness = 700f)
    /** Effects spring (alpha / color) without overshoot. */
    fun <T> effects(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 1600f)

    /** Scale applied to pressed interactive surfaces. */
    const val PressScale = 0.96f
    /** Scale for large pressed surfaces (hero cards, big tiles) so they don't "shrink" too much. */
    const val PressScaleLarge = 0.98f
}

/**
 * Component size scale. Every control in a row shares one of these heights so mixed rows (chip + button + icon)
 * line up: 32 (chips, small buttons), 40 (medium buttons, segmented control, compact icon buttons),
 * 48 (large buttons, standard touch target), 56 (hero calls to action).
 */
object Dimens {
    val controlSmall = 32.dp
    val controlMedium = 40.dp
    val controlLarge = 48.dp
    val controlHero = 56.dp
    /** Minimum touch target (Material / WCAG 2.5.8). Smaller visuals still get this hit area from Compose. */
    val touchTarget = 48.dp
    val iconSmall = 16.dp
    val iconMedium = 20.dp
    val icon = 24.dp
    /** Icon "bubble" leading list rows. */
    val listIcon = 40.dp
    /** Minimum height of a single-line list row; two-line rows grow to ~64dp. */
    val listRowMin = 56.dp
    val hairline = 1.dp
}

object Elevation {
    val none = 0.dp
    val low = 2.dp
    val mid = 8.dp
    val high = 18.dp
}
