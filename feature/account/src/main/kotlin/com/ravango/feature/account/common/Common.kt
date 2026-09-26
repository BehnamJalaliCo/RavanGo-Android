package com.ravango.feature.account.common

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ravango.core.common.format.localizeDigits
import com.ravango.core.designsystem.component.RgSwitch
import com.ravango.core.designsystem.theme.Palette
import com.ravango.core.designsystem.theme.Radius
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.feature.account.R
import com.ravango.platform.auth.AuthError
import com.ravango.platform.cloud.SyncIssue

@StringRes
internal fun AuthError.messageRes(): Int = when (this) {
    AuthError.NOT_CONFIGURED -> R.string.account_err_not_configured
    AuthError.GOOGLE_NOT_CONFIGURED -> R.string.account_err_google_not_configured
    AuthError.GOOGLE_UNAVAILABLE -> R.string.account_err_google_unavailable
    AuthError.NETWORK -> R.string.account_err_network
    AuthError.RATE_LIMITED -> R.string.account_err_rate_limited
    AuthError.INVALID_OTP -> R.string.account_err_invalid_otp
    AuthError.INVALID_CREDENTIALS -> R.string.account_err_invalid_credentials
    AuthError.INVALID_EMAIL -> R.string.account_err_invalid_email
    AuthError.INVALID_PHONE -> R.string.account_err_invalid_phone
    AuthError.WEAK_PASSWORD -> R.string.account_err_weak_password
    AuthError.USER_EXISTS -> R.string.account_err_user_exists
    AuthError.EMAIL_NOT_CONFIRMED -> R.string.account_err_email_not_confirmed
    AuthError.PROVIDER_DISABLED -> R.string.account_err_provider_disabled
    AuthError.SESSION_EXPIRED -> R.string.account_err_session_expired
    AuthError.NOT_SIGNED_IN -> R.string.account_err_not_signed_in
    AuthError.CANCELLED -> R.string.account_err_cancelled
    AuthError.UNKNOWN -> R.string.account_err_unknown
}

@StringRes
internal fun SyncIssue.messageRes(): Int = when (this) {
    SyncIssue.NOT_CONFIGURED -> R.string.account_cloud_not_configured
    SyncIssue.SIGN_IN_REQUIRED -> R.string.account_cloud_issue_sign_in
    SyncIssue.SESSION_EXPIRED -> R.string.account_cloud_issue_session
    SyncIssue.NETWORK -> R.string.account_cloud_issue_network
    SyncIssue.SERVER -> R.string.account_cloud_issue_server
    SyncIssue.QUOTA_EXCEEDED -> R.string.account_cloud_issue_quota
    SyncIssue.UNKNOWN -> R.string.account_cloud_issue_unknown
}

/** "5 minutes ago" in the app language, with localized digits. */
internal fun relativeTime(time: Long, now: Long = System.currentTimeMillis()): String =
    DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString().localizeDigits()

/** Two-letter initials from a display name or email/phone. */
internal fun initialsOf(name: String?): String {
    val clean = name?.trim().orEmpty()
    if (clean.isEmpty()) return "?"
    val base = clean.substringBefore('@')
    val parts = base.split(' ', '.', '_', '-').filter { it.isNotBlank() }
    val letters = if (parts.size >= 2) "${parts[0].first()}${parts[1].first()}" else base.filter { it.isLetterOrDigit() }.take(2)
    return letters.uppercase().ifEmpty { "?" }
}

private val avatarGradients = listOf(
    listOf(Palette.Lavender500, Palette.Rose400),
    listOf(Palette.Sky400, Palette.Mint400),
    listOf(Palette.Peach400, Palette.Rose500),
    listOf(Palette.Mint500, Palette.Lavender400),
    listOf(Palette.Butter400, Palette.Peach400),
)

/** Initials avatar on a gradient picked deterministically from [seed]. */
@Composable
internal fun InitialsAvatar(initials: String, seed: String, size: Dp = 72.dp, modifier: Modifier = Modifier) {
    val colors = avatarGradients[(seed.hashCode() and Int.MAX_VALUE) % avatarGradients.size]
    Box(modifier.size(size).clip(CircleShape).background(Brush.linearGradient(colors)), contentAlignment = Alignment.Center) {
        if (initials.isBlank()) {
            // Guest: a person glyph instead of an empty disc.
            Icon(Icons.Rounded.Person, null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
        } else {
            Text(
                initials,
                style = if (size >= 64.dp) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
    }
}

/** A row with a title/subtitle and a switch; the whole row toggles. */
@Composable
internal fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    trailingBadge: (@Composable () -> Unit)? = null,
) {
    com.ravango.core.designsystem.component.RgListItem(
        title = title,
        subtitle = subtitle,
        icon = icon, iconTint = iconTone(icon).content, iconBackground = iconTone(icon).container,
        modifier = modifier,
        onClick = if (enabled) ({ onCheckedChange(!checked) }) else null,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                trailingBadge?.invoke()
                RgSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
            }
        },
    )
}

/** Soft informational banner (e.g. "Cloud is not configured in this build"). */
@Composable
internal fun InfoBanner(text: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Rounded.Info, tint: Color = RgTheme.colors.accent) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(tint.copy(alpha = 0.12f))
            .padding(Spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Spacing.sm))
        Text(text, style = MaterialTheme.typography.bodySmall, color = RgTheme.colors.textPrimary)
    }
}

/** Heading + paragraphs for legal text. */
@Composable
internal fun LegalSection(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.sm)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = RgTheme.colors.textPrimary)
        Spacer(Modifier.height(Spacing.xs))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = RgTheme.colors.textSecondary)
    }
}

/**
 * Category color for a settings/account row icon, so groups read at a glance (iOS-style) instead of a wall of
 * identical blue bubbles. Unknown icons get a stable tone derived from their name.
 */
@Composable
internal fun iconTone(icon: ImageVector?): com.ravango.core.designsystem.theme.RgTone {
    val tones = RgTheme.colors.tones
    val name = icon?.name.orEmpty().substringAfterLast('.')
    return when (name) {
        "Edit", "Person", "Description", "Translate", "Key" -> tones.periwinkle
        "WorkspacePremium", "Notifications", "Star" -> tones.butter
        "Cloud", "CloudDone", "CloudSync", "Info", "ScreenLockPortrait", "Storage", "Language" -> tones.sky
        "Settings", "Vibration", "AutoAwesome", "Palette", "DarkMode", "LightMode" -> tones.lilac
        "PrivacyTip", "Shield", "Memory", "CleaningServices", "Lock" -> tones.mint
        "Mail", "PhotoLibrary", "VideoSettings", "BugReport" -> tones.peach
        "Animation", "Favorite" -> tones.blush
        else -> tones.forSeed(name)
    }
}
