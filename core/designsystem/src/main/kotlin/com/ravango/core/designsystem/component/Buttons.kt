package com.ravango.core.designsystem.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ravango.core.designsystem.theme.HapticEvent
import com.ravango.core.designsystem.theme.Motion
import com.ravango.core.designsystem.theme.Radius
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing

enum class RgButtonSize(val height: Dp, val hPadding: Dp) { SMALL(36.dp, 14.dp), MEDIUM(48.dp, 20.dp), LARGE(56.dp, 24.dp) }

/** Primary call to action: brand gradient pill with glow. */
@Composable
fun RgPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    size: RgButtonSize = RgButtonSize.LARGE,
    brush: Brush? = null,
) {
    val colors = RgTheme.colors
    val alpha by animateFloatAsState(if (enabled) 1f else 0.45f, Motion.quick(), label = "alpha")
    val shape = RoundedCornerShape(Radius.pill)
    Box(
        modifier
            .defaultMinSize(minHeight = size.height)
            .alpha(alpha)
            .shadow(if (enabled) 14.dp else 0.dp, shape, ambientColor = colors.accent.copy(alpha = 0.35f), spotColor = colors.accent.copy(alpha = 0.45f))
            .clip(shape)
            .background(brush ?: colors.brandGradient)
            .pressable(enabled = enabled && !loading, haptic = HapticEvent.CONFIRM, onClick = onClick)
            .padding(horizontal = size.hPadding),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(loading, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "loading") { isLoading ->
            if (isLoading) {
                CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.5.dp)
            } else {
                ButtonContent(text, icon, Color.White)
            }
        }
    }
}

/** Secondary action: soft tinted pill. */
@Composable
fun RgSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    size: RgButtonSize = RgButtonSize.LARGE,
    containerColor: Color = RgTheme.colors.accentSoft,
    contentColor: Color = RgTheme.colors.accent,
) {
    val alpha by animateFloatAsState(if (enabled) 1f else 0.45f, Motion.quick(), label = "alpha")
    Box(
        modifier
            .defaultMinSize(minHeight = size.height)
            .alpha(alpha)
            .clip(RoundedCornerShape(Radius.pill))
            .background(containerColor)
            .pressable(enabled = enabled, onClick = onClick)
            .padding(horizontal = size.hPadding),
        contentAlignment = Alignment.Center,
    ) { ButtonContent(text, icon, contentColor) }
}

/** Outlined, low-emphasis action. */
@Composable
fun RgOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    size: RgButtonSize = RgButtonSize.MEDIUM,
    contentColor: Color = RgTheme.colors.textPrimary,
) {
    val shape = RoundedCornerShape(Radius.pill)
    Box(
        modifier
            .defaultMinSize(minHeight = size.height)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .border(1.dp, RgTheme.colors.outlineStrong, shape)
            .pressable(enabled = enabled, onClick = onClick)
            .padding(horizontal = size.hPadding),
        contentAlignment = Alignment.Center,
    ) { ButtonContent(text, icon, contentColor) }
}

@Composable
fun RgTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = RgTheme.colors.accent, enabled: Boolean = true) {
    Box(
        modifier
            .defaultMinSize(minHeight = 40.dp)
            .clip(RoundedCornerShape(Radius.pill))
            .pressable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) color else color.copy(alpha = 0.4f))
    }
}

@Composable
private fun ButtonContent(text: String, icon: ImageVector?, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Spacing.sm))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Circular icon button with a soft container. Use [glass] over camera/video. */
@Composable
fun RgIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
    glass: Boolean = false,
    selected: Boolean = false,
    enabled: Boolean = true,
    tint: Color? = null,
    container: Color? = null,
) {
    val colors = RgTheme.colors
    val bg = when {
        container != null -> container
        selected -> colors.accent
        glass -> Color.Black.copy(alpha = 0.32f)
        else -> colors.surfaceMuted
    }
    val fg = tint ?: when {
        selected -> colors.onAccent
        glass -> Color.White
        else -> colors.textPrimary
    }
    Box(
        modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .background(bg)
            .then(if (glass) Modifier.border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape) else Modifier)
            .pressable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = fg, modifier = Modifier.size(iconSize))
    }
}

/** Small rounded label with a gradient, used to mark Pro features. */
@Composable
fun ProBadge(modifier: Modifier = Modifier, text: String = "PRO") {
    Box(
        modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(RgTheme.colors.proGradient)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}

/** Pill tag for metadata. */
@Composable
fun RgTag(text: String, modifier: Modifier = Modifier, color: Color = RgTheme.colors.accentSoft, contentColor: Color = RgTheme.colors.accent, icon: ImageVector? = null) {
    Row(
        modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(SolidColor(color))
            .height(26.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = contentColor, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = contentColor, maxLines = 1)
    }
}
