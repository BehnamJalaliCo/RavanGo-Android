package com.ravango.feature.ai

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import com.ravango.core.designsystem.component.pressable
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import com.ravango.core.designsystem.component.ShimmerBox
import com.ravango.core.designsystem.theme.Radius
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import com.ravango.core.common.format.formatDuration
import com.ravango.core.common.format.formatNumber
import com.ravango.core.designsystem.component.RgButtonSize
import com.ravango.core.designsystem.component.RgCard
import com.ravango.core.designsystem.component.RgChipRow
import com.ravango.core.designsystem.component.RgIconButton
import com.ravango.core.designsystem.component.RgLabeledSlider
import com.ravango.core.designsystem.component.RgOutlineButton
import com.ravango.core.designsystem.component.RgPrimaryButton
import com.ravango.core.designsystem.component.RgSecondaryButton
import com.ravango.core.designsystem.component.RgSegmentedControl
import com.ravango.core.designsystem.component.RgSwitch
import com.ravango.core.designsystem.component.RgTag
import com.ravango.core.designsystem.component.RgTextButton
import com.ravango.core.designsystem.component.RgTextField
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.engine.ai.api.ContentPlatform
import com.ravango.engine.ai.api.Tone
import com.ravango.engine.ai.prompts.PromptLibrary
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Everything an open AI tool can ask for (hoisted from [AiStudioViewModel] so the screen renders stateless). */
@androidx.compose.runtime.Immutable
class AiToolActions(
    val onInput: (String) -> Unit,
    val onOptions: ((ToolOptions) -> ToolOptions) -> Unit,
    val onGenerate: () -> Unit,
    val onStop: () -> Unit,
    val onToggleVariant: (Int) -> Unit,
    val onSave: (defaultTitle: String, openPrompter: Boolean) -> Unit,
    val onReplace: () -> Unit,
    val onCopy: (String) -> Unit,
    val onShare: (String) -> Unit,
)

@Composable
fun ToolContent(
    state: AiStudioUiState,
    padding: PaddingValues,
    actions: AiToolActions,
    onOpenSettings: () -> Unit,
    onSignIn: () -> Unit,
    onPaywall: () -> Unit,
) {
    val tool = state.tool ?: return
    val onCopy = actions.onCopy
    val onShare = actions.onShare
    val listState = rememberLazyListState()
    var optionsExpanded by rememberSaveable { mutableStateOf(true) }
    val defaultTitle = stringResource(R.string.ai_default_script_title)

    // Keep the result in view when generation starts.
    LaunchedEffect(state.generation == GenerationState.Streaming) {
        if (state.generation == GenerationState.Streaming) {
            optionsExpanded = false
            listState.animateScrollToItem(RESULT_INDEX)
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            start = Spacing.gutter, end = Spacing.gutter,
            top = padding.calculateTopPadding() + Spacing.sm,
            bottom = padding.calculateBottomPadding() + Spacing.huge,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item(key = "input") { InputCard(state, tool, actions.onInput) }
        item(key = "options") {
            OptionsCard(state, tool, optionsExpanded, { optionsExpanded = !optionsExpanded }, actions.onOptions)
        }
        item(key = "actions") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (state.isStreaming) {
                    RgSecondaryButton(stringResource(R.string.ai_stop), actions.onStop, icon = Icons.Rounded.Stop, modifier = Modifier.weight(1f))
                } else {
                    RgPrimaryButton(
                        text = stringResource(if (state.output.isBlank()) R.string.ai_generate else R.string.ai_regenerate),
                        onClick = actions.onGenerate,
                        icon = if (state.output.isBlank()) Icons.Rounded.AutoAwesome else Icons.Rounded.Refresh,
                        enabled = state.canGenerate,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        item(key = "result") { ResultSection(state, actions, onOpenSettings, onSignIn, onPaywall) }
        if (state.variants.isNotEmpty() && !state.isStreaming) {
            item(key = "variants_hint") {
                Text(
                    if (state.selectedVariants.isEmpty()) stringResource(R.string.ai_pick_variants)
                    else stringResource(R.string.ai_selected_count, formatNumber(state.selectedVariants.size)),
                    style = MaterialTheme.typography.labelLarge,
                    color = RgTheme.colors.textSecondary,
                )
            }
            itemsIndexed(state.variants, key = { i, v -> "v_${i}_${v.hashCode()}" }) { i, v ->
                VariantCard(v, i in state.selectedVariants, { actions.onToggleVariant(i) })
            }
        }
        if (state.hasResult) {
            item(key = "result_actions") {
                ResultActions(state, onCopy, onShare, onSave = { open -> actions.onSave(defaultTitle, open) }, onReplace = actions.onReplace)
            }
        }
    }
}

private const val RESULT_INDEX = 3

@Composable
private fun InputCard(state: AiStudioUiState, tool: AiTool, onInput: (String) -> Unit) {
    val (label, hint) = when (tool.input) {
        InputKind.TOPIC -> R.string.ai_input_topic to R.string.ai_input_topic_hint
        InputKind.TEXT -> R.string.ai_input_text to R.string.ai_input_text_hint
        InputKind.BULLETS -> R.string.ai_input_bullets to R.string.ai_input_bullets_hint
    }
    RgCard(Modifier.fillMaxWidth()) {
        state.sourceScriptTitle?.let {
            RgTag(stringResource(R.string.ai_input_from_script, it))
            Spacer(Modifier.height(Spacing.sm))
        }
        RgTextField(
            value = state.input,
            onValueChange = onInput,
            label = stringResource(label),
            placeholder = stringResource(hint),
            singleLine = false,
            minLines = if (tool.input == InputKind.TOPIC) 2 else 5,
            maxLines = 14,
            enabled = !state.isStreaming,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            trailing = if (state.input.isNotEmpty() && !state.isStreaming) {
                { RgTextButton(stringResource(R.string.ai_input_clear), { onInput("") }) }
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun OptionsCard(
    state: AiStudioUiState,
    tool: AiTool,
    expanded: Boolean,
    onToggle: () -> Unit,
    update: ((ToolOptions) -> ToolOptions) -> Unit,
) {
    val o = state.options
    RgCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = Spacing.md)) {
        // The whole header toggles; the chevron shows the state.
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).pressable(onClick = onToggle).padding(horizontal = Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Tune, null, tint = RgTheme.colors.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text(stringResource(R.string.ai_options), style = MaterialTheme.typography.titleMedium, color = RgTheme.colors.textPrimary, modifier = Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                stringResource(R.string.ai_options),
                tint = RgTheme.colors.textSecondary,
            )
        }
        AnimatedSection(expanded) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                // Language
                OptionLabel(if (tool == AiTool.TRANSLATE) R.string.ai_option_translate_to else R.string.ai_option_language)
                val fa = stringResource(R.string.ai_lang_fa)
                val en = stringResource(R.string.ai_lang_en)
                RgSegmentedControl(
                    options = listOf("fa", "en"),
                    selected = o.outputLanguage,
                    onSelect = { lang -> update { it.copy(outputLanguage = lang) } },
                    label = { if (it == "fa") fa else en },
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                )
                if (tool.usesPlatform) {
                    OptionLabel(R.string.ai_option_platform)
                    RgChipRow(
                        items = ContentPlatform.entries,
                        selected = o.platform,
                        onSelect = { p -> update { it.copy(platform = p) } },
                        label = { stringResource(it.labelRes()) },
                        contentPadding = PaddingValues(horizontal = Spacing.lg),
                    )
                }
                if (tool.usesTone) {
                    OptionLabel(R.string.ai_option_tone)
                    RgChipRow(
                        items = Tone.entries,
                        selected = o.tone,
                        onSelect = { t -> update { it.copy(tone = if (it.tone == t && !tool.toneRequired) null else t) } },
                        label = { stringResource(it.labelRes()) },
                        contentPadding = PaddingValues(horizontal = Spacing.lg),
                    )
                }
                if (tool.durationMode != AiTool.DurationMode.NONE) {
                    if (tool.durationMode == AiTool.DurationMode.OPTIONAL) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.ai_option_target_length), style = MaterialTheme.typography.labelLarge, color = RgTheme.colors.textSecondary, modifier = Modifier.weight(1f))
                            RgSwitch(o.useDuration, { on -> update { it.copy(useDuration = on) } })
                        }
                    }
                    AnimatedSection(o.useDuration) {
                        val words = PromptLibrary.targetWords(o.durationSec, o.outputLanguage)
                        RgLabeledSlider(
                            label = stringResource(R.string.ai_option_duration),
                            value = o.durationSec.toFloat(),
                            onValueChange = { v -> update { it.copy(durationSec = (v / 15f).roundToInt() * 15) } },
                            valueRange = 15f..600f,
                            valueText = stringResource(R.string.ai_option_duration_value, formatDuration(o.durationSec * 1_000_000L), formatNumber(words)),
                            modifier = Modifier.padding(horizontal = Spacing.lg),
                        )
                    }
                }
                if (tool.isList) {
                    RgLabeledSlider(
                        label = stringResource(R.string.ai_option_variants),
                        value = o.variants.toFloat(),
                        onValueChange = { v -> update { it.copy(variants = v.roundToInt().coerceIn(1, tool.maxVariants)) } },
                        valueRange = 1f..tool.maxVariants.toFloat(),
                        steps = tool.maxVariants - 2,
                        valueText = formatNumber(o.variants),
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                    )
                }
                if (tool.usesAudience) {
                    RgTextField(
                        value = o.audience,
                        onValueChange = { a -> update { it.copy(audience = a) } },
                        label = stringResource(R.string.ai_option_audience),
                        placeholder = stringResource(R.string.ai_option_audience_hint),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
                    )
                }
                RgTextField(
                    value = o.extra,
                    onValueChange = { x -> update { it.copy(extra = x) } },
                    label = stringResource(R.string.ai_option_extra),
                    placeholder = stringResource(R.string.ai_option_extra_hint),
                    singleLine = false,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
                )
            }
        }
    }
}

@Composable
private fun OptionLabel(res: Int) {
    Text(
        stringResource(res),
        style = MaterialTheme.typography.labelLarge,
        color = RgTheme.colors.textSecondary,
        modifier = Modifier.padding(horizontal = Spacing.lg).padding(top = Spacing.xs),
    )
}

@Composable
private fun ResultSection(
    state: AiStudioUiState,
    actions: AiToolActions,
    onOpenSettings: () -> Unit,
    onSignIn: () -> Unit,
    onPaywall: () -> Unit,
) {
    AnimatedContent(
        targetState = when {
            state.generation is GenerationState.Failed && state.output.isBlank() -> 2
            state.isStreaming && state.output.isBlank() -> 1
            state.output.isNotBlank() -> 3
            else -> 0
        },
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "result",
    ) { mode ->
        when (mode) {
            1 -> RgCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ai_generating), style = MaterialTheme.typography.labelLarge, color = RgTheme.colors.accent)
                Spacer(Modifier.height(Spacing.md))
                repeat(3) { i ->
                    ShimmerBox(Modifier.fillMaxWidth(if (i == 2) 0.6f else 1f).height(14.dp), RoundedCornerShape(Radius.pill))
                    Spacer(Modifier.height(Spacing.sm))
                }
            }
            2 -> {
                val f = state.generation as? GenerationState.Failed
                if (f != null) FailureCard(f.kind, f.code, actions.onGenerate, onPaywall, onOpenSettings, onSignIn)
            }
            3 -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                val g = state.generation
                if (g is GenerationState.Failed) FailureCard(g.kind, g.code, actions.onGenerate, onPaywall, onOpenSettings, onSignIn)
                // List tasks show their items as cards once complete; the raw stream is shown while writing.
                if (state.variants.isEmpty() || state.isStreaming) {
                    RgCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when {
                                    state.isStreaming -> stringResource(R.string.ai_generating)
                                    g == GenerationState.Stopped -> stringResource(R.string.ai_stopped)
                                    else -> stringResource(R.string.ai_result)
                                },
                                style = MaterialTheme.typography.labelLarge,
                                color = if (state.isStreaming) RgTheme.colors.accent else RgTheme.colors.textSecondary,
                                modifier = Modifier.weight(1f),
                            )
                            if (!state.isStreaming) {
                                RgIconButton(Icons.Rounded.ContentCopy, stringResource(com.ravango.core.ui.R.string.action_copy), { actions.onCopy(state.output.trim()) }, size = 36.dp)
                                Spacer(Modifier.width(Spacing.xs))
                                RgIconButton(Icons.Rounded.Share, stringResource(com.ravango.core.ui.R.string.action_share), { actions.onShare(state.output.trim()) }, size = 36.dp)
                            }
                        }
                        Spacer(Modifier.height(Spacing.sm))
                        StreamingText(state.output, state.isStreaming)
                    }
                }
                if (g is GenerationState.Done && g.creditsUsed > 0) {
                    Text(stringResource(R.string.ai_credits_used, formatNumber(g.creditsUsed)), style = MaterialTheme.typography.labelSmall, color = RgTheme.colors.textTertiary)
                }
            }
            else -> Spacer(Modifier.height(0.dp))
        }
    }
}

@Composable
private fun ResultActions(
    state: AiStudioUiState,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onSave: (openPrompter: Boolean) -> Unit,
    onReplace: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        RgPrimaryButton(
            stringResource(R.string.ai_open_prompter),
            { onSave(true) },
            icon = Icons.Rounded.Subscriptions,
            loading = state.saving,
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            RgSecondaryButton(stringResource(R.string.ai_save_new), { onSave(false) }, enabled = !state.saving, size = RgButtonSize.MEDIUM)
            if (state.sourceScriptId != null) {
                RgOutlineButton(stringResource(R.string.ai_replace_script), onReplace, enabled = !state.saving)
            }
            if (state.variants.isNotEmpty()) {
                RgOutlineButton(stringResource(com.ravango.core.ui.R.string.action_copy), { onCopy(state.actionText) }, icon = Icons.Rounded.ContentCopy)
                RgOutlineButton(stringResource(com.ravango.core.ui.R.string.action_share), { onShare(state.actionText) }, icon = Icons.AutoMirrored.Rounded.Send)
            }
        }
    }
}

private fun ContentPlatform.labelRes(): Int = when (this) {
    ContentPlatform.INSTAGRAM -> R.string.ai_platform_instagram
    ContentPlatform.YOUTUBE -> R.string.ai_platform_youtube
    ContentPlatform.YOUTUBE_SHORTS -> R.string.ai_platform_shorts
    ContentPlatform.TIKTOK -> R.string.ai_platform_tiktok
    ContentPlatform.LINKEDIN -> R.string.ai_platform_linkedin
    ContentPlatform.TELEGRAM -> R.string.ai_platform_telegram
    ContentPlatform.PODCAST -> R.string.ai_platform_podcast
    ContentPlatform.GENERAL -> R.string.ai_platform_general
}

private fun Tone.labelRes(): Int = when (this) {
    Tone.FRIENDLY -> R.string.ai_tone_friendly
    Tone.PROFESSIONAL -> R.string.ai_tone_professional
    Tone.ENERGETIC -> R.string.ai_tone_energetic
    Tone.HUMOROUS -> R.string.ai_tone_humorous
    Tone.INSPIRATIONAL -> R.string.ai_tone_inspirational
    Tone.CALM -> R.string.ai_tone_calm
    Tone.PERSUASIVE -> R.string.ai_tone_persuasive
    Tone.EDUCATIONAL -> R.string.ai_tone_educational
}

