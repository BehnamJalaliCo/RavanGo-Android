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
            RgIconButton(Icons.AutoMirrored.Rounded.ArrowBack, backContentDescription, onBack, container = RgTheme.colors.surface.copy(alpha = 0.7f))
            Spacer(Modifier.width(Spacing.md))
        } else {
            Spacer(Modifier.width(Spacing.sm))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = RgTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = RgTheme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically, content = actions)
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

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = RgTheme.colors.textPrimary, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) RgTextButton(action, onAction)
    }
}

/** Settings/list row with icon bubble, title, subtitle and trailing content (chevron by default). */
@Composable
fun RgListItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = RgTheme.colors.accent,
    iconBackground: Color = RgTheme.colors.accentSoft,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = if (onClick != null) ({ Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = RgTheme.colors.textTertiary) }) else null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .then(if (onClick != null) Modifier.pressable(onClick = onClick) else Modifier)
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(Radius.sm)).background(iconBackground), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = iconTint, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(Spacing.md))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = RgTheme.colors.textPrimary)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = RgTheme.colors.textSecondary)
        }
        if (trailing != null) {
            Spacer(Modifier.width(Spacing.sm))
            trailing()
        }
    }
}

/** Grouped card of list rows (iOS-style inset group, pastel). */
@Composable
fun RgGroup(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(horizontal = Spacing.gutter)) {
        if (title != null) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = RgTheme.colors.textSecondary, modifier = Modifier.padding(start = Spacing.sm, bottom = Spacing.sm, top = Spacing.md))
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
    Column(
        modifier.fillMaxWidth().padding(Spacing.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(88.dp).clip(CircleShape).background(RgTheme.colors.brandGradientSoft),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = RgTheme.colors.accent, modifier = Modifier.size(40.dp)) }
        Spacer(Modifier.height(Spacing.xl))
        Text(title, style = MaterialTheme.typography.titleLarge, color = RgTheme.colors.textPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Spacing.sm))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = RgTheme.colors.textSecondary, textAlign = TextAlign.Center)
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(Spacing.xl))
            RgPrimaryButton(actionText, onAction, size = RgButtonSize.MEDIUM)
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
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 40.dp, height = 5.dp).clip(CircleShape).background(colors.outlineStrong))
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
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary) },
        confirmButton = { RgTextButton(confirmText, onConfirm, color = if (destructive) colors.danger else colors.accent) },
        dismissButton = { RgTextButton(dismissText, onDismiss, color = colors.textSecondary) },
    )
}

/** Animated shimmer placeholder for loading content. */
@Composable
fun ShimmerBox(modifier: Modifier = Modifier, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(Radius.md)) {
    val colors = RgTheme.colors
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(-1f, 2f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "x")
    Box(
        modifier
            .clip(shape)
            .background(colors.surfaceMuted)
            .background(
                Brush.linearGradient(
                    listOf(Color.Transparent, colors.glassHighlight.copy(alpha = 0.6f), Color.Transparent),
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
