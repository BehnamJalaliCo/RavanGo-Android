package com.ravango.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
    GlassSurface(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(Spacing.sm), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Icon(icon, null, tint = RgTheme.colors.accent, modifier = Modifier.padding(4.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, color = RgTheme.colors.textPrimary)
            Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = RgTheme.colors.textSecondary)
            val label = if (requester.status == PermissionStatus.PERMANENTLY_DENIED) R.string.permission_open_settings else R.string.permission_allow
            RgPrimaryButton(stringResource(label), onClick = requester::request, size = RgButtonSize.MEDIUM)
        }
    }
}
