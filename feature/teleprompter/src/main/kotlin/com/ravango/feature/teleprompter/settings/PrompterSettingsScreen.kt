package com.ravango.feature.teleprompter.settings

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ravango.core.common.format.localizeDigits
import com.ravango.core.designsystem.component.ColorSwatchRow
import com.ravango.core.designsystem.component.LoadingState
import com.ravango.core.designsystem.component.RgButtonSize
import com.ravango.core.designsystem.component.RgChip
import com.ravango.core.designsystem.component.RgConfirmDialog
import com.ravango.core.designsystem.component.RgGroup
import com.ravango.core.designsystem.component.RgLabeledSlider
import com.ravango.core.designsystem.component.RgListItem
import com.ravango.core.designsystem.component.RgOutlineButton
import com.ravango.core.designsystem.component.RgScreen
import com.ravango.core.designsystem.component.RgSegmentedControl
import com.ravango.core.designsystem.component.RgSwitch
import com.ravango.core.designsystem.component.RgTextButton
import com.ravango.core.designsystem.component.RgTextField
import com.ravango.core.designsystem.component.pressable
import com.ravango.core.designsystem.theme.Radius
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.core.designsystem.theme.toFontFamily
import com.ravango.core.model.ContentDirection
import com.ravango.core.model.PrompterFont
import com.ravango.core.model.PrompterPlacement
import com.ravango.core.model.PrompterTextAlign
import com.ravango.core.model.TeleprompterPreset
import com.ravango.core.model.TeleprompterSettings
import com.ravango.engine.teleprompter.TeleprompterView
import com.ravango.engine.teleprompter.rememberPrompterController
import com.ravango.feature.teleprompter.R
import com.ravango.feature.teleprompter.input.VolumeKeyMode
import java.util.Locale
import kotlin.math.roundToInt
import com.ravango.core.ui.R as UiR

@Composable
internal fun PrompterSettingsRoute(onBack: () -> Unit, viewModel: PrompterSettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PrompterSettingsContent(state, viewModel, onBack)
}

/**
 * Stateless settings screen. [livePreview] scrolls the preview continuously (off in screenshot tests so the
 * render is deterministic).
 */
@Composable
internal fun PrompterSettingsContent(
    state: PrompterSettingsUiState,
    actions: PrompterSettingsActions,
    onBack: () -> Unit,
    livePreview: Boolean = true,
) {
    RgScreen(
        title = stringResource(R.string.prompter_settings),
        subtitle = state.scriptTitle,
        onBack = onBack,
    ) { padding ->
        if (state.loading) {
            LoadingState(Modifier.padding(padding))
        } else {
            SettingsContent(state, actions, livePreview, Modifier.padding(padding))
        }
    }
}

private fun Color.argb(): Long = toArgb().toLong() and 0xFFFFFFFFL

private val TextColors = listOf(0xFFFFFFFF, 0xFFFFF4D6, 0xFFFFD9A0, 0xFFE8E4FF, 0xFFBDF2E3, 0xFF111111, 0xFF1B1830).map { Color(it) }
private val HighlightColors = listOf(0xFFFFD166, 0xFFFF93AF, 0xFFA394FB, 0xFF6FD9C0, 0xFF7DB8FF, 0xFFFF6B6B).map { Color(it) }
private val BackgroundColors = listOf(0xFF000000, 0xFF15131F, 0xFF1E2C42, 0xFF1C3530, 0xFF3A2433, 0xFFFFFFFF, 0xFFF8F4E8).map { Color(it) }

@Composable
private fun SettingsContent(state: PrompterSettingsUiState, vm: PrompterSettingsActions, livePreview: Boolean, modifier: Modifier) {
    val s = state.settings
    var showSaveDialog by remember { mutableStateOf(false) }
    var presetToDelete by remember { mutableStateOf<TeleprompterPreset?>(null) }
    var showReset by remember { mutableStateOf(false) }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Spacing.huge)) {
        item { Preview(state, livePreview) }

        if (state.scriptTitle != null) {
            item {
                RgGroup(title = stringResource(R.string.prompter_scope_title)) {
                    Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        RgSegmentedControl(
                            options = listOf(true, false),
                            selected = state.scriptOnly,
                            onSelect = vm::setScriptOnly,
                            label = { if (it) stringResource(R.string.prompter_scope_script) else stringResource(R.string.prompter_scope_all) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            stringResource(if (state.scriptOnly) R.string.prompter_scope_script_hint else R.string.prompter_scope_all_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = RgTheme.colors.textSecondary,
                        )
                    }
                }
            }
        }

        item {
            Column {
                // Header aligned with the design-system group titles (gutter + 8dp).
                Row(Modifier.fillMaxWidth().padding(start = Spacing.gutter + Spacing.sm, end = Spacing.sm, top = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.prompter_presets), style = MaterialTheme.typography.labelLarge, color = RgTheme.colors.textSecondary, modifier = Modifier.weight(1f))
                    RgTextButton(stringResource(R.string.prompter_preset_save), { showSaveDialog = true })
                }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Spacing.gutter),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    items(state.presets, key = { it.id }) { preset ->
                        val selected = preset.settings == s
                        val trailing: (@Composable () -> Unit)? = if (preset.builtIn) null else {
                            {
                                Icon(
                                    Icons.Rounded.Close,
                                    stringResource(UiR.string.action_delete),
                                    tint = if (selected) RgTheme.colors.onAccent else RgTheme.colors.textTertiary,
                                    modifier = Modifier.size(18.dp).clip(RoundedCornerShape(50)).pressable { presetToDelete = preset },
                                )
                            }
                        }
                        RgChip(presetDisplayName(preset), selected, { vm.applyPreset(preset) }, trailing = trailing)
                    }
                }
            }
        }

        // Speed
        item {
            RgGroup(title = stringResource(R.string.prompter_group_speed)) {
                Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    RgLabeledSlider(
                        label = stringResource(R.string.prompter_wpm_label),
                        value = s.wordsPerMinute.toFloat(),
                        onValueChange = { v -> vm.update { it.copy(wordsPerMinute = v.roundToInt()) } },
                        valueRange = TeleprompterSettings.MIN_WPM.toFloat()..TeleprompterSettings.MAX_WPM.toFloat(),
                        valueText = stringResource(R.string.prompter_wpm_value, s.wordsPerMinute.toString().localizeDigits()),
                        icon = Icons.Rounded.Speed,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        SpeedPresets.forEach { (labelRes, wpm) ->
                            RgChip(stringResource(labelRes), s.wordsPerMinute == wpm, { vm.update { it.copy(wordsPerMinute = wpm) } })
                        }
                    }
                }
            }
        }

        // Text
        item {
            RgGroup(title = stringResource(R.string.prompter_group_text)) {
                Text(
                    stringResource(R.string.prompter_font),
                    style = MaterialTheme.typography.titleSmall,
                    color = RgTheme.colors.textPrimary,
                    modifier = Modifier.padding(start = Spacing.md, end = Spacing.md, top = Spacing.md, bottom = Spacing.sm),
                )
                // Scrolls edge to edge inside the card instead of being clipped by its padding.
                FontPicker(s.font) { f -> vm.update { it.copy(font = f) } }
                Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    RgLabeledSlider(
                        stringResource(R.string.prompter_font_size), s.fontSizeSp, { v -> vm.update { it.copy(fontSizeSp = v.roundToInt().toFloat()) } },
                        valueRange = TeleprompterSettings.MIN_FONT_SP..TeleprompterSettings.MAX_FONT_SP,
                        valueText = s.fontSizeSp.roundToInt().toString().localizeDigits(),
                    )
                    RgLabeledSlider(
                        stringResource(R.string.prompter_font_weight), s.fontWeight.toFloat(), { v -> vm.update { it.copy(fontWeight = (v / 100f).roundToInt() * 100) } },
                        valueRange = 300f..900f, steps = 5,
                        valueText = weightLabel(s.fontWeight),
                    )
                    RgLabeledSlider(
                        stringResource(R.string.prompter_line_spacing), s.lineSpacing, { v -> vm.update { it.copy(lineSpacing = (v * 20).roundToInt() / 20f) } },
                        valueRange = 1f..2.5f,
                        valueText = decimal(s.lineSpacing, 2),
                    )
                    RgLabeledSlider(
                        stringResource(R.string.prompter_letter_spacing), s.letterSpacingEm, { v -> vm.update { it.copy(letterSpacingEm = (v * 100).roundToInt() / 100f) } },
                        valueRange = -0.05f..0.3f,
                        valueText = decimal(s.letterSpacingEm, 2),
                    )
                    Text(stringResource(R.string.prompter_alignment), style = MaterialTheme.typography.titleSmall, color = RgTheme.colors.textPrimary)
                    RgSegmentedControl(
                        options = PrompterTextAlign.entries,
                        selected = s.textAlign,
                        onSelect = { a -> vm.update { it.copy(textAlign = a) } },
                        label = { alignLabel(it) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    RgLabeledSlider(
                        stringResource(R.string.prompter_text_width), s.textWidthFraction * 100f, { v -> vm.update { it.copy(textWidthFraction = v.roundToInt() / 100f) } },
                        valueRange = 40f..100f,
                        valueText = percent(s.textWidthFraction),
                    )
                    RgLabeledSlider(
                        stringResource(R.string.prompter_padding), s.horizontalPaddingDp, { v -> vm.update { it.copy(horizontalPaddingDp = v.roundToInt().toFloat()) } },
                        valueRange = 0f..64f,
                        valueText = s.horizontalPaddingDp.roundToInt().toString().localizeDigits(),
                    )
                    Text(stringResource(R.string.prompter_direction), style = MaterialTheme.typography.titleSmall, color = RgTheme.colors.textPrimary)
                    RgSegmentedControl(
                        options = ContentDirection.entries,
                        selected = s.direction,
                        onSelect = { d -> vm.update { it.copy(direction = d) } },
                        label = { directionLabel(it) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // Colors
        item {
            RgGroup(title = stringResource(R.string.prompter_group_colors)) {
                Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    ColorRow(stringResource(R.string.prompter_text_color), TextColors, Color(s.textColor)) { c -> vm.update { it.copy(textColor = c.argb()) } }
                    ColorRow(stringResource(R.string.prompter_highlight_color), HighlightColors, Color(s.highlightColor)) { c -> vm.update { it.copy(highlightColor = c.argb()) } }
                    ColorRow(stringResource(R.string.prompter_background_color), BackgroundColors, Color(s.backgroundColor)) { c -> vm.update { it.copy(backgroundColor = c.argb()) } }
                    RgLabeledSlider(
                        stringResource(R.string.prompter_background_opacity), s.backgroundOpacity * 100f, { v -> vm.update { it.copy(backgroundOpacity = v.roundToInt() / 100f) } },
                        valueRange = 0f..100f,
                        valueText = percent(s.backgroundOpacity),
                    )
                }
            }
        }

        // Layout
        item {
            RgGroup(title = stringResource(R.string.prompter_group_layout)) {
                Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    RgLabeledSlider(
                        stringResource(R.string.prompter_eye_line), s.eyeLinePosition * 100f, { v -> vm.update { it.copy(eyeLinePosition = v.roundToInt() / 100f) } },
                        valueRange = 10f..70f,
                        valueText = percent(s.eyeLinePosition),
                    )
                    RgLabeledSlider(
                        stringResource(R.string.prompter_area_height), s.areaHeightFraction * 100f, { v -> vm.update { it.copy(areaHeightFraction = v.roundToInt() / 100f) } },
                        valueRange = 20f..80f,
                        valueText = percent(s.areaHeightFraction),
                    )
                    Text(stringResource(R.string.prompter_placement), style = MaterialTheme.typography.titleSmall, color = RgTheme.colors.textPrimary)
                    RgSegmentedControl(
                        options = PrompterPlacement.entries,
                        selected = s.placement,
                        onSelect = { p -> vm.update { it.copy(placement = p) } },
                        label = { placementLabel(it) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(R.string.prompter_placement_hint), style = MaterialTheme.typography.bodySmall, color = RgTheme.colors.textSecondary)
                }
                ToggleRow(stringResource(R.string.prompter_show_eye_line), null, s.showEyeLine) { v -> vm.update { it.copy(showEyeLine = v) } }
            }
        }

        // Mirror
        item {
            RgGroup(title = stringResource(R.string.prompter_group_mirror)) {
                ToggleRow(stringResource(R.string.prompter_mirror_h_long), null, s.mirrorHorizontal) { v -> vm.update { it.copy(mirrorHorizontal = v) } }
                ToggleRow(stringResource(R.string.prompter_mirror_v_long), null, s.mirrorVertical) { v -> vm.update { it.copy(mirrorVertical = v) } }
                Text(
                    stringResource(R.string.prompter_mirror_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = RgTheme.colors.textSecondary,
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                )
            }
        }

        // Behaviour
        item {
            RgGroup(title = stringResource(R.string.prompter_group_behaviour)) {
                Column(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
                    RgLabeledSlider(
                        stringResource(R.string.prompter_countdown), s.countdownSeconds.toFloat(), { v -> vm.update { it.copy(countdownSeconds = v.roundToInt()) } },
                        valueRange = 0f..10f, steps = 9,
                        valueText = if (s.countdownSeconds == 0) stringResource(R.string.prompter_off) else stringResource(R.string.prompter_seconds, s.countdownSeconds.toString().localizeDigits()),
                    )
                }
                ToggleRow(stringResource(R.string.prompter_loop), stringResource(R.string.prompter_loop_hint), s.loop) { v -> vm.update { it.copy(loop = v) } }
                ToggleRow(stringResource(R.string.prompter_dim_read), null, s.dimReadText) { v -> vm.update { it.copy(dimReadText = v) } }
                ToggleRow(stringResource(R.string.prompter_show_remaining), null, s.showRemainingTime) { v -> vm.update { it.copy(showRemainingTime = v) } }
                ToggleRow(stringResource(R.string.prompter_show_progress), null, s.showProgress) { v -> vm.update { it.copy(showProgress = v) } }
                ToggleRow(stringResource(R.string.prompter_tap_to_pause), stringResource(R.string.prompter_tap_to_pause_hint), s.tapToPause) { v -> vm.update { it.copy(tapToPause = v) } }
                ToggleRow(stringResource(R.string.prompter_volume_keys), stringResource(R.string.prompter_volume_keys_hint), s.volumeKeysControl) { v -> vm.update { it.copy(volumeKeysControl = v) } }
                if (s.volumeKeysControl) {
                    RgSegmentedControl(
                        options = VolumeKeyMode.entries,
                        selected = state.volumeKeyMode,
                        onSelect = vm::setVolumeKeyMode,
                        label = { if (it == VolumeKeyMode.SPEED) stringResource(R.string.prompter_volume_mode_speed) else stringResource(R.string.prompter_volume_mode_page) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs),
                    )
                }
                ToggleRow(stringResource(R.string.prompter_remote), stringResource(R.string.prompter_remote_hint), s.remoteControl) { v -> vm.update { it.copy(remoteControl = v) } }
                ToggleRow(stringResource(R.string.prompter_sync_recording), stringResource(R.string.prompter_sync_recording_hint), s.syncWithRecording) { v -> vm.update { it.copy(syncWithRecording = v) } }
            }
        }

        item {
            Box(Modifier.fillMaxWidth().padding(Spacing.gutter), contentAlignment = Alignment.Center) {
                RgOutlineButton(stringResource(R.string.prompter_reset_defaults), { showReset = true }, icon = Icons.Rounded.RestartAlt, size = RgButtonSize.MEDIUM)
            }
        }
        item { Spacer(Modifier.navigationBarsPadding()) }
    }

    if (showSaveDialog) {
        SavePresetDialog(onSave = { name -> vm.savePreset(name); showSaveDialog = false }, onDismiss = { showSaveDialog = false })
    }
    presetToDelete?.let { preset ->
        RgConfirmDialog(
            title = stringResource(R.string.prompter_preset_delete_title),
            message = stringResource(R.string.prompter_preset_delete_message, presetDisplayName(preset)),
            confirmText = stringResource(UiR.string.action_delete),
            dismissText = stringResource(UiR.string.action_cancel),
            onConfirm = { vm.deletePreset(preset); presetToDelete = null },
            onDismiss = { presetToDelete = null },
            destructive = true,
        )
    }
    if (showReset) {
        RgConfirmDialog(
            title = stringResource(R.string.prompter_reset_defaults),
            message = stringResource(R.string.prompter_reset_message),
            confirmText = stringResource(UiR.string.action_reset),
            dismissText = stringResource(UiR.string.action_cancel),
            onConfirm = { vm.resetToDefaults(); showReset = false },
            onDismiss = { showReset = false },
        )
    }
}

private val SpeedPresets = listOf(
    R.string.prompter_speed_slow to 100,
    R.string.prompter_speed_normal to 140,
    R.string.prompter_speed_fast to 180,
    R.string.prompter_speed_very_fast to 230,
)

@Composable
private fun Preview(state: PrompterSettingsUiState, live: Boolean) {
    val sample = state.scriptBody?.takeIf { it.isNotBlank() }?.take(1_500) ?: stringResource(R.string.prompter_preview_sample)
    // The preview loops continuously with no countdown so every change is visible immediately.
    val previewSettings = state.settings.copy(countdownSeconds = 0, loop = true)
    val controller = rememberPrompterController(sample, previewSettings)
    LaunchedEffect(controller, live) { if (live) controller.play() }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter, vertical = Spacing.sm)
            .height(220.dp)
            .clip(RoundedCornerShape(Radius.xl))
            .background(Color(0xFF0B0A12))
            .border(1.dp, RgTheme.colors.outline, RoundedCornerShape(Radius.xl)),
    ) {
        TeleprompterView(
            text = sample,
            settings = previewSettings.copy(fontSizeSp = (previewSettings.fontSizeSp * 0.6f).coerceAtLeast(TeleprompterSettings.MIN_FONT_SP)),
            controller = controller,
            modifier = Modifier.fillMaxSize(),
            interactive = false,
        )
        Text(
            stringResource(R.string.prompter_preview),
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(Spacing.sm)
                .clip(RoundedCornerShape(Radius.pill))
                .background(Color.Black.copy(alpha = 0.4f))
                .padding(horizontal = Spacing.sm, vertical = 2.dp),
        )
    }
}

@Composable
private fun FontPicker(selected: PrompterFont, onSelect: (PrompterFont) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        items(PrompterFont.entries, key = { it.name }) { font ->
            val isSelected = font == selected
            val shape = RoundedCornerShape(Radius.md)
            Column(
                Modifier
                    .width(112.dp)
                    .clip(shape)
                    .background(if (isSelected) RgTheme.colors.accentSoft else RgTheme.colors.surfaceMuted)
                    .border(if (isSelected) 2.dp else 1.dp, if (isSelected) RgTheme.colors.accent else RgTheme.colors.outline, shape)
                    .pressable { onSelect(font) }
                    .padding(Spacing.md),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.prompter_font_sample),
                    fontFamily = font.toFontFamily(),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = RgTheme.colors.textPrimary,
                    maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
                Text(fontLabel(font), style = MaterialTheme.typography.labelSmall, color = RgTheme.colors.textSecondary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ColorRow(label: String, colors: List<Color>, selected: Color, onSelect: (Color) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = RgTheme.colors.textPrimary)
        // Keep a custom (non-palette) color visible and selected.
        val palette = if (colors.any { it.argb() == selected.argb() }) colors else listOf(selected) + colors
        ColorSwatchRow(palette, selected, onSelect)
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    RgListItem(
        title = title,
        subtitle = subtitle,
        onClick = { onChange(!checked) },
        trailing = { RgSwitch(checked, onChange) },
    )
}

@Composable
private fun SavePresetDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RgTheme.colors.backgroundElevated,
        shape = RoundedCornerShape(Radius.xl),
        icon = { Icon(Icons.Rounded.BookmarkAdd, null, tint = RgTheme.colors.accent) },
        title = { Text(stringResource(R.string.prompter_preset_save)) },
        text = {
            RgTextField(value = name, onValueChange = { name = it.take(40) }, placeholder = stringResource(R.string.prompter_preset_name_hint))
        },
        confirmButton = { RgTextButton(stringResource(UiR.string.action_save), { onSave(name) }, enabled = name.isNotBlank()) },
        dismissButton = { RgTextButton(stringResource(UiR.string.action_cancel), onDismiss, color = RgTheme.colors.textSecondary) },
    )
}

@Composable
internal fun presetDisplayName(preset: TeleprompterPreset): String = when (preset.name) {
    "preset_prompter_standard" -> stringResource(R.string.prompter_preset_standard)
    "preset_prompter_large" -> stringResource(R.string.prompter_preset_large)
    "preset_prompter_fast" -> stringResource(R.string.prompter_preset_fast)
    "preset_prompter_camera" -> stringResource(R.string.prompter_preset_camera)
    "preset_prompter_mirror" -> stringResource(R.string.prompter_preset_mirror)
    "preset_prompter_night" -> stringResource(R.string.prompter_preset_night)
    else -> preset.name
}

/**
 * Matched by name so the label table also covers fonts added to the enum later (e.g. the brand font RAVAGH);
 * unknown values fall back to a readable form of the enum name.
 */
@Composable
private fun fontLabel(font: PrompterFont): String = when (font.name) {
    "RAVAGH" -> stringResource(R.string.prompter_font_ravagh)
    "VAZIRMATN" -> stringResource(R.string.prompter_font_vazirmatn)
    "SAHEL" -> stringResource(R.string.prompter_font_sahel)
    "SAMIM" -> stringResource(R.string.prompter_font_samim)
    "SYSTEM_SANS" -> stringResource(R.string.prompter_font_sans)
    "SYSTEM_SERIF" -> stringResource(R.string.prompter_font_serif)
    "SYSTEM_MONO" -> stringResource(R.string.prompter_font_mono)
    else -> font.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}

@Composable
private fun alignLabel(align: PrompterTextAlign): String = stringResource(
    when (align) {
        PrompterTextAlign.START -> R.string.prompter_align_start
        PrompterTextAlign.CENTER -> R.string.prompter_align_center
        PrompterTextAlign.END -> R.string.prompter_align_end
        PrompterTextAlign.JUSTIFY -> R.string.prompter_align_justify
    },
)

@Composable
private fun directionLabel(direction: ContentDirection): String = stringResource(
    when (direction) {
        ContentDirection.AUTO -> R.string.prompter_direction_auto
        ContentDirection.RTL -> R.string.prompter_direction_rtl
        ContentDirection.LTR -> R.string.prompter_direction_ltr
    },
)

@Composable
private fun placementLabel(placement: PrompterPlacement): String = stringResource(
    when (placement) {
        PrompterPlacement.TOP -> R.string.prompter_placement_top
        PrompterPlacement.CENTER -> R.string.prompter_placement_center
        PrompterPlacement.BOTTOM -> R.string.prompter_placement_bottom
    },
)

@Composable
private fun weightLabel(weight: Int): String = stringResource(
    when {
        weight <= 300 -> R.string.prompter_weight_light
        weight <= 400 -> R.string.prompter_weight_regular
        weight <= 500 -> R.string.prompter_weight_medium
        weight <= 600 -> R.string.prompter_weight_semibold
        weight <= 700 -> R.string.prompter_weight_bold
        else -> R.string.prompter_weight_black
    },
)

private fun decimal(value: Float, digits: Int): String = String.format(Locale.US, "%.${digits}f", value).localizeDigits()

private fun percent(fraction: Float): String = "${(fraction * 100).roundToInt()}٪".let {
    if (Locale.getDefault().language == "fa") it.localizeDigits() else it.replace("٪", "%")
}

