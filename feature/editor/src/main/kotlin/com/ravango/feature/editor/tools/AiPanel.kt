package com.ravango.feature.editor.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MovieFilter
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ravango.core.designsystem.component.ProBadge
import com.ravango.core.designsystem.component.RgButtonSize
import com.ravango.core.designsystem.component.RgOutlineButton
import com.ravango.core.designsystem.component.RgPrimaryButton
import com.ravango.core.designsystem.component.RgSecondaryButton
import com.ravango.core.designsystem.component.RgTextButton
import com.ravango.core.designsystem.component.pressable
import com.ravango.core.designsystem.theme.Radius
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.core.model.ProFeature
import com.ravango.engine.ai.api.CapabilityState
import com.ravango.feature.editor.EditorUiState
import com.ravango.feature.editor.EditorViewModel
import com.ravango.feature.editor.R
import com.ravango.feature.editor.ui.InfoCard
import com.ravango.feature.editor.ui.localized
import com.ravango.feature.editor.ui.timecode

@Composable
private fun AiCard(icon: ImageVector, title: String, body: String, pro: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs)
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(Radius.md))
            .padding(Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = accent(), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text(title, style = MaterialTheme.typography.titleSmall, color = Color.White, modifier = Modifier.weight(1f))
            if (pro) ProBadge()
        }
        Text(body, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.65f), modifier = Modifier.padding(top = 2.dp, bottom = Spacing.sm))
        content()
    }
}

@Composable
fun AiPanel(state: EditorUiState, vm: EditorViewModel) {
    val ai = state.ai
    val pro = !state.has(ProFeature.AI_VIDEO_TOOLS)
    val busy = state.busy != null
    if (!state.services.aiConfigured) {
        InfoCard(stringResource(R.string.editor_ai_not_configured) + (state.services.aiDetail?.let { "\n$it" } ?: ""), icon = Icons.Rounded.Info) {
            RgTextButton(stringResource(R.string.editor_open_settings), onClick = vm::openSettings)
        }
    }

    // Silence removal
    AiCard(Icons.AutoMirrored.Rounded.VolumeOff, stringResource(R.string.editor_ai_silence_title), stringResource(R.string.editor_ai_silence_body), pro) {
        val silences = ai.silences
        if (silences == null) {
            RgSecondaryButton(stringResource(R.string.editor_ai_find_silences), vm::detectSilences, size = RgButtonSize.SMALL, enabled = !busy)
        } else {
            val count = silences.values.sumOf { it.size }
            Text(
                stringResource(R.string.editor_ai_silence_result, localized(count.toString()), timecode(ai.silenceTotalUs)),
                style = MaterialTheme.typography.bodyMedium, color = Color.White,
            )
            Spacer(Modifier.height(Spacing.xs))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                RgPrimaryButton(stringResource(R.string.editor_apply), vm::applySilenceRemoval, size = RgButtonSize.SMALL, enabled = count > 0)
                RgOutlineButton(stringResource(R.string.editor_dismiss), vm::dismissAi, size = RgButtonSize.SMALL)
            }
        }
    }

    // Highlights
    AiCard(Icons.Rounded.Star, stringResource(R.string.editor_ai_highlights_title), stringResource(R.string.editor_ai_highlights_body), pro) {
        val list = ai.highlights
        if (list == null) {
            RgSecondaryButton(stringResource(R.string.editor_ai_find_highlights), vm::findHighlights, size = RgButtonSize.SMALL, enabled = !busy)
        } else if (list.isEmpty()) {
            Text(stringResource(R.string.editor_ai_none_found), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
        } else {
            list.forEach { h ->
                Column(
                    Modifier.fillMaxWidth().pressable { ai.highlightClipId?.let { vm.jumpToSource(it, h.range.startUs) } }.padding(vertical = 6.dp),
                ) {
                    Text(h.title, style = MaterialTheme.typography.bodyMedium, color = Color.White)
                    Text(timecode(h.range.startUs) + " · " + h.reason, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
                }
            }
        }
    }

    // Shorts / Reels
    AiCard(Icons.Rounded.MovieFilter, stringResource(R.string.editor_ai_shorts_title), stringResource(R.string.editor_ai_shorts_body), pro) {
        val shorts = ai.shorts
        if (!state.services.speechConfigured) Text(stringResource(R.string.editor_ai_needs_speech), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
        if (shorts == null) {
            RgSecondaryButton(stringResource(R.string.editor_ai_make_shorts), vm::suggestShorts, size = RgButtonSize.SMALL, enabled = !busy && state.services.speechConfigured)
        } else if (shorts.isEmpty()) {
            Text(stringResource(R.string.editor_ai_none_found), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
        } else {
            shorts.forEachIndexed { i, s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.title, style = MaterialTheme.typography.bodyMedium, color = Color.White)
                        Text(s.hook + " · " + timecode(s.durationUs, tenths = false), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f), maxLines = 2)
                    }
                    if (i in ai.createdShorts) Icon(Icons.Rounded.Check, stringResource(R.string.editor_ai_short_created), tint = accent())
                    else RgTextButton(stringResource(R.string.editor_ai_create_project), onClick = { vm.createShort(i) }, enabled = !busy)
                }
            }
        }
    }

    // Auto edit
    AiCard(Icons.Rounded.ContentCut, stringResource(R.string.editor_ai_autoedit_title), stringResource(R.string.editor_ai_autoedit_body), pro) {
        val plan = ai.plan
        if (plan == null) {
            RgSecondaryButton(stringResource(R.string.editor_ai_plan), vm::planAutoEdit, size = RgButtonSize.SMALL, enabled = !busy)
        } else {
            val cutCount = plan.cuts.values.sumOf { it.size }
            val cutUs = plan.cuts.values.flatten().sumOf { it.range.durationUs }
            Text(stringResource(R.string.editor_ai_plan_summary, localized(cutCount.toString()), timecode(cutUs), localized(plan.subtitles.size.toString())), style = MaterialTheme.typography.bodyMedium, color = Color.White)
            plan.suggestedTitle?.let { Text(stringResource(R.string.editor_ai_suggested_title, it), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f)) }
            plan.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f)) }
            Spacer(Modifier.height(Spacing.xs))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                RgPrimaryButton(stringResource(R.string.editor_apply), vm::applyAutoEdit, size = RgButtonSize.SMALL)
                RgOutlineButton(stringResource(R.string.editor_dismiss), vm::dismissAi, size = RgButtonSize.SMALL)
            }
        }
    }

    // Eye contact
    AiCard(Icons.Rounded.Visibility, stringResource(R.string.editor_ai_eye_title), stringResource(R.string.editor_ai_eye_body), pro) {
        when (state.services.eyeContact) {
            CapabilityState.AVAILABLE -> RgSecondaryButton(stringResource(R.string.editor_ai_eye_apply), vm::correctEyeContact, size = RgButtonSize.SMALL, enabled = !busy && state.selectedClip() != null)
            CapabilityState.REQUIRES_SERVICE -> Text(stringResource(R.string.editor_ai_eye_requires, state.services.eyeContactService), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f))
            CapabilityState.UNSUPPORTED -> Text(stringResource(R.string.editor_ai_eye_unsupported), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f))
        }
    }
}
