package com.ravango.core.designsystem.component

import com.ravango.core.designsystem.theme.BalancedLines
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ravango.core.designsystem.motion.rememberEntranceActive
import com.ravango.core.designsystem.motion.staggeredEntrance
import com.ravango.core.designsystem.theme.Dimens
import com.ravango.core.designsystem.theme.Elevation
import com.ravango.core.designsystem.theme.Motion
import com.ravango.core.designsystem.theme.Radius
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing

/** Top app bar: large title, optional back button and actions. Transparent to let the gradient show through. */
@Composable
fun RgTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    backContentDescription: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = 64.dp)
            .padding(horizontal = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            // AutoMirrored icon flips automatically in RTL.
            RgIconButton(
                Icons.AutoMirrored.Rounded.ArrowBack,
                backContentDescription,
                onBack,
                container = if (RgTheme.colors.isDark) RgTheme.colors.surfaceMuted.copy(alpha = 0.8f) else RgTheme.colors.surface.copy(alpha = 0.8f),
            )
            Spacer(Modifier.width(Spacing.md))
        } else {
            Spacer(Modifier.width(Spacing.sm))
        }
        Column(Modifier.weight(1f).padding(vertical = Spacing.sm)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = RgTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = RgTheme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(
            Modifier.padding(start = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}

/** Standard screen frame: gradient background, top bar, snackbar host. */
@Composable
fun RgScreen(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    snackbarHostState: SnackbarHostState? = null,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    GradientBackground(modifier) {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0),
            topBar = { RgTopBar(title, subtitle = subtitle, onBack = onBack, actions = actions) },
            snackbarHost = { snackbarHostState?.let { SnackbarHost(it) } },
            floatingActionButton = floatingActionButton,
            bottomBar = bottomBar,
            content = content,
        )
    }
}

/**
 * Section title with an optional trailing action. The row always reserves the action's 40dp height, so sections with
 * and without an action keep the same vertical rhythm, and the action label is pulled to the gutter so its text lines
 * up with the cards below.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    subtitle: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = Spacing.gutter, end = Spacing.gutter - 12.dp, top = Spacing.xs, bottom = Spacing.xs)
            .heightIn(min = Dimens.controlMedium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = RgTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = RgTheme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (action != null && onAction != null) RgTextButton(action, onAction)
    }
}

/**
 * Settings/list row with icon bubble, title, subtitle and trailing content (chevron by default). Min 56dp; the icon
 * bubble, title block and trailing control are vertically centered; the pressed highlight is concentric with the
 * enclosing [RgGroup] card.
 */
@Composable
fun RgListItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = RgTheme.colors.accent,
    iconBackground: Color = RgTheme.colors.accentSoft,
    /** Title color; pass [RgColors.danger][com.ravango.core.designsystem.theme.RgColors.danger] for destructive rows. */
    titleColor: Color = RgTheme.colors.textPrimary,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = if (onClick != null) ({ Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = RgTheme.colors.textTertiary, modifier = Modifier.size(Dimens.iconMedium)) }) else null,
) {
    val shape = RoundedCornerShape(Radius.lg - Spacing.xs)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.listRowMin)
            .clip(shape)
            .then(if (onClick != null) Modifier.pressable(shape = shape, onClick = onClick) else Modifier)
            .padding(horizontal = Spacing.md, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(Modifier.size(Dimens.listIcon).clip(RoundedCornerShape(Radius.sm)).background(iconBackground), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = iconTint, modifier = Modifier.size(Dimens.iconMedium + 2.dp))
            }
            Spacer(Modifier.width(Spacing.md))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = titleColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = RgTheme.colors.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) {
            Spacer(Modifier.width(Spacing.md))
            trailing()
        }
    }
}

/**
 * Hairline separator for rows inside an [RgGroup]/[RgCard]. [inset] aligns it with the row text (skips the icon
 * bubble), iOS-style.
 */
@Composable
fun RgDivider(modifier: Modifier = Modifier, inset: Boolean = true) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = if (inset) Spacing.md + Dimens.listIcon + Spacing.md else Spacing.md, end = Spacing.md)
            .height(Dimens.hairline)
            .background(RgTheme.colors.divider),
    )
}

/** Grouped card of list rows (iOS-style inset group, pastel). */
@Composable
fun RgGroup(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(horizontal = Spacing.gutter)) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = RgTheme.colors.textSecondary,
                modifier = Modifier.padding(start = Spacing.md, end = Spacing.md, bottom = Spacing.sm, top = Spacing.lg),
            )
        }
        RgCard(contentPadding = PaddingValues(Spacing.xs), content = content)
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = RgTheme.colors
    val entrance = rememberEntranceActive()
    Column(
        modifier.fillMaxWidth().padding(horizontal = Spacing.xxxl, vertical = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Halo + gradient disc + a soft offset "echo" ring: a layered illustration instead of a flat circle.
        Box(Modifier.size(120.dp).staggeredEntrance(0, entrance), contentAlignment = Alignment.Center) {
            Box(Modifier.size(120.dp).clip(CircleShape).background(colors.accentSoft.copy(alpha = if (colors.isDark) 0.35f else 0.55f)))
            Box(
                Modifier.size(88.dp).softShadow(Elevation.mid, CircleShape, colors.accentGlow).clip(CircleShape).background(colors.brandGradientSoft)
                    .border(1.dp, Color.White.copy(alpha = if (colors.isDark) 0.08f else 0.7f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = colors.accent, modifier = Modifier.size(38.dp)) }
        }
        Spacer(Modifier.height(Spacing.xl))
        Column(Modifier.staggeredEntrance(1, entrance), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = colors.textPrimary, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 320.dp))
            Spacer(Modifier.height(Spacing.sm))
            Text(message, style = MaterialTheme.typography.bodyMedium.merge(BalancedLines), color = colors.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 320.dp))
        }
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(Spacing.xxl))
            RgPrimaryButton(actionText, onAction, size = RgButtonSize.LARGE, modifier = Modifier.staggeredEntrance(2, entrance))
        }
    }
}

/**
 * Bottom sheet with RavanGo styling. Opens fully expanded by default; pass [skipPartiallyExpanded] = false for tall,
 * scrollable content that should peek first. Callers need no experimental Material opt-in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RgBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    skipPartiallyExpanded: Boolean = true,
    dark: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = RgTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
        containerColor = if (dark) Color(0xF20C101C) else colors.backgroundElevated,
        shape = RoundedCornerShape(topStart = Radius.xl, topEnd = Radius.xl),
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 36.dp, height = 4.dp).clip(CircleShape).background(if (dark) Color.White.copy(alpha = 0.25f) else colors.outlineStrong))
        },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = Spacing.lg)) {
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = if (dark) Color.White else colors.textPrimary,
                    modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
                )
            }
            content()
        }
    }
}

/**
 * RavanGo dialog: a rounded, elevated card that springs in (scale + fade) over a dimmed scrim and animates out before
 * [onDismissRequest] is delivered. Buttons are laid out end-aligned; pass the confirm action last. Reduce motion swaps
 * the spring for a plain fade.
 */
@Composable
fun RgDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = RgTheme.colors.accent,
    iconBackground: Color = RgTheme.colors.accentSoft,
    buttons: (@Composable RowScope.(dismiss: (then: () -> Unit) -> Unit) -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = RgTheme.colors
    val reduceMotion = RgTheme.reduceMotion
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val dismiss: (() -> Unit) -> Unit = { then -> if (visible.targetState) { pending = then; visible.targetState = false } }
    LaunchedEffect(visible.currentState, visible.isIdle) {
        if (visible.isIdle && !visible.currentState && !visible.targetState) pending?.invoke()
    }
    Dialog(onDismissRequest = { dismiss(onDismissRequest) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        AnimatedVisibility(
            visibleState = visible,
            enter = if (reduceMotion) fadeIn(tween(Motion.Duration.Short)) else
                fadeIn(tween(Motion.Duration.Short)) + scaleIn(spring(dampingRatio = 0.72f, stiffness = 520f), initialScale = 0.86f),
            exit = if (reduceMotion) fadeOut(tween(Motion.Duration.Instant)) else
                fadeOut(tween(Motion.Duration.Short)) + scaleOut(tween(Motion.Duration.Short, easing = Motion.AccelerateEasing), targetScale = 0.92f),
        ) {
            val shape = RoundedCornerShape(Radius.xl)
            Column(
                modifier
                    .padding(horizontal = Spacing.xxl)
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .softShadow(Elevation.high, shape)
                    .clip(shape)
                    .background(colors.backgroundElevated)
                    .then(if (colors.isDark) Modifier.border(1.dp, Color.White.copy(alpha = 0.07f), shape) else Modifier)
                    .padding(start = Spacing.xxl, end = Spacing.xxl, top = Spacing.xxl, bottom = Spacing.lg),
            ) {
                if (icon != null) {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(Radius.md)).background(iconBackground), contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = iconTint, modifier = Modifier.size(Dimens.icon))
                    }
                    Spacer(Modifier.height(Spacing.lg))
                }
                if (title != null) {
                    Text(title, style = MaterialTheme.typography.titleLarge, color = colors.textPrimary)
                    Spacer(Modifier.height(Spacing.sm))
                }
                content()
                if (buttons != null) {
                    Spacer(Modifier.height(Spacing.lg))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { buttons(dismiss) }
                }
            }
        }
    }
}

@Composable
fun RgConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    val colors = RgTheme.colors
    RgDialog(
        onDismissRequest = onDismiss,
        title = title,
        buttons = { dismiss ->
            RgTextButton(dismissText, { dismiss(onDismiss) }, color = colors.textSecondary)
            if (destructive) {
                RgSecondaryButton(confirmText, { dismiss(onConfirm) }, size = RgButtonSize.MEDIUM, containerColor = colors.danger.copy(alpha = if (colors.isDark) 0.18f else 0.1f), contentColor = colors.danger)
            } else {
                RgPrimaryButton(confirmText, { dismiss(onConfirm) }, size = RgButtonSize.MEDIUM)
            }
        },
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
    }
}

/**
 * Animated shimmer placeholder for loading content. The sheen is positioned in window coordinates, so every shimmer
 * box on screen shows one continuous band sweeping across the layout (instead of each box flashing on its own). The
 * sweep is read in the draw phase and the gradient is built once per size: no recomposition or allocation per frame.
 * Static sheen with reduced motion.
 */
@Composable
fun ShimmerBox(modifier: Modifier = Modifier, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(Radius.md)) {
    val colors = RgTheme.colors
    val sweep: State<Float> = if (RgTheme.reduceMotion) {
        remember { mutableFloatStateOf(0.3f) }
    } else {
        rememberInfiniteTransition(label = "shimmer").animateFloat(
            -0.4f, 1.4f,
            infiniteRepeatable(tween(1_400, delayMillis = 200, easing = LinearEasing), RepeatMode.Restart),
            label = "x",
        )
    }
    val windowX = remember { floatArrayOf(0f) }
    val screenWidth = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val sheen = Color.White.copy(alpha = if (colors.isDark) 0.07f else 0.6f)
    Box(
        modifier
            .onPlaced { windowX[0] = it.positionInWindow().x }
            .clip(shape)
            .background(colors.surfaceMuted)
            .drawWithCache {
                val band = screenWidth * 0.45f
                val brush = Brush.horizontalGradient(listOf(Color.Transparent, sheen, Color.Transparent), startX = 0f, endX = band)
                onDrawBehind {
                    val x = -windowX[0] + sweep.value * (screenWidth + band) - band
                    translate(left = x) { drawRect(brush, size = androidx.compose.ui.geometry.Size(band, size.height)) }
                }
            },
    )
}

/** Skeleton text line (12–16dp tall pill) for loading layouts. */
@Composable
fun SkeletonLine(modifier: Modifier = Modifier, widthFraction: Float = 1f, height: androidx.compose.ui.unit.Dp = 12.dp) {
    ShimmerBox(modifier.fillMaxWidth(widthFraction).height(height), RoundedCornerShape(Radius.pill))
}

/** Skeleton of an [RgListItem]: icon bubble + two lines. */
@Composable
fun SkeletonListItem(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = Dimens.listRowMin).padding(horizontal = Spacing.md, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        ShimmerBox(Modifier.size(Dimens.listIcon), RoundedCornerShape(Radius.sm))
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            SkeletonLine(widthFraction = 0.55f, height = 14.dp)
            SkeletonLine(widthFraction = 0.8f, height = 10.dp)
        }
    }
}

/** Skeleton of a media card: rounded thumbnail plus title and meta lines. */
@Composable
fun SkeletonCard(modifier: Modifier = Modifier, thumbnailAspect: Float = 0.8f) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        ShimmerBox(Modifier.fillMaxWidth().aspectRatio(thumbnailAspect), RoundedCornerShape(Radius.lg))
        SkeletonLine(Modifier.padding(horizontal = Spacing.xs), widthFraction = 0.7f, height = 14.dp)
        SkeletonLine(Modifier.padding(horizontal = Spacing.xs), widthFraction = 0.4f, height = 10.dp)
    }
}

/**
 * Indeterminate progress ring. With "reduce motion" on it renders as a still three-quarter arc, so nothing spins
 * forever (and screenshot captures can settle).
 */
@Composable
fun RgSpinner(
    modifier: Modifier = Modifier,
    color: Color = RgTheme.colors.accent,
    strokeWidth: androidx.compose.ui.unit.Dp = 3.dp,
    trackColor: Color = Color.Transparent,
) {
    if (RgTheme.reduceMotion) {
        androidx.compose.material3.CircularProgressIndicator(
            progress = { 0.75f },
            modifier = modifier,
            color = color,
            strokeWidth = strokeWidth,
            trackColor = trackColor,
        )
    } else {
        androidx.compose.material3.CircularProgressIndicator(modifier = modifier, color = color, strokeWidth = strokeWidth, trackColor = trackColor)
    }
}

/** Full-screen centered loading state. */
@Composable
fun LoadingState(modifier: Modifier = Modifier, message: String? = null) {
    Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        RgSpinner(color = RgTheme.colors.accent, strokeWidth = 3.dp)
        if (message != null) {
            Spacer(Modifier.height(Spacing.md))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = RgTheme.colors.textSecondary)
        }
    }
}

/** Linear progress bar with gradient fill. The fill follows [progress] with a spring (snaps with reduce motion). */
@Composable
fun RgProgressBar(progress: Float, modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 6.dp, trackColor: Color = RgTheme.colors.surfaceMuted) {
    val target = progress.coerceIn(0f, 1f)
    val animated = if (RgTheme.reduceMotion) {
        remember(target) { mutableFloatStateOf(target) }
    } else {
        androidx.compose.animation.core.animateFloatAsState(target, spring(dampingRatio = 1f, stiffness = 200f), label = "progress")
    }
    val brush = RgTheme.colors.brandGradient
    Box(
        modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(Radius.pill)).background(trackColor).drawWithCache {
            val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
            onDrawBehind {
                val w = size.width * animated.value
                if (w > 0f) drawRoundRect(brush, size = androidx.compose.ui.geometry.Size(w.coerceAtLeast(size.height), size.height), cornerRadius = r)
            }
        },
    )
}

@Composable
fun rememberSnackbarHostState(): SnackbarHostState = remember { SnackbarHostState() }
