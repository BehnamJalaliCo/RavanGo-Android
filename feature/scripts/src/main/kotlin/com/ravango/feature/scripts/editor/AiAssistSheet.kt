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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextOverflow
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
import com.ravango.core.designsystem.component.RgProgressBar
import androidx.compose.foundation.layout.height
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
    RgBottomSheet(onDismiss = { onCancel(); onDismiss() }) {
        AiAssistContent(
            state = state,
            hasSelection = hasSelection,
            onRun = onRun,
            onTone = onTone,
            onCancel = onCancel,
            onReplace = onReplace,
            onInsertBelow = onInsertBelow,
            onCopy = { text ->
                copyToClipboard(context, text)
                Toast.makeText(context, context.getString(UiR.string.copied), Toast.LENGTH_SHORT).show()
            },
            onDiscard = onDiscard,
            onOpenSettings = onOpenSettings,
            onOpenAiStudio = onOpenAiStudio,
        )
    }
}

/** Body of the AI assist sheet (tools, tone, streaming result). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AiAssistContent(
    state: AiUiState,
    hasSelection: Boolean,
    onRun: (TextTask) -> Unit,
    onTone: (Tone) -> Unit,
    onCancel: () -> Unit,
    onReplace: (String) -> Unit,
    onInsertBelow: (String) -> Unit,
    onCopy: (String) -> Unit,
    onDiscard: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAiStudio: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.scripts_ai_title),
            style = MaterialTheme.typography.titleLarge,
            color = RgTheme.colors.textPrimary,
            modifier = Modifier.padding(horizontal = Spacing.gutter).padding(top = Spacing.xs, bottom = Spacing.sm),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            val block = state.block
            if (block != null) {
                Box(Modifier.padding(horizontal = Spacing.gutter)) { BlockedCard(block, state.availability?.detail, onOpenSettings, onOpenAiStudio) }
                return@Column
            }
            Text(
                stringResource(if (hasSelection || state.onSelection) R.string.scripts_ai_scope_selection else R.string.scripts_ai_scope_all),
                style = MaterialTheme.typography.bodySmall,
                color = RgTheme.colors.textSecondary,
                modifier = Modifier.padding(horizontal = Spacing.gutter),
            )
            // The result sits right under the scope line so streaming text is always in view.
            AnimatedVisibility(state.task != null) {
                Box(Modifier.padding(horizontal = Spacing.gutter)) {
                    ResultCard(
                        state = state,
                        onCancel = onCancel,
                        onRetry = { state.task?.let(onRun) },
                        onReplace = onReplace,
                        onInsertBelow = onInsertBelow,
                        onCopy = onCopy,
                        onDiscard = onDiscard,
                    )
                }
            }
            // Tools: an even two-column grid of equal tiles.
            Column(Modifier.padding(horizontal = Spacing.gutter), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                AiTools.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        row.forEach { tool ->
                            ToolTile(
                                tool = tool,
                                selected = state.task == tool.task,
                                enabled = !state.running,
                                onClick = { onRun(tool.task) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            Text(
                stringResource(R.string.scripts_ai_tone_label),
                style = MaterialTheme.typography.labelLarge,
                color = RgTheme.colors.textSecondary,
                modifier = Modifier.padding(horizontal = Spacing.gutter),
            )
            LazyRow(contentPadding = PaddingValues(horizontal = Spacing.gutter), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                items(Tone.entries, key = { it.name }) { tone ->
                    RgChip(toneLabel(tone), state.tone == tone, { onTone(tone) })
                }
            }
            Text(
                stringResource(R.string.scripts_ai_disclaimer),
                style = MaterialTheme.typography.labelSmall,
                color = RgTheme.colors.textTertiary,
                modifier = Modifier.padding(horizontal = Spacing.gutter),
            )
        }
    }
}

@Composable
private fun ToolTile(tool: AiTool, selected: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = RgTheme.colors
    val shape = RoundedCornerShape(Radius.md)
    Row(
        modifier
            .height(52.dp)
            .clip(shape)
            .background(if (selected) colors.accent else colors.surfaceMuted)
            .pressable(enabled = enabled || selected, onClick = onClick)
            .padding(horizontal = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(tool.icon, null, tint = if (selected) colors.onAccent else colors.accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Spacing.sm))
        Text(
            stringResource(tool.label),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) colors.onAccent else colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
        if (state.running) WorkingBar()
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
            RgSecondaryButton(stringResource(R.string.scripts_ai_stop), onCancel, icon = Icons.Rounded.Stop, size = RgButtonSize.SMALL)
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
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (state.items.isEmpty()) {
            // Apply actions as two equal-width buttons.
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                RgPrimaryButton(
                    stringResource(if (state.onSelection) R.string.scripts_ai_replace_selection else R.string.scripts_ai_replace_all),
                    { onReplace(state.output) },
                    icon = Icons.Rounded.SwapHoriz,
                    size = RgButtonSize.MEDIUM,
                    modifier = Modifier.weight(1f),
                )
                RgSecondaryButton(
                    stringResource(R.string.scripts_ai_insert_below), { onInsertBelow(state.output) },
                    icon = Icons.Rounded.VerticalAlignBottom, size = RgButtonSize.MEDIUM, modifier = Modifier.weight(1f),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RgOutlineButton(stringResource(UiR.string.action_copy), { onCopy(state.output) }, icon = Icons.Rounded.ContentCopy, size = RgButtonSize.SMALL)
            Spacer(Modifier.weight(1f))
            RgTextButton(stringResource(R.string.scripts_ai_discard), onDiscard, color = RgTheme.colors.textSecondary)
        }
    }
}

@Composable
private fun SmallAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).pressable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = RgTheme.colors.accent, modifier = Modifier.size(20.dp))
    }
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

/** Indeterminate progress; a still bar when reduced motion is on. */
@Composable
private fun WorkingBar() {
    val colors = RgTheme.colors
    if (RgTheme.reduceMotion) {
        RgProgressBar(0.35f, height = 4.dp)
    } else {
        LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp), color = colors.accent, trackColor = colors.surfaceMuted)
    }
}
