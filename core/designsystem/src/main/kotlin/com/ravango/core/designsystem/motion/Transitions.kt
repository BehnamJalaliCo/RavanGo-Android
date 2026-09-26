package com.ravango.core.designsystem.motion

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.ravango.core.designsystem.theme.Motion
import com.ravango.core.designsystem.theme.RgTheme

/*
 * Transition vocabulary (Material motion patterns, tuned for RavanGo):
 *  - Shared axis X  — hierarchical navigation (list → detail, settings → sub-page). Direction follows the layout
 *                      direction, so "forward" moves right-to-left in Persian and left-to-right in English.
 *  - Fade through   — peer or mode switches (tab content, studio screens, state changes inside a card).
 *  - Slide up       — modal destinations (paywall, sign-in): enter from the bottom, leave the same way.
 * Offsets use springs (interruptible, seekable for predictive back); fades are short tweens. With reduce motion every
 * pattern degrades to a quick cross-fade, which carries no motion.
 */

private val OffsetSpring = spring(dampingRatio = 1f, stiffness = 420f, visibilityThreshold = IntOffset.VisibilityThreshold)
private val ScaleSpring = spring(dampingRatio = 1f, stiffness = 420f, visibilityThreshold = 0.001f)
private const val AXIS_OFFSET = 0.14f
private const val AXIS_OFFSET_BEHIND = 0.08f

/** Navigation transitions. Call from a NavHost's transition lambdas (their receiver is an [AnimatedContentTransitionScope]). */
object RgTransitions {

    private fun reduced(): EnterTransition = fadeIn(tween(Motion.Duration.Short))
    private fun reducedExit(): ExitTransition = fadeOut(tween(Motion.Duration.Instant))

    /** Shared axis X, forward: new screen slides in from the end edge while fading in. */
    fun <S> AnimatedContentTransitionScope<S>.sharedAxisEnter(reduceMotion: Boolean): EnterTransition =
        if (reduceMotion) reduced() else slideIntoContainer(
            AnimatedContentTransitionScope.SlideDirection.Start,
            animationSpec = OffsetSpring,
            initialOffset = { (it * AXIS_OFFSET).toInt() },
        ) + fadeIn(tween(Motion.Duration.Medium, delayMillis = 40, easing = Motion.DecelerateEasing))

    /** Shared axis X, forward: the old screen drifts a little toward the start edge and fades out quickly. */
    fun <S> AnimatedContentTransitionScope<S>.sharedAxisExit(reduceMotion: Boolean): ExitTransition =
        if (reduceMotion) reducedExit() else slideOutOfContainer(
            AnimatedContentTransitionScope.SlideDirection.Start,
            animationSpec = OffsetSpring,
            targetOffset = { (it * AXIS_OFFSET_BEHIND).toInt() },
        ) + fadeOut(tween(Motion.Duration.Short, easing = Motion.AccelerateEasing))

    /** Shared axis X, back (also driven by the predictive-back gesture). */
    fun <S> AnimatedContentTransitionScope<S>.sharedAxisPopEnter(reduceMotion: Boolean): EnterTransition =
        if (reduceMotion) reduced() else slideIntoContainer(
            AnimatedContentTransitionScope.SlideDirection.End,
            animationSpec = OffsetSpring,
            initialOffset = { (it * AXIS_OFFSET_BEHIND).toInt() },
        ) + fadeIn(tween(Motion.Duration.Medium, delayMillis = 40, easing = Motion.DecelerateEasing))

    fun <S> AnimatedContentTransitionScope<S>.sharedAxisPopExit(reduceMotion: Boolean): ExitTransition =
        if (reduceMotion) reducedExit() else slideOutOfContainer(
            AnimatedContentTransitionScope.SlideDirection.End,
            animationSpec = OffsetSpring,
            targetOffset = { (it * AXIS_OFFSET).toInt() },
        ) + fadeOut(tween(Motion.Duration.Short, easing = Motion.AccelerateEasing))

    /** Fade through: incoming content fades in with a gentle scale-up after the outgoing one has mostly faded. */
    fun fadeThroughEnter(reduceMotion: Boolean): EnterTransition =
        if (reduceMotion) reduced() else fadeIn(tween(Motion.Duration.Medium, delayMillis = 70, easing = Motion.DecelerateEasing)) +
            scaleIn(ScaleSpring, initialScale = 0.94f)

    fun fadeThroughExit(reduceMotion: Boolean): ExitTransition =
        if (reduceMotion) reducedExit() else fadeOut(tween(Motion.Duration.Instant, easing = Motion.AccelerateEasing))

    /** Fade through, back: the revealed screen settles from slightly larger. */
    fun fadeThroughPopEnter(reduceMotion: Boolean): EnterTransition =
        if (reduceMotion) reduced() else fadeIn(tween(Motion.Duration.Medium, delayMillis = 40)) + scaleIn(ScaleSpring, initialScale = 1.04f)

    fun fadeThroughPopExit(reduceMotion: Boolean): ExitTransition =
        if (reduceMotion) reducedExit() else fadeOut(tween(Motion.Duration.Short)) + scaleOut(ScaleSpring, targetScale = 0.94f)

    /** Modal: slides up from the bottom. */
    fun modalEnter(reduceMotion: Boolean): EnterTransition =
        if (reduceMotion) reduced() else slideInVertically(OffsetSpring) { (it * 0.18f).toInt() } +
            fadeIn(tween(Motion.Duration.Medium, easing = Motion.DecelerateEasing))

    fun modalPopExit(reduceMotion: Boolean): ExitTransition =
        if (reduceMotion) reducedExit() else slideOutVertically(OffsetSpring) { (it * 0.18f).toInt() } +
            fadeOut(tween(Motion.Duration.Short, easing = Motion.AccelerateEasing))

    /** What the screen underneath a modal does: stays put and dims slightly via fade. */
    fun underModalExit(reduceMotion: Boolean): ExitTransition =
        if (reduceMotion) reducedExit() else fadeOut(tween(Motion.Duration.Long), targetAlpha = 0.6f)

    fun underModalPopEnter(reduceMotion: Boolean): EnterTransition =
        if (reduceMotion) reduced() else fadeIn(tween(Motion.Duration.Medium), initialAlpha = 0.6f)
}

private infix fun ContentTransform.withSize(sizeTransform: SizeTransform): ContentTransform =
    ContentTransform(targetContentEnter, initialContentExit, targetContentZIndex, sizeTransform)

/** Fade-through [ContentTransform] for [AnimatedContent] state changes (loading → content, tab A → tab B …). */
fun rgFadeThrough(reduceMotion: Boolean, clipSize: Boolean = false): ContentTransform {
    if (reduceMotion) return (EnterTransition.None togetherWith ExitTransition.None) withSize SizeTransform(clipSize) { _, _ -> snap() }
    val transform = (fadeIn(tween(Motion.Duration.Medium, delayMillis = 60, easing = Motion.DecelerateEasing)) + scaleIn(ScaleSpring, initialScale = 0.96f)) togetherWith
        fadeOut(tween(Motion.Duration.Instant))
    return transform withSize SizeTransform(clip = clipSize) { _, _ -> spring(dampingRatio = 1f, stiffness = 420f, visibilityThreshold = IntSize.VisibilityThreshold) }
}

/** Vertical "ticker" [ContentTransform]: new content rises in (or drops in when [up] is false). For values and labels. */
fun rgVerticalTicker(reduceMotion: Boolean, up: Boolean = true): ContentTransform {
    if (reduceMotion) return (EnterTransition.None togetherWith ExitTransition.None) withSize SizeTransform(false) { _, _ -> snap() }
    val dir = if (up) 1 else -1
    val transform = (slideInVertically(spring(dampingRatio = 0.8f, stiffness = 520f, visibilityThreshold = IntOffset.VisibilityThreshold)) { dir * it } + fadeIn(tween(Motion.Duration.Short))) togetherWith
        (slideOutVertically(spring(dampingRatio = 1f, stiffness = 700f, visibilityThreshold = IntOffset.VisibilityThreshold)) { -dir * it } + fadeOut(tween(Motion.Duration.Instant)))
    return transform withSize SizeTransform(clip = false)
}

/**
 * [AnimatedContent] with the RavanGo fade-through (spring size change, no clipping). With reduce motion the content
 * swaps instantly.
 */
@Composable
fun <S> RgAnimatedContent(
    targetState: S,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    label: String = "RgAnimatedContent",
    contentKey: (targetState: S) -> Any? = { it },
    content: @Composable AnimatedContentScope.(targetState: S) -> Unit,
) {
    val reduceMotion = RgTheme.reduceMotion
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        transitionSpec = { rgFadeThrough(reduceMotion) },
        contentAlignment = contentAlignment,
        label = label,
        contentKey = contentKey,
        content = content,
    )
}

/** Enter/exit presets for [androidx.compose.animation.AnimatedVisibility] that honour reduce motion. */
object RgEnter {
    /** Pops in (badges, check marks, FABs). */
    @Composable
    fun pop(): EnterTransition = if (RgTheme.reduceMotion) fadeIn(tween(Motion.Duration.Short)) else
        scaleIn(spring(dampingRatio = 0.6f, stiffness = 500f), initialScale = 0.6f) + fadeIn(tween(Motion.Duration.Short))

    /** Expands vertically (inline panels, search fields, banners). */
    @Composable
    fun expand(): EnterTransition = if (RgTheme.reduceMotion) fadeIn(tween(Motion.Duration.Short)) else
        expandVertically(spring(dampingRatio = 1f, stiffness = 500f, visibilityThreshold = IntSize.VisibilityThreshold), expandFrom = Alignment.Top) +
            fadeIn(tween(Motion.Duration.Medium, delayMillis = 40))

    /** Rises from below (bottom bars, snackbars, floating actions). */
    @Composable
    fun rise(): EnterTransition = if (RgTheme.reduceMotion) fadeIn(tween(Motion.Duration.Short)) else
        slideInVertically(spring(dampingRatio = 0.85f, stiffness = 450f, visibilityThreshold = IntOffset.VisibilityThreshold)) { it / 2 } + fadeIn(tween(Motion.Duration.Short))

    @Composable
    fun fade(): EnterTransition = fadeIn(tween(if (RgTheme.reduceMotion) Motion.Duration.Short else Motion.Duration.Medium))
}

object RgExit {
    @Composable
    fun pop(): ExitTransition = if (RgTheme.reduceMotion) fadeOut(tween(Motion.Duration.Instant)) else
        scaleOut(spring(dampingRatio = 1f, stiffness = 700f), targetScale = 0.6f) + fadeOut(tween(Motion.Duration.Instant))

    @Composable
    fun collapse(): ExitTransition = if (RgTheme.reduceMotion) fadeOut(tween(Motion.Duration.Instant)) else
        shrinkVertically(spring(dampingRatio = 1f, stiffness = 700f, visibilityThreshold = IntSize.VisibilityThreshold), shrinkTowards = Alignment.Top) +
            fadeOut(tween(Motion.Duration.Instant))

    @Composable
    fun sink(): ExitTransition = if (RgTheme.reduceMotion) fadeOut(tween(Motion.Duration.Instant)) else
        slideOutVertically(spring(dampingRatio = 1f, stiffness = 700f, visibilityThreshold = IntOffset.VisibilityThreshold)) { it / 2 } + fadeOut(tween(Motion.Duration.Instant))

    @Composable
    fun fade(): ExitTransition = fadeOut(tween(Motion.Duration.Short))
}
