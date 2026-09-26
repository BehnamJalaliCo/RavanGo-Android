package com.ravango.feature.editor.tools

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.automirrored.rounded.BrandingWatermark
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ravango.core.designsystem.component.RgChipRow
import com.ravango.core.designsystem.component.RgPrimaryButton
import com.ravango.core.designsystem.component.RgProgressBar
import com.ravango.core.designsystem.component.RgTextButton
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.core.model.AudioTrackKind
import com.ravango.core.model.MediaKind
import com.ravango.core.model.OverlayItem
import com.ravango.core.model.ProFeature
import com.ravango.core.ui.AppPermission
import com.ravango.core.ui.PermissionRationaleCard
import com.ravango.core.ui.rememberPermissionRequester
import com.ravango.engine.editor.ops.EditOps
import com.ravango.engine.editor.ops.withTransform
import com.ravango.feature.editor.EditorUiState
import com.ravango.feature.editor.EditorViewModel
import com.ravango.feature.editor.R
import com.ravango.feature.editor.Selection
import com.ravango.feature.editor.ui.ActionRow
import com.ravango.feature.editor.ui.InfoCard
import com.ravango.feature.editor.ui.PanelSection
import com.ravango.feature.editor.ui.SwitchRow
import com.ravango.feature.editor.ui.ToolAction
import com.ravango.feature.editor.ui.ValueSlider
import com.ravango.feature.editor.ui.formatSeconds
import com.ravango.feature.editor.ui.percent
import com.ravango.feature.editor.ui.timecode
import kotlin.math.roundToLong

// ---------------------------------------------------------------------------------------------- overlay / PiP

@Composable
fun OverlayPanel(state: EditorUiState, vm: EditorViewModel) {
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::addMediaOverlay) }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::addMediaOverlay) }
    ActionRow {
        ToolAction(Icons.Rounded.Image, stringResource(R.string.editor_add_photo_overlay), { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
        ToolAction(
            Icons.Rounded.Videocam, stringResource(R.string.editor_add_pip), {
                if (state.has(ProFeature.EDITOR_PIP)) pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) else vm.upgrade(ProFeature.EDITOR_PIP)
            },
            pro = !state.has(ProFeature.EDITOR_PIP),
        )
    }
    if (!state.has(ProFeature.EDITOR_MULTI_LAYER)) InfoCard(stringResource(R.string.editor_single_layer_hint), tint = accent())
    val item = (state.selection as? Selection.Overlay)?.let { EditOps.findOverlay(state.document, it.id) }
    when (item) {
        is OverlayItem.Video -> {
            ItemActions(item, vm)
            ValueSlider(stringResource(R.string.editor_corner_radius), item.cornerRadius, { v -> vm.updateOverlay(item.id, "radius") { (it as OverlayItem.Video).copy(cornerRadius = v) } }, 0f..0.5f, percent(item.cornerRadius * 2), onFinished = vm::endGesture)
            SwitchRow(stringResource(R.string.editor_border), item.borderColor != null, { on -> vm.updateOverlay(item.id) { (it as OverlayItem.Video).copy(borderColor = if (on) 0xFFFFFFFF else null) } })
            if (item.source.hasAudio) {
                ValueSlider(stringResource(R.string.editor_pip_volume), item.volume, { v -> vm.updateOverlay(item.id, "pipvol") { (it as OverlayItem.Video).copy(volume = v) } }, 0f..1f, percent(item.volume), onFinished = vm::endGesture)
            }
            AnimationPickers(item, vm)
        }
        is OverlayItem.Image -> {
            ItemActions(item, vm)
            AnimationPickers(item, vm)
        }
        else -> Text(stringResource(R.string.editor_overlay_hint), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs))
    }
}

// ---------------------------------------------------------------------------------------------- logo / watermark

private enum class Corner(val x: Float, val y: Float, val label: Int) {
    TOP_START(0.16f, 0.08f, R.string.editor_corner_top_left),
    TOP_END(0.84f, 0.08f, R.string.editor_corner_top_right),
    BOTTOM_START(0.16f, 0.92f, R.string.editor_corner_bottom_left),
    BOTTOM_END(0.84f, 0.92f, R.string.editor_corner_bottom_right),
    CENTER(0.5f, 0.5f, R.string.editor_corner_center),
}

@Composable
fun LogoPanel(state: EditorUiState, vm: EditorViewModel) {
    val pickLogo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { vm.addLogo(it, asWatermark = false) } }
    val pickWatermark = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { vm.addLogo(it, asWatermark = true) } }
    ActionRow {
        ToolAction(Icons.Rounded.Image, stringResource(R.string.editor_add_logo), { pickLogo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
        ToolAction(Icons.AutoMirrored.Rounded.BrandingWatermark, stringResource(R.string.editor_add_watermark), { pickWatermark.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
    }
    val item = (state.selection as? Selection.Overlay)?.let { EditOps.findOverlay(state.document, it.id) } as? OverlayItem.Image
    if (item != null) {
        PanelSection(stringResource(R.string.editor_position)) {
            RgChipRow(Corner.entries.toList(), null, { c -> vm.updateOverlay(item.id) { it.withTransform(it.transform.copy(centerX = c.x, centerY = c.y)) } }, { stringResource(it.label) }, glass = true, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp))
        }
        ItemActions(item, vm)
        SwitchRow(stringResource(R.string.editor_is_watermark), item.isWatermark, { on -> vm.updateOverlay(item.id) { (it as OverlayItem.Image).copy(isWatermark = on) } })
    }
    val e = state.entitlements
    if (e.watermarkOnExport && !e.has(ProFeature.EXPORT_NO_WATERMARK)) {
        InfoCard(stringResource(R.string.editor_app_watermark_hint), tint = RgTheme.colors.warning) {
            RgTextButton(stringResource(R.string.editor_remove), onClick = { vm.upgrade(ProFeature.EXPORT_NO_WATERMARK) })
        }
    }
}

// ---------------------------------------------------------------------------------------------- music

@Composable
fun MusicPanel(state: EditorUiState, vm: EditorViewModel) {
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importMusic) }
    ActionRow {
        ToolAction(Icons.Rounded.LibraryMusic, stringResource(R.string.editor_import_music), { pickAudio.launch(arrayOf("audio/*")) })
    }
    val sel = (state.selection as? Selection.Audio)?.let { EditOps.findAudioClip(state.document, it.id) }
    if (sel == null) {
        Text(stringResource(R.string.editor_music_hint), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs))
        return
    }
    AudioClipControls(state, vm, sel.first, sel.second)
}

@Composable
private fun AudioClipControls(state: EditorUiState, vm: EditorViewModel, track: com.ravango.core.model.AudioTrack, clip: com.ravango.core.model.AudioClip) {
    val secs = stringResource(R.string.editor_unit_seconds)
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
        Text(
            timecode(clip.startUs) + " – " + timecode(clip.endUs),
            style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.weight(1f),
        )
        com.ravango.core.designsystem.component.RgIconButton(Icons.Rounded.Delete, stringResource(R.string.editor_delete), vm::deleteSelection, size = 36.dp, iconSize = 18.dp, glass = true)
    }
    ValueSlider(stringResource(R.string.editor_volume), clip.volume, { v -> vm.updateAudioClip(clip.id, "vol") { it.copy(volume = v) } }, 0f..2f, percent(clip.volume), onFinished = vm::endGesture)
    ValueSlider(stringResource(R.string.editor_fade_in), clip.fadeInUs / 1e6f, { v -> vm.updateAudioClip(clip.id, "fin") { it.copy(fadeInUs = (v * 1e6).roundToLong()) } }, 0f..5f, formatSeconds(clip.fadeInUs, secs), onFinished = vm::endGesture)
    ValueSlider(stringResource(R.string.editor_fade_out), clip.fadeOutUs / 1e6f, { v -> vm.updateAudioClip(clip.id, "fout") { it.copy(fadeOutUs = (v * 1e6).roundToLong()) } }, 0f..5f, formatSeconds(clip.fadeOutUs, secs), onFinished = vm::endGesture)
    if (track.kind == AudioTrackKind.MUSIC) SwitchRow(stringResource(R.string.editor_loop), clip.loop, { on -> vm.updateAudioClip(clip.id) { it.copy(loop = on) } })
    SwitchRow(stringResource(R.string.editor_ducking), track.ducking, { vm.setTrackDucking(track.id, it) })
    SwitchRow(stringResource(R.string.editor_mute_track), track.muted, { vm.setTrackMuted(track.id, it) })
    if (state.document.durationUs > 0 && clip.startUs >= state.document.durationUs) InfoCard(stringResource(R.string.editor_audio_beyond_end))
}

// ---------------------------------------------------------------------------------------------- voice-over

@Composable
fun VoiceOverPanel(state: EditorUiState, vm: EditorViewModel) {
    val mic = rememberPermissionRequester(AppPermission.MICROPHONE)
    if (!mic.allGranted) {
        PermissionRationaleCard(
            requester = mic,
            title = stringResource(com.ravango.core.ui.R.string.permission_mic_title),
            message = stringResource(R.string.editor_voiceover_permission),
            icon = Icons.Rounded.Mic,
            modifier = Modifier.padding(horizontal = Spacing.lg),
        )
        return
    }
    val vo = state.voiceOver
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        RgPrimaryButton(
            text = stringResource(if (vo.recording) R.string.editor_voiceover_stop else R.string.editor_voiceover_record),
            onClick = { if (vo.recording) vm.stopVoiceOver() else vm.startVoiceOver() },
            icon = if (vo.recording) Icons.Rounded.Stop else Icons.Rounded.FiberManualRecord,
            size = com.ravango.core.designsystem.component.RgButtonSize.MEDIUM,
            brush = if (vo.recording) RgTheme.colors.recordGradient else null,
        )
        if (vo.recording) Text(timecode(vo.elapsedUs), style = MaterialTheme.typography.titleMedium, color = Color.White)
    }
    if (vo.recording) {
        Spacer(Modifier.height(Spacing.sm))
        RgProgressBar(vo.level, Modifier.fillMaxWidth().padding(horizontal = Spacing.lg), height = 8.dp)
    }
    InfoCard(stringResource(R.string.editor_voiceover_hint), tint = accent())
    val sel = (state.selection as? Selection.Audio)?.let { EditOps.findAudioClip(state.document, it.id) }
    if (sel != null && !vo.recording) AudioClipControls(state, vm, sel.first, sel.second)
}

// ---------------------------------------------------------------------------------------------- clip audio

@Composable
fun AudioPanel(state: EditorUiState, vm: EditorViewModel) {
    val clip = state.selectedClip() ?: return SelectClipHint(vm)
    if (clip.source.kind != MediaKind.VIDEO || !clip.source.hasAudio) {
        InfoCard(stringResource(R.string.editor_msg_no_audio))
        return
    }
    val nrPro = state.has(ProFeature.AUDIO_NOISE_REDUCTION)
    ActionRow {
        ToolAction(Icons.Rounded.Download, stringResource(R.string.editor_extract_audio), vm::extractAudio, enabled = !clip.muted)
        ToolAction(Icons.Rounded.LinkOff, stringResource(R.string.editor_detach_audio), vm::detachAudio, enabled = !clip.muted && !clip.reversed)
        ToolAction(Icons.Rounded.AutoFixHigh, stringResource(R.string.editor_ai_cleanup), vm::aiAudioCleanup, pro = !nrPro, enabled = !clip.muted)
    }
    if (clip.reversed) InfoCard(stringResource(R.string.editor_reversed_no_audio))
    ValueSlider(stringResource(R.string.editor_volume), clip.volume, { vm.setClipVolume(it, true) }, 0f..2f, percent(clip.volume), onFinished = vm::endGesture)
    ValueSlider(stringResource(R.string.editor_noise_reduction), clip.noiseReduction, { vm.setNoiseReduction(it, true) }, 0f..1f, percent(clip.noiseReduction), pro = !nrPro, onFinished = vm::endGesture)
    SwitchRow(stringResource(R.string.editor_voice_enhance), clip.voiceEnhance, vm::setVoiceEnhance)
    SwitchRow(stringResource(R.string.editor_mute), clip.muted, { vm.toggleMute() })
}
