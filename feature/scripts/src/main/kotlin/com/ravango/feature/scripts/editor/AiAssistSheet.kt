package com.ravango.feature.scripts.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShortText
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material.icons.rounded.Phishing
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Spellcheck
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VerticalAlignBottom
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.ravango.core.designsystem.component.RgBottomSheet
import com.ravango.core.designsystem.component.RgButtonSize
import com.ravango.core.designsystem.component.RgChip
import com.ravango.core.designsystem.component.RgOutlineButton
import com.ravango.core.designsystem.component.RgPrimaryButton
import com.ravango.core.designsystem.component.RgSecondaryButton
import com.ravango.core.designsystem.component.RgTextButton
import com.ravango.core.designsystem.component.pressable
import com.ravango.core.designsystem.theme.Radius
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.core.ui.detectDirection
import com.ravango.core.ui.message
import com.ravango.engine.ai.api.TextTask
import com.ravango.engine.ai.api.Tone
import com.ravango.feature.scripts.R
import com.ravango.core.ui.R as UiR

private data class AiTool(val task: TextTask, val icon: ImageVector, val label: Int)

private val AiTools = listOf(
    AiTool(TextTask.REWRITE, Icons.Rounded.AutoAwesome, R.string.scripts_ai_rewrite),
    AiTool(TextTask.SHORTEN, Icons.AutoMirrored.Rounded.ShortText, R.string.scripts_ai_shorten),
    AiTool(TextTask.LENGTHEN, Icons.Rounded.UnfoldMore, R.string.scripts_ai_lengthen),
    AiTool(TextTask.CHANGE_TONE, Icons.Rounded.Mood, R.string.scripts_ai_change_tone),
    AiTool(TextTask.FIX_GRAMMAR, Icons.Rounded.Spellcheck, R.string.scripts_ai_fix_grammar),
    AiTool(TextTask.TRANSLATE, Icons.Rounded.Translate, R.string.scripts_ai_translate),
    AiTool(TextTask.FORMAL_TO_CONVERSATIONAL, Icons.Rounded.ChatBubbleOutline, R.string.scripts_ai_conversational),
    AiTool(TextTask.BULLETS_TO_SCRIPT, Icons.AutoMirrored.Rounded.FormatListBulleted, R.string.scripts_ai_bullets_to_script),
    AiTool(TextTask.HOOKS, Icons.Rounded.Phishing, R.string.scripts_ai_hooks),
    AiTool(TextTask.CTA, Icons.Rounded.Campaign, R.string.scripts_ai_cta),
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AiAssistSheet(
    state: AiUiState,
    hasSelection: Boolean,
    onRun: (TextTask) -> Unit,
    onTone: (Tone) -> Unit,
    onCancel: () -> Unit,
    onReplace: (String) -> Unit,
    onInsertBelow: (String) -> Unit,
    onDiscard: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAiStudio: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    RgBottomSheet(onDismiss = { onCancel(); onDismiss() }, title = stringResource(R.string.scripts_ai_title)) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.gutter)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            val block = state.block
            if (block != null) {
                BlockedCard(block, state.availability?.detail, onOpenSettings, onOpenAiStudio)
                return@Column
            }
            Text(
                stringResource(if (hasSelection || state.onSelection) R.string.scripts_ai_scope_selection else R.string.scripts_ai_scope_all),
                style = MaterialTheme.typography.bodySmall,
                color = RgTheme.colors.textSecondary,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                AiTools.forEach { tool ->
                    RgChip(
                        text = stringResource(tool.label),
                        selected = state.task == tool.task,
                        onClick = { if (!state.running) onRun(tool.task) },
                        icon = tool.icon,
                    )
                }
            }
            Text(stringResource(R.string.scripts_ai_tone_label), style = MaterialTheme.typography.labelLarge, color = RgTheme.colors.textSecondary)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                items(Tone.entries, key = { it.name }) { tone ->
                    RgChip(toneLabel(tone), state.tone == tone, { onTone(tone) })
                }
            }

            AnimatedVisibility(state.task != null) {
                ResultCard(
                    state = state,
                    onCancel = onCancel,
                    onRetry = { state.task?.let(onRun) },
                    onReplace = onReplace,
                    onInsertBelow = onInsertBelow,
                    onCopy = { text ->
                        copyToClipboard(context, text)
                        Toast.makeText(context, context.getString(UiR.string.copied), Toast.LENGTH_SHORT).show()
                    },
                    onDiscard = onDiscard,
                )
            }
            Text(stringResource(R.string.scripts_ai_disclaimer), style = MaterialTheme.typography.labelSmall, color = RgTheme.colors.textTertiary)
        }
    }
}

@Composable
private fun BlockedCard(block: AiBlock, detail: String?, onOpenSettings: () -> Unit, onOpenAiStudio: () -> Unit) {
    val colors = RgTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.lg))
            .background(colors.pastelLavender)
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lock, null, tint = colors.accent)
            Spacer(Modifier.width(Spacing.sm))
            Text(
                stringResource(if (block == AiBlock.CONSENT) R.string.scripts_ai_consent_title else R.string.scripts_ai_not_configured_title),
                style = MaterialTheme.typography.titleSmall,
                color = colors.textPrimary,
            )
        }
        Text(
            stringResource(if (block == AiBlock.CONSENT) R.string.scripts_ai_consent_message else R.string.scripts_ai_not_configured_message),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
        )
        if (block == AiBlock.NOT_CONFIGURED && !detail.isNullOrBlank()) {
            Text(detail, style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            RgPrimaryButton(stringResource(R.string.scripts_ai_open_settings), onOpenSettings, size = RgButtonSize.SMALL)
            RgOutlineButton(stringResource(R.string.scripts_ai_open_studio), onOpenAiStudio, size = RgButtonSize.SMALL)
        }
    }
}

@Composable
private fun ResultCard(
    state: AiUiState,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onReplace: (String) -> Unit,
    onInsertBelow: (String) -> Unit,
    onCopy: (String) -> Unit,
    onDiscard: () -> Unit,
) {
    val colors = RgTheme.colors
    val shape = RoundedCornerShape(Radius.lg)
    val ui = LocalLayoutDirection.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.outline, shape)
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        if (state.running) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.accent, trackColor = colors.surfaceMuted)
        }
        val error = state.error
        if (error != null) {
            Text(error.message(), style = MaterialTheme.typography.bodyMedium, color = colors.danger)
            if (!state.errorDetail.isNullOrBlank()) Text(state.errorDetail, style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                RgSecondaryButton(stringResource(UiR.string.action_retry), onRetry, icon = Icons.Rounded.Refresh, size = RgButtonSize.SMALL)
                RgTextButton(stringResource(UiR.string.action_close), onDiscard, color = colors.textSecondary)
            }
            return@Column
        }
        if (state.items.isNotEmpty()) {
            state.items.forEach { item ->
                val dir = detectDirection(item) ?: ui
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.md))
                        .background(colors.surfaceMuted)
                        .padding(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompositionLocalProvider(LocalLayoutDirection provides dir) {
                        Text(item, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary, modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.width(Spacing.sm))
                    SmallAction(Icons.Rounded.VerticalAlignBottom, stringResource(R.string.scripts_ai_insert_below)) { onInsertBelow(item) }
                    SmallAction(Icons.Rounded.ContentCopy, stringResource(UiR.string.action_copy)) { onCopy(item) }
                }
            }
        } else {
            val text = state.output.ifEmpty { if (state.running) stringResource(R.string.scripts_ai_thinking) else "" }
            val dir: LayoutDirection = detectDirection(text) ?: ui
            CompositionLocalProvider(LocalLayoutDirection provides dir) {
                Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary, modifier = Modifier.fillMaxWidth())
            }
        }
        if (state.running) {
            RgTextButton(stringResource(R.string.scripts_ai_stop), onCancel, color = colors.textSecondary)
        } else if (state.completed && state.output.isNotBlank()) {
            FlowActions(state, onReplace, onInsertBelow, onCopy, onDiscard)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowActions(
    state: AiUiState,
    onReplace: (String) -> Unit,
    onInsertBelow: (String) -> Unit,
    onCopy: (String) -> Unit,
    onDiscard: () -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (state.items.isEmpty()) {
            RgPrimaryButton(
                stringResource(if (state.onSelection) R.string.scripts_ai_replace_selection else R.string.scripts_ai_replace_all),
                { onReplace(state.output) },
                icon = Icons.Rounded.SwapHoriz,
                size = RgButtonSize.SMALL,
            )
            RgSecondaryButton(stringResource(R.string.scripts_ai_insert_below), { onInsertBelow(state.output) }, icon = Icons.Rounded.VerticalAlignBottom, size = RgButtonSize.SMALL)
        }
        RgOutlineButton(stringResource(UiR.string.action_copy), { onCopy(state.output) }, icon = Icons.Rounded.ContentCopy, size = RgButtonSize.SMALL)
        RgTextButton(stringResource(R.string.scripts_ai_discard), onDiscard, color = RgTheme.colors.textSecondary)
    }
}

@Composable
private fun SmallAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    Icon(
        icon,
        description,
        tint = RgTheme.colors.accent,
        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(50)).pressable(onClick = onClick).padding(8.dp),
    )
}

@Composable
private fun toneLabel(tone: Tone): String = stringResource(
    when (tone) {
        Tone.FRIENDLY -> R.string.scripts_ai_tone_friendly
        Tone.PROFESSIONAL -> R.string.scripts_ai_tone_professional
        Tone.ENERGETIC -> R.string.scripts_ai_tone_energetic
        Tone.HUMOROUS -> R.string.scripts_ai_tone_humorous
        Tone.INSPIRATIONAL -> R.string.scripts_ai_tone_inspirational
        Tone.CALM -> R.string.scripts_ai_tone_calm
        Tone.PERSUASIVE -> R.string.scripts_ai_tone_persuasive
        Tone.EDUCATIONAL -> R.string.scripts_ai_tone_educational
    },
)

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("RavanGo", text))
}
