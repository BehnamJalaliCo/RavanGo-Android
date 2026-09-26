package com.ravango.core.designsystem.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.ravango.core.designsystem.theme.ButtonText
import com.ravango.core.designsystem.theme.Dimens
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ravango.core.designsystem.theme.HapticEvent
import com.ravango.core.designsystem.theme.Motion
import com.ravango.core.designsystem.theme.Radius
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing

/**
 * Button heights follow [com.ravango.core.designsystem.theme.Dimens]: SMALL 32 (pairs with chips), MEDIUM 40 (pairs with
 * segmented controls and compact icon buttons), LARGE 48 (standard CTA), HERO 56 (one per screen: onboarding, paywall).
 */
enum class RgButtonSize(val height: Dp, val hPadding: Dp) {
    SMALL(32.dp, 14.dp),
    MEDIUM(40.dp, 18.dp),
    LARGE(48.dp, 22.dp),
    HERO(56.dp, 28.dp),
    ;

    internal val iconSize: Dp get() = when (this) { SMALL -> 16.dp; MEDIUM -> 18.dp; LARGE, HERO -> 20.dp }
    internal val iconGap: Dp get() = if (this == SMALL) 6.dp else Spacing.sm
    internal val textStyle: TextStyle get() = when (this) {
        SMALL -> ButtonText.small
        MEDIUM -> ButtonText.medium
        LARGE -> ButtonText.large
        HERO -> ButtonText.hero
    }
}

private val PillShape = RoundedCornerShape(Radius.pill)

/** Primary call to action: brand gradient pill with a soft colored glow. Disabled: flat muted pill (no faded gradient). */
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
    /** Label/icon color; defaults to white. Pass a dark ink for light brushes (e.g. the gold Pro gradient). */
    contentColor: Color? = null,
) {
    val colors = RgTheme.colors
    val content = if (enabled) contentColor ?: Color.White else colors.textTertiary
    Box(
        modifier
            .defaultMinSize(minHeight = size.height)
            .softShadow(if (enabled && size >= RgButtonSize.LARGE) 12.dp else if (enabled) 6.dp else 0.dp, PillShape, colors.accent)
            .clip(PillShape)
            .then(if (enabled) Modifier.background(brush ?: colors.ctaGradient) else Modifier.background(colors.surfaceMuted))
            .pressable(
                shape = PillShape,
                enabled = enabled && !loading,
                haptic = HapticEvent.CONFIRM,
                pressedOverlay = Color.Black.copy(alpha = 0.10f),
                onClick = onClick,
            )
            .padding(horizontal = size.hPadding),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(loading, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "loading") { isLoading ->
            if (isLoading) {
                CircularProgressIndicator(Modifier.size(size.iconSize + 2.dp), color = content, strokeWidth = 2.5.dp)
            } else {
                ButtonContent(text, icon, content, size)
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
    contentColor: Color = RgTheme.colors.onAccentSoft,
) {
    val alpha by animateFloatAsState(if (enabled) 1f else 0.45f, Motion.quick(), label = "alpha")
    Box(
        modifier
            .defaultMinSize(minHeight = size.height)
            .alpha(alpha)
            .clip(PillShape)
            .background(containerColor)
            .pressable(shape = PillShape, enabled = enabled, onClick = onClick)
            .padding(horizontal = size.hPadding),
        contentAlignment = Alignment.Center,
    ) { ButtonContent(text, icon, contentColor, size) }
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
    val colors = RgTheme.colors
    Box(
        modifier
            .defaultMinSize(minHeight = size.height)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(PillShape)
            .background(colors.surface.copy(alpha = if (colors.isDark) 0.4f else 0.7f))
            .border(1.dp, colors.outlineStrong, PillShape)
            .pressable(shape = PillShape, enabled = enabled, onClick = onClick)
            .padding(horizontal = size.hPadding),
        contentAlignment = Alignment.Center,
    ) { ButtonContent(text, icon, contentColor, size) }
}

@Composable
fun RgTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = RgTheme.colors.accent, enabled: Boolean = true) {
    Box(
        modifier
            .defaultMinSize(minHeight = Dimens.controlMedium)
            .clip(PillShape)
            .pressable(shape = PillShape, enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = ButtonText.medium, color = if (enabled) color else color.copy(alpha = 0.4f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ButtonContent(text: String, icon: ImageVector?, color: Color, size: RgButtonSize) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(size.iconSize))
            Spacer(Modifier.width(size.iconGap))
        }
        Text(text, style = size.textStyle, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Circular icon button with a soft container. Use [glass] over camera/video. Visual sizes 40/44/48; anything smaller
 * than 48dp still receives a 48dp touch area from Compose's minimum-touch-target handling.
 */
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
    val bg by animateColorAsState(
        when {
            container != null -> container
            selected -> colors.accent
            glass -> Color.Black.copy(alpha = 0.34f)
            else -> colors.surfaceMuted
        },
        Motion.quick(), label = "iconBg",
    )
    val fg = tint ?: when {
        selected -> colors.onAccent
        glass -> Color.White
        else -> colors.textPrimary
    }
    val stroke = when {
        glass -> Color.White.copy(alpha = 0.18f)
        selected -> Color.Transparent
        colors.isDark && container == null -> Color.White.copy(alpha = 0.06f)
        else -> Color.Transparent
    }
    Box(
        modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, stroke, CircleShape)
            .pressable(shape = CircleShape, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = fg, modifier = Modifier.size(iconSize))
    }
}

/** Small rounded label with a gradient, used to mark Pro features. Fixed 20dp height so it centers in any row. */
@Composable
fun ProBadge(modifier: Modifier = Modifier, text: String = "PRO") {
    Box(
        modifier
            .height(20.dp)
            .clip(PillShape)
            .background(RgTheme.colors.proGradient)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.06.em, lineHeight = 14.sp),
            color = Color.White,
            maxLines = 1,
        )
    }
}

/** Pill tag for metadata (24dp). */
@Composable
fun RgTag(text: String, modifier: Modifier = Modifier, color: Color = RgTheme.colors.accentSoft, contentColor: Color = RgTheme.colors.onAccentSoft, icon: ImageVector? = null) {
    Row(
        modifier
            .height(24.dp)
            .clip(PillShape)
            .background(SolidColor(color))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = contentColor, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium.copy(lineHeight = 16.sp), color = contentColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
