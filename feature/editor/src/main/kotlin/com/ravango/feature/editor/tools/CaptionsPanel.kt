package com.ravango.feature.editor.tools

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.automirrored.rounded.CallMerge
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ravango.core.designsystem.component.ColorSwatchRow
import com.ravango.core.designsystem.component.RgButtonSize
import com.ravango.core.designsystem.component.RgChip
import com.ravango.core.designsystem.component.RgChipRow
import com.ravango.core.designsystem.component.RgIconButton
import com.ravango.core.designsystem.component.RgPrimaryButton
import com.ravango.core.designsystem.component.RgSegmentedControl
import com.ravango.core.designsystem.component.RgTextButton
import com.ravango.core.designsystem.component.RgTextField
import com.ravango.core.designsystem.component.pressable
import com.ravango.core.designsystem.theme.Radius
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.core.model.ProFeature
import com.ravango.core.model.SubtitleAnimation
import com.ravango.core.model.SubtitleCue
import com.ravango.feature.editor.EditorUiState
import com.ravango.feature.editor.EditorViewModel
import com.ravango.feature.editor.R
import com.ravango.feature.editor.Selection
import com.ravango.feature.editor.ui.ActionRow
import com.ravango.feature.editor.ui.InfoCard
import com.ravango.feature.editor.ui.PanelSection
import com.ravango.feature.editor.ui.SwatchColors
import com.ravango.feature.editor.ui.SwitchRow
import com.ravango.feature.editor.ui.ToolAction
import com.ravango.feature.editor.ui.ValueSlider
import com.ravango.feature.editor.ui.localized
import com.ravango.feature.editor.ui.percent
import com.ravango.feature.editor.ui.timecode
import com.ravango.feature.editor.ui.toArgbLong
import com.ravango.feature.editor.ui.toComposeColor
import kotlin.math.roundToInt

private enum class CaptionTab { AUTO, EDIT, STYLE }

private enum class CaptionLanguage(val code: String?, val label: Int) {
    AUTO(null, R.string.editor_lang_auto),
    FA("fa", R.string.editor_lang_fa),
    EN("en", R.string.editor_lang_en),
}

fun SubtitleAnimation.label(): Int = when (this) {
    SubtitleAnimation.NONE -> R.string.editor_anim_none
    SubtitleAnimation.FADE -> R.string.editor_anim_fade
    SubtitleAnimation.POP -> R.string.editor_anim_pop
    SubtitleAnimation.KARAOKE -> R.string.editor_anim_karaoke
    SubtitleAnimation.WORD_BY_WORD -> R.string.editor_anim_word_by_word
    SubtitleAnimation.SLIDE_UP -> R.string.editor_anim_slide_up
}

@Composable
fun CaptionsPanel(state: EditorUiState, vm: EditorViewModel) {
    var tab by rememberSaveable { mutableStateOf(if (state.document.subtitles.cues.isEmpty()) CaptionTab.AUTO else CaptionTab.EDIT) }
    LaunchedEffect(state.selection) { if (state.selection is Selection.Cue) tab = CaptionTab.EDIT }
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg)) {
        RgSegmentedControl(CaptionTab.entries.toList(), tab, { tab = it }, {
            stringResource(
                when (it) {
                    CaptionTab.AUTO -> R.string.editor_captions_auto
                    CaptionTab.EDIT -> R.string.editor_captions_edit
                    CaptionTab.STYLE -> R.string.editor_captions_style
                },
            )
        }, glass = true, modifier = Modifier.fillMaxWidth())
    }
    Spacer(Modifier.height(Spacing.sm))
    when (tab) {
        CaptionTab.AUTO -> AutoCaptions(state, vm)
        CaptionTab.EDIT -> CueEditor(state, vm)
        CaptionTab.STYLE -> SubtitleStyleEditor(state, vm)
    }
}

@Composable
private fun AutoCaptions(state: EditorUiState, vm: EditorViewModel) {
    var lang by rememberSaveable { mutableStateOf(CaptionLanguage.AUTO) }
    val services = state.services
    val importSrt = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importSrt) }
    val exportSrt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri -> uri?.let(vm::exportSrt) }
    when {
        !services.speechConfigured -> InfoCard(
            stringResource(R.string.editor_speech_not_configured) + (services.speechDetail?.let { "\n$it" } ?: ""),
            icon = Icons.Rounded.Info,
        ) { RgTextButton(stringResource(R.string.editor_open_settings), onClick = vm::openSettings) }
        services.speechConsentRequired -> InfoCard(stringResource(R.string.editor_speech_consent), icon = Icons.Rounded.Info) {
            RgTextButton(stringResource(R.string.editor_open_settings), onClick = vm::openSettings)
        }
    }
    PanelSection(stringResource(R.string.editor_language)) {
        RgChipRow(CaptionLanguage.entries.toList(), lang, { lang = it }, { stringResource(it.label) }, glass = true, contentPadding = PaddingValues(0.dp))
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
        RgPrimaryButton(
            stringResource(R.string.editor_generate_captions),
            onClick = { vm.autoCaption(lang.code) },
            icon = Icons.Rounded.ClosedCaption,
            size = RgButtonSize.MEDIUM,
            enabled = services.speechConfigured && state.busy == null,
        )
        if (!state.has(ProFeature.AUTO_CAPTIONS)) {
            Spacer(Modifier.width(Spacing.sm))
            com.ravango.core.designsystem.component.ProBadge()
        }
    }
    Spacer(Modifier.height(Spacing.xs))
    SwitchRow(stringResource(R.string.editor_captions_visible), state.document.subtitles.visible, vm::setSubtitlesVisible)
    SwitchRow(stringResource(R.string.editor_captions_burn_in), state.document.subtitles.burnIn, vm::setBurnIn)
    ActionRow {
        ToolAction(Icons.Rounded.FileUpload, stringResource(R.string.editor_import_srt), { importSrt.launch(arrayOf("application/x-subrip", "text/plain", "application/octet-stream", "*/*")) })
        ToolAction(Icons.Rounded.FileDownload, stringResource(R.string.editor_export_srt), { exportSrt.launch("captions.srt") }, enabled = state.document.subtitles.cues.isNotEmpty())
        ToolAction(Icons.Rounded.Delete, stringResource(R.string.editor_clear_captions), vm::clearCues, enabled = state.document.subtitles.cues.isNotEmpty())
    }
}

@Composable
private fun CueEditor(state: EditorUiState, vm: EditorViewModel) {
    val cues = state.document.subtitles.cues
    var newText by rememberSaveable { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
        RgTextField(newText, { newText = it }, Modifier.weight(1f), placeholder = stringResource(R.string.editor_new_caption))
        Spacer(Modifier.width(Spacing.sm))
        RgIconButton(Icons.Rounded.Add, stringResource(R.string.editor_add_caption), {
            vm.addCueAtPlayhead(newText.trim())
            newText = ""
        }, enabled = newText.isNotBlank(), selected = true)
    }
    if (cues.isEmpty()) {
        Text(stringResource(R.string.editor_no_captions), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(Spacing.lg))
        return
    }
    val selectedId = (state.selection as? Selection.Cue)?.id
    val listState = rememberLazyListState()
    LaunchedEffect(selectedId) {
        val i = cues.indexOfFirst { it.id == selectedId }
        if (i >= 0) listState.animateScrollToItem(i)
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().height(230.dp), contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        items(cues, key = { it.id }) { cue -> CueRow(cue, cue.id == selectedId, vm) }
    }
}

@Composable
private fun CueRow(cue: SubtitleCue, selected: Boolean, vm: EditorViewModel) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (selected) accent().copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f), RoundedCornerShape(Radius.sm))
            .padding(Spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                timecode(cue.startUs) + " → " + timecode(cue.endUs),
                style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.weight(1f).pressable {
                    vm.select(Selection.Cue(cue.id))
                    vm.seekTo(cue.startUs)
                },
            )
            Nudge(Icons.Rounded.Remove) { vm.updateCue(cue.id) { it.copy(startUs = (it.startUs - 100_000).coerceAtLeast(0)) } }
            Nudge(Icons.Rounded.Add) { vm.updateCue(cue.id) { it.copy(startUs = minOf(it.startUs + 100_000, it.endUs - 100_000)) } }
            Spacer(Modifier.width(6.dp))
            Nudge(Icons.Rounded.Remove) { vm.updateCue(cue.id) { it.copy(endUs = maxOf(it.endUs - 100_000, it.startUs + 100_000)) } }
            Nudge(Icons.Rounded.Add) { vm.updateCue(cue.id) { it.copy(endUs = it.endUs + 100_000) } }
        }
        RgTextField(cue.text, { t -> vm.updateCue(cue.id, "cuetext") { it.copy(text = t) } }, Modifier.fillMaxWidth(), singleLine = false, maxLines = 3)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            RgIconButton(Icons.Rounded.ContentCut, stringResource(R.string.editor_split_at_playhead), { vm.splitCueAtPlayhead(cue.id) }, size = 32.dp, iconSize = 16.dp, glass = true)
            RgIconButton(Icons.AutoMirrored.Rounded.CallMerge, stringResource(R.string.editor_merge_next), { vm.mergeCueWithNext(cue.id) }, size = 32.dp, iconSize = 16.dp, glass = true)
            RgIconButton(Icons.Rounded.Delete, stringResource(R.string.editor_delete), { vm.deleteCue(cue.id) }, size = 32.dp, iconSize = 16.dp, glass = true)
        }
    }
}

@Composable
private fun Nudge(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    RgIconButton(icon, null, onClick, size = 26.dp, iconSize = 14.dp, glass = true)
}

@Composable
private fun SubtitleStyleEditor(state: EditorUiState, vm: EditorViewModel) {
    val style = state.document.subtitles.style
    PanelSection(stringResource(R.string.editor_font)) {}
    FontPicker(style.font) { vm.setSubtitleStyle(style.copy(font = it)) }
    ValueSlider(stringResource(R.string.editor_text_size), style.sizeSp, { vm.setSubtitleStyle(style.copy(sizeSp = it), true) }, 12f..60f, localized("${style.sizeSp.roundToInt()}"), onFinished = vm::endGesture)
    PanelSection(stringResource(R.string.editor_color)) {
        ColorSwatchRow(SwatchColors.map { it.toComposeColor() }, style.color.toComposeColor(), { vm.setSubtitleStyle(style.copy(color = it.toArgbLong())) })
    }
    PanelSection(stringResource(R.string.editor_active_word_color)) {
        ColorSwatchRow(SwatchColors.map { it.toComposeColor() }, style.activeWordColor.toComposeColor(), { vm.setSubtitleStyle(style.copy(activeWordColor = it.toArgbLong())) })
    }
    PanelSection(stringResource(R.string.editor_text_background)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RgChip(stringResource(R.string.editor_none), style.backgroundColor == null, { vm.setSubtitleStyle(style.copy(backgroundColor = null)) }, glass = true)
            Spacer(Modifier.width(Spacing.sm))
            ColorSwatchRow(SwatchColors.map { it.toComposeColor() }, style.backgroundColor?.let { (it or 0xFF000000).toComposeColor() }, { vm.setSubtitleStyle(style.copy(backgroundColor = (it.toArgbLong() and 0x00FFFFFF) or 0x99000000)) }, swatchSize = 28.dp)
        }
    }
    SwitchRow(stringResource(R.string.editor_outline), style.outlineColor != null, { vm.setSubtitleStyle(style.copy(outlineColor = if (it) 0xFF000000 else null)) })
    ValueSlider(stringResource(R.string.editor_caption_position), style.positionY, { vm.setSubtitleStyle(style.copy(positionY = it), true) }, 0.1f..0.95f, percent(style.positionY), onFinished = vm::endGesture)
    ValueSlider(stringResource(R.string.editor_caption_width), style.maxWidthFraction, { vm.setSubtitleStyle(style.copy(maxWidthFraction = it), true) }, 0.4f..1f, percent(style.maxWidthFraction), onFinished = vm::endGesture)
    PanelSection(stringResource(R.string.editor_animation)) {
        RgChipRow(SubtitleAnimation.entries.toList(), style.animation, { vm.setSubtitleStyle(style.copy(animation = it)) }, { stringResource(it.label()) }, glass = true, contentPadding = PaddingValues(0.dp))
    }
    SwitchRow(stringResource(R.string.editor_uppercase), style.uppercase, { vm.setSubtitleStyle(style.copy(uppercase = it)) })
}
