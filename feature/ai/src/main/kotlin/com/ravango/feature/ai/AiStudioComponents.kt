package com.ravango.feature.ai

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ravango.core.common.format.formatNumber
import com.ravango.core.common.result.ErrorKind
import com.ravango.core.designsystem.component.RgCard
import com.ravango.core.designsystem.component.RgButtonSize
import com.ravango.core.designsystem.component.RgPrimaryButton
import com.ravango.core.designsystem.component.RgProgressBar
import com.ravango.core.designsystem.component.RgSecondaryButton
import com.ravango.core.designsystem.component.RgTag
import com.ravango.core.designsystem.component.pressable
import com.ravango.core.designsystem.theme.Radius
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.core.model.AiProviderId
import com.ravango.core.model.ContentDirection
import com.ravango.core.ui.message
import com.ravango.core.ui.resolve
import com.ravango.engine.ai.api.AiAvailability
import com.ravango.engine.ai.api.AiErrors
import com.ravango.engine.ai.api.AiSetupIssue

@StringRes
fun AiProviderId.labelRes(): Int = when (this) {
    AiProviderId.RAVANGO_GATEWAY -> R.string.ai_provider_gateway
    AiProviderId.ANTHROPIC_DIRECT -> R.string.ai_provider_anthropic
    AiProviderId.OPENAI_COMPATIBLE -> R.string.ai_provider_openai_compatible
}

@StringRes
fun AiSetupIssue?.messageRes(): Int = when (this) {
    AiSetupIssue.GATEWAY_NOT_CONFIGURED -> R.string.ai_issue_gateway
    AiSetupIssue.SIGN_IN_REQUIRED -> R.string.ai_issue_sign_in
    AiSetupIssue.API_KEY_MISSING -> R.string.ai_issue_key
    AiSetupIssue.CUSTOM_ENDPOINT_MISSING -> R.string.ai_issue_endpoint
    else -> R.string.ai_issue_generic
}

/** Localized explanation for a failed generation: specific AI codes first, then the generic [ErrorKind] text. */
@Composable
fun failureMessage(kind: ErrorKind, code: String?): String = when (code) {
    AiErrors.REFUSED -> stringResource(R.string.ai_err_refused)
    AiErrors.NO_CREDITS -> stringResource(R.string.ai_err_no_credits)
    AiErrors.RATE_LIMITED -> stringResource(R.string.ai_err_rate_limited)
    AiErrors.OVERLOADED -> stringResource(R.string.ai_err_overloaded)
    AiErrors.INVALID_API_KEY -> stringResource(R.string.ai_err_invalid_key)
    AiErrors.EMPTY_RESPONSE -> stringResource(R.string.ai_err_empty)
    AiErrors.SERVICE_NOT_AVAILABLE -> stringResource(R.string.ai_err_service)
    AiErrors.SIGN_IN_REQUIRED -> stringResource(R.string.ai_issue_sign_in)
    AiErrors.API_KEY_MISSING -> stringResource(R.string.ai_issue_key)
    AiErrors.GATEWAY_NOT_CONFIGURED -> stringResource(R.string.ai_issue_gateway)
    AiErrors.CUSTOM_ENDPOINT_MISSING -> stringResource(R.string.ai_issue_endpoint)
    else -> if (kind == ErrorKind.QUOTA) stringResource(R.string.ai_err_no_credits) else kind.message()
}

/** Hero header: brand gradient card with provider status and credits. */
@Composable
fun AiHero(state: AiStudioUiState, onGetCredits: () -> Unit, modifier: Modifier = Modifier) {
    val colors = RgTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter)
            .clip(RoundedCornerShape(Radius.xl))
            .background(colors.brandGradient),
    ) {
        Column(Modifier.padding(Spacing.xl)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.ai_hero_title), style = MaterialTheme.typography.titleLarge, color = Color.White)
                    ProviderStatus(state.availability)
                }
            }
            Spacer(Modifier.height(Spacing.md))
            Text(stringResource(R.string.ai_hero_body), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.92f))
            Spacer(Modifier.height(Spacing.lg))
            if (state.showsCredits) {
                val e = state.entitlements
                val remaining = e.aiCreditsRemaining.coerceAtLeast(0)
                val total = e.aiCreditsPerMonth.coerceAtLeast(1)
                Text(
                    stringResource(R.string.ai_credits_remaining, formatNumber(remaining), formatNumber(total)),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                )
                Spacer(Modifier.height(Spacing.sm))
                RgProgressBar(remaining.toFloat() / total, trackColor = Color.White.copy(alpha = 0.25f))
                if (remaining < total / 4) {
                    Spacer(Modifier.height(Spacing.md))
                    RgSecondaryButton(
                        stringResource(R.string.ai_get_more_credits), onGetCredits, size = RgButtonSize.SMALL,
                        containerColor = Color.White, contentColor = colors.accent,
                    )
                }
            } else {
                Text(stringResource(R.string.ai_own_key_no_credits), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.9f))
            }
        }
    }
}

@Composable
private fun ProviderStatus(a: AiAvailability) {
    val (icon, label) = when {
        !a.configured -> Icons.Rounded.ErrorOutline to stringResource(R.string.ai_status_setup)
        a.consentRequired -> Icons.Rounded.Shield to stringResource(R.string.ai_status_consent)
        else -> Icons.Rounded.CheckCircle to stringResource(R.string.ai_status_ready)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(a.provider.labelRes()), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(Spacing.sm))
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(Spacing.xs))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}

/** Explains missing setup with a fix-it action. */
@Composable
fun SetupCard(availability: AiAvailability, onOpenSettings: () -> Unit, onSignIn: () -> Unit, modifier: Modifier = Modifier) {
    RgCard(modifier.fillMaxWidth().padding(horizontal = Spacing.gutter), color = RgTheme.colors.pastelButter) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Lock, null, tint = RgTheme.colors.warning, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(stringResource(availability.issue.messageRes()), style = MaterialTheme.typography.bodyMedium, color = RgTheme.colors.textPrimary)
                Spacer(Modifier.height(Spacing.sm))
                if (availability.issue == AiSetupIssue.SIGN_IN_REQUIRED) {
                    RgPrimaryButton(stringResource(R.string.ai_sign_in), onSignIn, size = RgButtonSize.SMALL)
                } else {
                    RgSecondaryButton(stringResource(R.string.ai_open_settings), onOpenSettings, size = RgButtonSize.SMALL)
                }
            }
        }
    }
}

/** A tile in the tool grid. */
@Composable
fun ToolTile(tool: AiTool, tint: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = RgTheme.colors
    Column(
        modifier
            .clip(RoundedCornerShape(Radius.lg))
            .background(colors.surface)
            .border(1.dp, colors.outline, RoundedCornerShape(Radius.lg))
            .pressable(onClick = onClick)
            .padding(Spacing.lg)
            .heightIn(min = 112.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(Radius.sm)).background(tint), contentAlignment = Alignment.Center) {
            Icon(tool.icon, null, tint = colors.accent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(Spacing.md))
        Text(stringResource(tool.title), style = MaterialTheme.typography.titleSmall, color = colors.textPrimary, maxLines = 2)
        Spacer(Modifier.height(Spacing.xxs))
        Text(stringResource(tool.subtitle), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Output text with a blinking typing cursor while streaming. Direction follows the content (Persian output reads
 * RTL even in an English UI).
 */
@Composable
fun StreamingText(text: String, streaming: Boolean, modifier: Modifier = Modifier) {
    val uiDirection = LocalLayoutDirection.current
    val direction = remember(text.take(64), uiDirection) { ContentDirection.AUTO.resolve(text, uiDirection) }
    val reduceMotion = RgTheme.reduceMotion
    val transition = rememberInfiniteTransition(label = "cursor")
    val blink by transition.animateFloat(1f, 0f, infiniteRepeatable(tween(530), RepeatMode.Reverse), label = "blink")
    val accent = RgTheme.colors.accent
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                color = RgTheme.colors.textPrimary,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (streaming) {
                Box(
                    Modifier
                        .padding(start = 2.dp, bottom = 4.dp)
                        .size(width = 2.dp, height = 18.dp)
                        .drawWithContent { drawRect(accent.copy(alpha = if (reduceMotion) 1f else blink)) },
                )
            }
        }
    }
}

/** Selectable card for a list-style result item. */
@Composable
fun VariantCard(text: String, selected: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val colors = RgTheme.colors
    val border by animateColorAsState(if (selected) colors.accent else colors.outline, label = "border")
    val bg by animateColorAsState(if (selected) colors.accentSoft else colors.surface, label = "bg")
    val uiDirection = LocalLayoutDirection.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(bg)
            .border(if (selected) 2.dp else 1.dp, border, RoundedCornerShape(Radius.md))
            .pressable(onClick = onToggle)
            .padding(Spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            null,
            tint = if (selected) colors.accent else colors.textTertiary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(Spacing.md))
        CompositionLocalProvider(LocalLayoutDirection provides ContentDirection.AUTO.resolve(text, uiDirection)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary, modifier = Modifier.weight(1f))
        }
    }
}

/** Error card with the right recovery action. */
@Composable
fun FailureCard(
    kind: ErrorKind,
    code: String?,
    onRetry: () -> Unit,
    onPaywall: () -> Unit,
    onOpenSettings: () -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RgCard(modifier.fillMaxWidth(), color = RgTheme.colors.pastelRose) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.ErrorOutline, null, tint = RgTheme.colors.danger, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(failureMessage(kind, code), style = MaterialTheme.typography.bodyMedium, color = RgTheme.colors.textPrimary)
                Spacer(Modifier.height(Spacing.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    when {
                        kind == ErrorKind.QUOTA -> RgPrimaryButton(stringResource(R.string.ai_get_more_credits), onPaywall, size = RgButtonSize.SMALL)
                        code == AiErrors.SIGN_IN_REQUIRED || kind == ErrorKind.AUTH -> RgPrimaryButton(stringResource(R.string.ai_sign_in), onSignIn, size = RgButtonSize.SMALL)
                        kind == ErrorKind.NOT_CONFIGURED -> RgSecondaryButton(stringResource(R.string.ai_open_settings), onOpenSettings, size = RgButtonSize.SMALL)
                        else -> RgSecondaryButton(stringResource(com.ravango.core.ui.R.string.action_retry), onRetry, size = RgButtonSize.SMALL)
                    }
                }
            }
        }
    }
}

/** Glass info row used in the video tools card. */
@Composable
fun VideoFeatureRow(label: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(RgTheme.colors.accent))
        Spacer(Modifier.width(Spacing.sm))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = RgTheme.colors.textPrimary, modifier = Modifier.weight(1f))
        if (trailing != null) RgTag(trailing, color = RgTheme.colors.surfaceMuted, contentColor = RgTheme.colors.textSecondary)
    }
}

@Composable
fun AnimatedSection(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(visible, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) { content() }
}
