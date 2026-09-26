package com.ravango.feature.projects.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ravango.core.common.format.formatNumber
import com.ravango.core.designsystem.component.ProBadge
import com.ravango.core.designsystem.component.RgBottomSheet
import com.ravango.core.designsystem.component.RgOutlineButton
import com.ravango.core.designsystem.component.RgPrimaryButton
import com.ravango.core.designsystem.component.RgSecondaryButton
import com.ravango.core.designsystem.component.RgTag
import com.ravango.core.designsystem.theme.Radius
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.core.model.ProjectTemplate
import com.ravango.feature.projects.R

/** Tags shown on template cards and in the sheet: duration, aspect and captions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TemplateTags(template: ProjectTemplate, captionsIncluded: Boolean, modifier: Modifier = Modifier) {
    val colors = RgTheme.colors
    val locale = currentLocale()
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RgTag(formatSuggestedDuration(template.suggestedDurationSec), icon = Icons.Rounded.Timer, color = colors.surfaceMuted, contentColor = colors.textSecondary)
        RgTag(aspectLabel(template.aspectRatio, locale), icon = Icons.Rounded.AspectRatio, color = colors.surfaceMuted, contentColor = colors.textSecondary)
        if (template.autoCaptions) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RgTag(stringResource(R.string.projects_template_auto_captions), icon = Icons.Rounded.ClosedCaption, color = colors.pastelMint, contentColor = colors.success)
                if (!captionsIncluded) {
                    Spacer(Modifier.width(4.dp))
                    ProBadge()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateSheet(
    template: ProjectTemplate,
    busyMode: TemplateStartMode?,
    captionsIncluded: Boolean,
    onStart: (TemplateStartMode, TemplateTexts) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = RgTheme.colors
    val accent = Color(template.accentArgb)
    val name = templateName(template)
    val translate = rememberHeadingTranslator()
    val headings = outlineHeadings(template.scriptOutline)
    val texts = TemplateTexts(title = name, localizedOutline = localizeOutline(template.scriptOutline, translate))
    val busy = busyMode != null
    val locale = currentLocale()

    RgBottomSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(width = 84.dp, height = 108.dp)
                        .clip(RoundedCornerShape(Radius.lg))
                        .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0.18f)))),
                ) {
                    AspectFramePreview(template.aspectRatio, accent, template.autoCaptions, Modifier.fillMaxWidth().height(108.dp))
                }
                Spacer(Modifier.width(Spacing.lg))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(name, style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary)
                    Text(templateDescription(template), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            TemplateTags(template, captionsIncluded)

            if (headings.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.lg))
                        .background(colors.surfaceMuted)
                        .padding(Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Text(stringResource(R.string.projects_template_outline), style = MaterialTheme.typography.labelLarge, color = colors.textSecondary)
                    headings.forEachIndexed { i, heading ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(22.dp).clip(CircleShape).background(accent.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                                Text(formatNumber(i + 1, locale),style = MaterialTheme.typography.labelSmall, color = colors.textPrimary)
                            }
                            Spacer(Modifier.width(Spacing.md))
                            Text(translate(heading) ?: heading, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
                        }
                    }
                }
            }

            RgPrimaryButton(
                text = stringResource(R.string.projects_template_start_with_script),
                onClick = { onStart(TemplateStartMode.WITH_SCRIPT, texts) },
                icon = Icons.Rounded.Description,
                loading = busyMode == TemplateStartMode.WITH_SCRIPT,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            RgSecondaryButton(
                text = stringResource(R.string.projects_template_write_with_ai),
                onClick = { onStart(TemplateStartMode.WITH_AI, texts) },
                icon = Icons.Rounded.AutoAwesome,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            RgOutlineButton(
                text = stringResource(R.string.projects_template_record_without_script),
                onClick = { onStart(TemplateStartMode.WITHOUT_SCRIPT, texts) },
                icon = Icons.Rounded.Videocam,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
