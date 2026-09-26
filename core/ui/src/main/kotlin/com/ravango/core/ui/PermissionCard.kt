package com.ravango.core.ui

import androidx.compose.ui.graphics.Color
import com.ravango.core.designsystem.component.softShadow
import com.ravango.core.designsystem.motion.rememberEntranceActive
import com.ravango.core.designsystem.motion.staggeredEntrance
import com.ravango.core.designsystem.theme.Elevation
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.ravango.core.designsystem.theme.Dimens
import com.ravango.core.designsystem.theme.Radius
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ravango.core.designsystem.component.GlassSurface
import com.ravango.core.designsystem.component.RgButtonSize
import com.ravango.core.designsystem.component.RgPrimaryButton
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing

/**
 * Transparent explanation shown *before* the system permission dialog: what we need, why, and that
 * nothing leaves the device without consent.
 */
@Composable
fun PermissionRationaleCard(
    requester: PermissionRequester,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Rounded.Lock,
) {
    val colors = RgTheme.colors
    val entrance = rememberEntranceActive()
    GlassSurface(modifier.fillMaxWidth(), shape = RoundedCornerShape(Radius.xl), contentPadding = PaddingValues(Spacing.xl)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            // Layered illustration: soft halo, brand-gradient disc with a glow, white glyph.
            Box(Modifier.size(84.dp).staggeredEntrance(0, entrance), contentAlignment = Alignment.Center) {
                Box(Modifier.size(84.dp).clip(CircleShape).background(colors.accentSoft.copy(alpha = if (colors.isDark) 0.6f else 0.8f)))
                Box(
                    Modifier.size(60.dp).softShadow(Elevation.mid, CircleShape, colors.accentGlow).clip(CircleShape).background(colors.ctaGradient),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, null, tint = Color.White, modifier = Modifier.size(Dimens.icon + 4.dp))
                }
            }
            Spacer(Modifier.height(Spacing.lg))
            Column(Modifier.staggeredEntrance(1, entrance), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, color = colors.textPrimary)
                Spacer(Modifier.height(Spacing.sm))
                Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = colors.textSecondary)
            }
            Spacer(Modifier.height(Spacing.xl))
            val label = if (requester.status == PermissionStatus.PERMANENTLY_DENIED) R.string.permission_open_settings else R.string.permission_allow
            RgPrimaryButton(stringResource(label), onClick = requester::request, size = RgButtonSize.LARGE, modifier = Modifier.fillMaxWidth())
        }
    }
}
