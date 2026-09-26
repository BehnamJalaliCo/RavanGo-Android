package com.ravango.core.designsystem.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ravango.core.designsystem.theme.Dimens
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
            Text(title, style = MaterialTheme.typography.titleSmall, color = RgTheme.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
    Column(
        modifier.fillMaxWidth().padding(horizontal = Spacing.xxxl, vertical = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Halo + gradient disc: a soft, layered illustration instead of a flat circle.
        Box(Modifier.size(112.dp).clip(CircleShape).background(colors.accentSoft.copy(alpha = if (colors.isDark) 0.35f else 0.5f)), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(84.dp).clip(CircleShape).background(colors.brandGradientSoft),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = colors.accent, modifier = Modifier.size(36.dp)) }
        }
        Spacer(Modifier.height(Spacing.xl))
        Text(title, style = MaterialTheme.typography.titleLarge, color = colors.textPrimary, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 320.dp))
        Spacer(Modifier.height(Spacing.sm))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 320.dp))
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(Spacing.xxl))
            RgPrimaryButton(actionText, onAction, size = RgButtonSize.LARGE)
        }
    }
}

/** Bottom sheet with RavanGo styling. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RgBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    dark: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = RgTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
        containerColor = if (dark) Color(0xF2141220) else colors.backgroundElevated,
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
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.backgroundElevated,
        shape = RoundedCornerShape(Radius.xl),
        title = { Text(title, style = MaterialTheme.typography.titleLarge, color = colors.textPrimary) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary) },
        confirmButton = { RgTextButton(confirmText, onConfirm, color = if (destructive) colors.danger else colors.accent) },
        dismissButton = { RgTextButton(dismissText, onDismiss, color = colors.textSecondary) },
    )
}

/** Animated shimmer placeholder for loading content. */
@Composable
fun ShimmerBox(modifier: Modifier = Modifier, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(Radius.md)) {
    val colors = RgTheme.colors
    // Static sheen when the user asked for reduced motion.
    val x = if (RgTheme.reduceMotion) {
        0.3f
    } else {
        val transition = rememberInfiniteTransition(label = "shimmer")
        val animated by transition.animateFloat(-1f, 2f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "x")
        animated
    }
    Box(
        modifier
            .clip(shape)
            .background(colors.surfaceMuted)
            .background(
                Brush.linearGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = if (colors.isDark) 0.06f else 0.55f), Color.Transparent),
                    start = androidx.compose.ui.geometry.Offset(x * 600f, 0f),
                    end = androidx.compose.ui.geometry.Offset(x * 600f + 400f, 400f),
                ),
            ),
    )
}

/** Full-screen centered loading state. */
@Composable
fun LoadingState(modifier: Modifier = Modifier, message: String? = null) {
    Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        androidx.compose.material3.CircularProgressIndicator(color = RgTheme.colors.accent, strokeWidth = 3.dp)
        if (message != null) {
            Spacer(Modifier.height(Spacing.md))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = RgTheme.colors.textSecondary)
        }
    }
}

/** Linear progress bar with gradient fill. */
@Composable
fun RgProgressBar(progress: Float, modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 6.dp, trackColor: Color = RgTheme.colors.surfaceMuted) {
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(Radius.pill)).background(trackColor)) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(height)
                .clip(RoundedCornerShape(Radius.pill))
                .background(RgTheme.colors.brandGradient),
        )
    }
}

@Composable
fun rememberSnackbarHostState(): SnackbarHostState = remember { SnackbarHostState() }
