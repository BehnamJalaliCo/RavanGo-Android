package com.ravango.feature.editor.tools

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.Gradient
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import com.ravango.core.designsystem.component.RgChipRow
import com.ravango.core.designsystem.component.RgTextButton
import com.ravango.core.model.CropRect
import com.ravango.core.model.MediaKind
import com.ravango.core.model.ProFeature
import com.ravango.core.model.VideoClip
import com.ravango.feature.editor.EditorUiState
import com.ravango.feature.editor.EditorActions
import com.ravango.feature.editor.R
import com.ravango.feature.editor.Selection
import com.ravango.feature.editor.ui.ActionRow
import com.ravango.feature.editor.ui.InfoCard
import com.ravango.feature.editor.ui.PanelSection
import com.ravango.feature.editor.ui.SwitchRow
import com.ravango.feature.editor.ui.ToolAction
import com.ravango.feature.editor.ui.ValueSlider
import com.ravango.feature.editor.ui.formatSeconds
import com.ravango.feature.editor.ui.localized
import com.ravango.feature.editor.ui.percent
import kotlin.math.roundToLong

internal fun EditorUiState.selectedClip(): VideoClip? =
    (selection as? Selection.Clip)?.let { s -> document.mainTrack.firstOrNull { it.id == s.id } }

/** Shown by clip-specific tools when nothing is selected. */
@Composable
internal fun SelectClipHint(vm: EditorActions) {
    InfoCard(stringResource(R.string.editor_hint_select_clip), icon = Icons.Rounded.TouchApp, tint = accent()) {
        RgTextButton(stringResource(R.string.editor_select_at_playhead), onClick = vm::selectClipAtPlayhead)
    }
}

private enum class EditSub { SPEED, CROP, VOLUME, FADES, DURATION }

@Composable
fun EditPanel(state: EditorUiState, vm: EditorActions) {
    val clip = state.selectedClip()
    var sub by rememberSaveable { mutableStateOf<EditSub?>(null) }
    val secs = stringResource(R.string.editor_unit_seconds)
    ActionRow {
        ToolAction(Icons.Rounded.ContentCut, stringResource(R.string.editor_split), vm::split)
        if (clip != null) {
            ToolAction(Icons.Rounded.Delete, stringResource(R.string.editor_delete), vm::deleteSelection)
            ToolAction(Icons.Rounded.ContentCopy, stringResource(R.string.editor_duplicate), vm::duplicateSelection)
            if (vm.canJoin(clip.id)) ToolAction(Icons.Rounded.Link, stringResource(R.string.editor_join), vm::joinWithNext)
            if (clip.source.kind == MediaKind.VIDEO) {
                ToolAction(Icons.Rounded.Speed, stringResource(R.string.editor_speed), { sub = if (sub == EditSub.SPEED) null else EditSub.SPEED }, selected = sub == EditSub.SPEED)
                ToolAction(
                    Icons.Rounded.Replay, stringResource(R.string.editor_reverse), vm::toggleReverse,
                    selected = clip.reversed, pro = !state.has(ProFeature.EDITOR_REVERSE), busy = clip.id in state.reversing,
                )
                ToolAction(Icons.Rounded.AcUnit, stringResource(R.string.editor_freeze), { vm.freezeFrame() })
            } else {
                ToolAction(Icons.Rounded.Timer, stringResource(R.string.editor_duration), { sub = if (sub == EditSub.DURATION) null else EditSub.DURATION }, selected = sub == EditSub.DURATION)
            }
            ToolAction(Icons.Rounded.Crop, stringResource(R.string.editor_crop), { sub = if (sub == EditSub.CROP) null else EditSub.CROP }, selected = sub == EditSub.CROP)
            ToolAction(Icons.AutoMirrored.Rounded.RotateRight, stringResource(R.string.editor_rotate), vm::rotateClip)
            ToolAction(Icons.Rounded.Flip, stringResource(R.string.editor_flip_h), { vm.flipClip(true) }, selected = clip.flipHorizontal)
            ToolAction(Icons.Rounded.Flip, stringResource(R.string.editor_flip_v), { vm.flipClip(false) }, selected = clip.flipVertical, modifier = Modifier.graphicsLayer { rotationZ = 90f })
            if (clip.source.kind == MediaKind.VIDEO && clip.source.hasAudio) {
                ToolAction(Icons.AutoMirrored.Rounded.VolumeUp, stringResource(R.string.editor_volume), { sub = if (sub == EditSub.VOLUME) null else EditSub.VOLUME }, selected = sub == EditSub.VOLUME)
                ToolAction(Icons.Rounded.LinkOff, stringResource(R.string.editor_detach_audio), vm::detachAudio, enabled = !clip.muted && !clip.reversed)
            }
            ToolAction(Icons.Rounded.Gradient, stringResource(R.string.editor_fades), { sub = if (sub == EditSub.FADES) null else EditSub.FADES }, selected = sub == EditSub.FADES)
            ToolAction(Icons.Rounded.RestartAlt, stringResource(R.string.editor_reset_transform), vm::resetClipTransform)
        }
    }
    if (clip == null) {
        SelectClipHint(vm)
        return
    }
    if (clip.id in state.reversing) InfoCard(stringResource(R.string.editor_reversing_hint))
    AnimatedVisibility(sub != null) {
        Column(Modifier.fillMaxWidth()) {
            when (sub) {
                EditSub.SPEED -> SpeedControls(clip, vm)
                EditSub.CROP -> CropControls(clip, vm)
                EditSub.VOLUME -> {
                    ValueSlider(stringResource(R.string.editor_volume), clip.volume, { vm.setClipVolume(it, true) }, 0f..2f, percent(clip.volume), onFinished = vm::endGesture)
                    SwitchRow(stringResource(R.string.editor_mute), clip.muted, { vm.toggleMute() })
                }
                EditSub.FADES -> {
                    if (clip.source.hasAudio && clip.source.kind == MediaKind.VIDEO) {
                        ValueSlider(stringResource(R.string.editor_audio_fade_in), clip.audioFadeInUs / 1e6f, { vm.setAudioFades((it * 1e6).roundToLong(), clip.audioFadeOutUs, true) }, 0f..3f, formatSeconds(clip.audioFadeInUs, secs), onFinished = vm::endGesture)
                        ValueSlider(stringResource(R.string.editor_audio_fade_out), clip.audioFadeOutUs / 1e6f, { vm.setAudioFades(clip.audioFadeInUs, (it * 1e6).roundToLong(), true) }, 0f..3f, formatSeconds(clip.audioFadeOutUs, secs), onFinished = vm::endGesture)
                    }
                    ValueSlider(stringResource(R.string.editor_video_fade_in), clip.videoFadeInUs / 1e6f, { vm.setVideoFades((it * 1e6).roundToLong(), clip.videoFadeOutUs, true) }, 0f..3f, formatSeconds(clip.videoFadeInUs, secs), onFinished = vm::endGesture)
                    ValueSlider(stringResource(R.string.editor_video_fade_out), clip.videoFadeOutUs / 1e6f, { vm.setVideoFades(clip.videoFadeInUs, (it * 1e6).roundToLong(), true) }, 0f..3f, formatSeconds(clip.videoFadeOutUs, secs), onFinished = vm::endGesture)
                }
                EditSub.DURATION -> ValueSlider(
                    stringResource(R.string.editor_duration), clip.stillDurationUs / 1e6f, { vm.setStillDuration((it * 1e6).roundToLong(), true) }, 0.5f..15f,
                    formatSeconds(clip.stillDurationUs, secs), onFinished = vm::endGesture,
                )
                null -> Unit
            }
        }
    }
}

private val SpeedPresets = listOf(0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 3f, 4f)

@Composable
private fun SpeedControls(clip: VideoClip, vm: EditorActions) {
    PanelSection(stringResource(R.string.editor_speed)) {
        RgChipRow(SpeedPresets, SpeedPresets.firstOrNull { it == clip.speed }, { vm.setSpeed(it, gesture = false) }, { localized(trim(it)) + "×" }, glass = true)
    }
    ValueSlider(stringResource(R.string.editor_speed_custom), clip.speed, { vm.setSpeed(it, gesture = true) }, 0.25f..4f, localized(trim(clip.speed)) + "×", onFinished = vm::endGesture)
    InfoCard(stringResource(R.string.editor_speed_hint, com.ravango.feature.editor.ui.timecode(clip.outputDurationUs, tenths = true)), tint = accent())
}

private fun trim(v: Float): String = if (v == v.toInt().toFloat()) v.toInt().toString() else ((v * 100).toInt() / 100f).toString()

private data class CropPreset(val label: Int, val ratio: Float?)

private val CropPresets = listOf(
    CropPreset(R.string.editor_crop_free, null),
    CropPreset(R.string.editor_ratio_9_16, 9f / 16f),
    CropPreset(R.string.editor_ratio_1_1, 1f),
    CropPreset(R.string.editor_ratio_4_5, 4f / 5f),
    CropPreset(R.string.editor_ratio_16_9, 16f / 9f),
)

@Composable
private fun CropControls(clip: VideoClip, vm: EditorActions) {
    val c = clip.crop
    PanelSection(stringResource(R.string.editor_crop)) {
        RgChipRow(CropPresets, null, { p ->
            if (p.ratio == null) vm.setCrop(CropRect(), gesture = false)
            else vm.setCrop(centeredCrop(clip, p.ratio), gesture = false)
        }, { stringResource(it.label) }, glass = true)
    }
    ValueSlider(stringResource(R.string.editor_crop_left), c.left, { vm.setCrop(c.copy(left = it), true) }, 0f..0.45f, percent(c.left), onFinished = vm::endGesture)
    ValueSlider(stringResource(R.string.editor_crop_right), 1f - c.right, { vm.setCrop(c.copy(right = 1f - it), true) }, 0f..0.45f, percent(1f - c.right), onFinished = vm::endGesture)
    ValueSlider(stringResource(R.string.editor_crop_top), c.top, { vm.setCrop(c.copy(top = it), true) }, 0f..0.45f, percent(c.top), onFinished = vm::endGesture)
    ValueSlider(stringResource(R.string.editor_crop_bottom), 1f - c.bottom, { vm.setCrop(c.copy(bottom = 1f - it), true) }, 0f..0.45f, percent(1f - c.bottom), onFinished = vm::endGesture)
}

/** Largest centered crop of the upright source with the displayed aspect [ratio] (after quarter turns). */
private fun centeredCrop(clip: VideoClip, ratio: Float): CropRect {
    val w = clip.source.width.takeIf { it > 0 }?.toFloat() ?: 1080f
    val h = clip.source.height.takeIf { it > 0 }?.toFloat() ?: 1920f
    val odd = clip.rotationQuarterTurns % 2 == 1
    val target = if (odd) 1f / ratio else ratio
    val src = w / h
    return if (src > target) {
        val frac = target / src
        CropRect((1f - frac) / 2f, 0f, (1f + frac) / 2f, 1f)
    } else {
        val frac = src / target
        CropRect(0f, (1f - frac) / 2f, 1f, (1f + frac) / 2f)
    }
}
