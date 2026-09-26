@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ravango.feature.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ravango.core.common.format.localizeDigits
import com.ravango.core.designsystem.component.ProBadge
import com.ravango.core.designsystem.component.RgBottomSheet
import com.ravango.core.designsystem.component.RgChip
import com.ravango.core.designsystem.component.RgLabeledSlider
import com.ravango.core.designsystem.component.RgListItem
import com.ravango.core.designsystem.component.RgSegmentedControl
import com.ravango.core.designsystem.component.RgSwitch
import com.ravango.core.designsystem.component.pressable
import com.ravango.core.designsystem.theme.Palette
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.core.model.AspectRatioSpec
import com.ravango.core.model.AudioInputDevice
import com.ravango.core.model.AudioInputType
import com.ravango.core.model.AudioLevel
import com.ravango.core.model.AudioSettings
import com.ravango.core.model.BitrateProfile
import com.ravango.core.model.CameraSettings
import com.ravango.core.model.CaptureMode
import com.ravango.core.model.Entitlements
import com.ravango.core.model.GridType
import com.ravango.core.model.LensFacing
import com.ravango.core.model.ProFeature
import com.ravango.core.model.SafeAreaType
import com.ravango.core.model.VideoCodec
import com.ravango.core.model.VideoSize
import com.ravango.engine.camera.capability.CameraCapabilities
import com.ravango.feature.camera.R
import java.util.Locale

@Composable
private fun SheetSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.sm)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(bottom = Spacing.sm))
        content()
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null, enabled: Boolean = true, pro: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = if (enabled) Color.White else Color.White.copy(alpha = 0.45f))
                if (pro) { Spacer(Modifier.width(6.dp)); ProBadge() }
            }
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
        }
        RgSwitch(checked, onChange, enabled = enabled)
    }
}

/** Resolution × frame rate (only supported combinations), codec and quality. */
@Composable
internal fun ResolutionSheet(
    caps: CameraCapabilities,
    settings: CameraSettings,
    entitlements: Entitlements,
    onSelect: (VideoSize, Int) -> Unit,
    onCodec: (VideoCodec) -> Unit,
    onQuality: (BitrateProfile) -> Unit,
    onRequirePro: (ProFeature) -> Unit,
    onDismiss: () -> Unit,
) {
    RgBottomSheet(onDismiss = onDismiss, title = stringResource(R.string.camera_resolution_title), dark = true) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            caps.videoSizes.forEach { size ->
                val sizeGated = size.shortSide > 1080 && !entitlements.has(ProFeature.RECORD_4K)
                SheetSection(size.label.localizeDigits()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        caps.frameRatesFor(size).forEach { fps ->
                            val fpsGated = fps > 30 && !entitlements.has(ProFeature.RECORD_HIGH_FPS)
                            val gated = sizeGated || fpsGated
                            RgChip(
                                text = stringResource(R.string.camera_fps, fps.toString().localizeDigits()),
                                selected = settings.resolution == size && settings.frameRate == fps,
                                onClick = {
                                    when {
                                        sizeGated -> onRequirePro(ProFeature.RECORD_4K)
                                        fpsGated -> onRequirePro(ProFeature.RECORD_HIGH_FPS)
                                        else -> onSelect(size, fps)
                                    }
                                },
                                glass = true,
                                trailing = if (gated) ({ ProBadge() }) else null,
                            )
                        }
                    }
                }
            }
            val mode = caps.modeFor(settings.resolution, settings.frameRate)
            if ((mode?.codecs?.size ?: 0) > 1) {
                SheetSection(stringResource(R.string.camera_codec)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        mode!!.codecs.forEach { codec ->
                            val gated = codec == VideoCodec.HEVC && !entitlements.has(ProFeature.RECORD_HEVC)
                            RgChip(
                                text = stringResource(if (codec == VideoCodec.HEVC) R.string.camera_codec_hevc else R.string.camera_codec_h264),
                                selected = settings.codec == codec,
                                onClick = { if (gated) onRequirePro(ProFeature.RECORD_HEVC) else onCodec(codec) },
                                glass = true,
                                trailing = if (gated) ({ ProBadge() }) else null,
                            )
                        }
                    }
                }
            }
            SheetSection(stringResource(R.string.camera_quality)) {
                RgSegmentedControl(
                    options = BitrateProfile.entries,
                    selected = settings.bitrateProfile,
                    onSelect = onQuality,
                    label = { stringResource(when (it) { BitrateProfile.STANDARD -> R.string.camera_quality_standard; BitrateProfile.HIGH -> R.string.camera_quality_high; BitrateProfile.MAX -> R.string.camera_quality_max }) },
                    glass = true,
                )
            }
        }
    }
}

@Composable
internal fun AspectSheet(selected: AspectRatioSpec, onSelect: (AspectRatioSpec) -> Unit, onDismiss: () -> Unit) {
    RgBottomSheet(onDismiss = onDismiss, title = stringResource(R.string.camera_aspect_title), dark = true) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.gutter, vertical = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.Bottom,
        ) {
            AspectRatioSpec.Presets.forEach { spec ->
                val isSelected = spec == selected
                Column(
                    Modifier.clip(RoundedCornerShape(14.dp)).pressable { onSelect(spec) }.padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val h = 56.dp
                    val w = (56f * spec.ratio).coerceIn(24f, 100f).dp
                    val boxH = if (spec.ratio > 1.8f) (100f / spec.ratio).dp else h
                    Box(
                        Modifier
                            .size(width = if (spec.ratio > 1.8f) 100.dp else w, height = boxH)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSelected) RgTheme.colors.accent.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.08f))
                            .border(2.dp, if (isSelected) RgTheme.colors.accent else Color.White.copy(alpha = 0.4f), RoundedCornerShape(6.dp)),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(aspectLabel(spec), style = MaterialTheme.typography.labelLarge, color = if (isSelected) RgTheme.colors.accent else Color.White)
                    Text(aspectUse(spec), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
                }
            }
        }
    }
}

@Composable
private fun aspectUse(spec: AspectRatioSpec): String = stringResource(
    when (spec) {
        AspectRatioSpec.Portrait9x16 -> R.string.camera_aspect_use_vertical
        AspectRatioSpec.Landscape16x9 -> R.string.camera_aspect_use_youtube
        AspectRatioSpec.Square1x1 -> R.string.camera_aspect_use_square
        AspectRatioSpec.Portrait4x5 -> R.string.camera_aspect_use_feed
        AspectRatioSpec.Cinema21x9 -> R.string.camera_aspect_use_cinema
        else -> R.string.camera_aspect_use_classic
    },
)

@Composable
internal fun StudioSettingsSheet(
    caps: CameraCapabilities?,
    settings: CameraSettings,
    locked: Boolean,
    onChange: ((CameraSettings) -> CameraSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    RgBottomSheet(onDismiss = onDismiss, title = stringResource(R.string.camera_settings), dark = true) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SheetSection(stringResource(R.string.camera_grid)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    GridType.entries.forEach { g -> RgChip(g.label(), settings.grid == g, { onChange { it.copy(grid = g) } }, glass = true) }
                }
            }
            SheetSection(stringResource(R.string.camera_safe_area)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SafeAreaType.entries.forEach { s -> RgChip(s.label(), settings.safeArea == s, { onChange { it.copy(safeArea = s) } }, glass = true) }
                }
            }
            SheetSection(stringResource(R.string.camera_timer)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    listOf(0, 3, 5, 10).forEach { t ->
                        RgChip(
                            if (t == 0) stringResource(R.string.camera_timer_off) else stringResource(R.string.camera_timer_seconds, t.toString().localizeDigits()),
                            settings.timerSeconds == t,
                            { onChange { it.copy(timerSeconds = t) } },
                            glass = true,
                        )
                    }
                }
            }
            Column(Modifier.padding(horizontal = Spacing.gutter)) {
                SwitchRow(stringResource(R.string.camera_level), settings.showLevel, { v -> onChange { it.copy(showLevel = v) } }, stringResource(R.string.camera_level_hint))
                if (caps?.facing == LensFacing.FRONT) {
                    SwitchRow(
                        stringResource(R.string.camera_mirror_front),
                        settings.mirrorFrontRecording,
                        { v -> onChange { it.copy(mirrorFrontRecording = v) } },
                        stringResource(R.string.camera_mirror_front_hint),
                        enabled = !locked,
                    )
                }
                if (caps != null && caps.stabilizationModes.size > 1) {
                    Text(stringResource(R.string.camera_pro_stabilization), style = MaterialTheme.typography.titleSmall, color = Color.White, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        caps.stabilizationModes.forEach { m -> RgChip(m.label(), settings.stabilization == m, { if (!locked) onChange { it.copy(stabilization = m) } }, glass = true) }
                    }
                }
                if (caps != null && caps.hdrOptions.isNotEmpty()) {
                    SwitchRow(stringResource(R.string.camera_pro_hdr), settings.hdr, { v -> onChange { it.copy(hdr = v) } }, stringResource(R.string.camera_pro_hdr_hint), enabled = !locked)
                } else if (caps?.tenBitHdrOnDevice == true) {
                    Text(stringResource(R.string.camera_hdr10_unsupported), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(vertical = 6.dp))
                }
            }
        }
    }
}

private fun AudioInputType.icon() = when (this) {
    AudioInputType.BLUETOOTH, AudioInputType.BLUETOOTH_LE -> Icons.Rounded.Bluetooth
    AudioInputType.USB -> Icons.Rounded.Usb
    AudioInputType.WIRED -> Icons.Rounded.Headset
    else -> Icons.Rounded.Mic
}

@Composable
private fun AudioInputType.label(): String = stringResource(
    when (this) {
        AudioInputType.BUILT_IN -> R.string.camera_input_builtin
        AudioInputType.WIRED -> R.string.camera_input_wired
        AudioInputType.USB -> R.string.camera_input_usb
        AudioInputType.BLUETOOTH, AudioInputType.BLUETOOTH_LE -> R.string.camera_input_bluetooth
        AudioInputType.HDMI -> R.string.camera_input_hdmi
        AudioInputType.OTHER -> R.string.camera_input_other
    },
)

/** Audio panel: capture mode, input, gain, live meter, DSP and monitoring. */
@Composable
internal fun AudioSheet(
    captureMode: CaptureMode,
    settings: AudioSettings,
    inputs: List<AudioInputDevice>,
    activeInput: AudioInputDevice?,
    monitoringAvailable: Boolean,
    micGranted: Boolean,
    entitlements: Entitlements,
    locked: Boolean,
    level: State<AudioLevel>,
    clipping: State<Boolean>,
    onCaptureMode: (CaptureMode) -> Unit,
    onChange: ((AudioSettings) -> AudioSettings) -> Unit,
    onRequestMic: () -> Unit,
    onRequirePro: (ProFeature) -> Unit,
    onDismiss: () -> Unit,
) {
    RgBottomSheet(onDismiss = onDismiss, title = stringResource(R.string.camera_audio_title), dark = true) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SheetSection(stringResource(R.string.camera_capture_mode)) {
                RgSegmentedControl(
                    options = CaptureMode.entries,
                    selected = captureMode,
                    onSelect = { if (!locked) onCaptureMode(it) },
                    label = { it.label() },
                    glass = true,
                )
            }
            if (!micGranted) {
                Column(Modifier.padding(horizontal = Spacing.gutter)) {
                    RgListItem(
                        title = stringResource(R.string.camera_mic_off_title),
                        subtitle = stringResource(R.string.camera_mic_off_message),
                        icon = Icons.Rounded.Mic,
                        onClick = onRequestMic,
                    )
                }
                return@Column
            }
            SheetSection(stringResource(R.string.camera_level_meter)) {
                LevelMeterBar(level, clipping, height = 14.dp)
                if (clipping.value) {
                    Text(stringResource(R.string.camera_clipping), style = MaterialTheme.typography.labelMedium, color = Palette.Record, modifier = Modifier.padding(top = 4.dp))
                }
            }
            if (inputs.isNotEmpty()) {
                SheetSection(stringResource(R.string.camera_input)) {
                    Column {
                        inputs.forEach { device ->
                            val active = activeInput?.id == device.id
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (active) Color.White.copy(alpha = 0.1f) else Color.Transparent)
                                    .pressable(enabled = !locked) { onChange { it.copy(preferredInput = device.type, preferredDeviceName = device.name) } }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(device.type.icon(), null, tint = Color.White)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(device.name.ifBlank { device.type.label() }, style = MaterialTheme.typography.titleSmall, color = Color.White, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                                    Text(device.type.label(), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
                                }
                                if (active) Icon(Icons.Rounded.CheckCircle, stringResource(R.string.camera_input_active), tint = RgTheme.colors.accent)
                            }
                        }
                    }
                }
            }
            Column(Modifier.padding(horizontal = Spacing.gutter)) {
                RgLabeledSlider(
                    label = stringResource(R.string.camera_gain),
                    value = settings.gainDb,
                    onValueChange = { v -> onChange { it.copy(gainDb = (v * 2).toInt() / 2f) } },
                    valueRange = -12f..24f,
                    valueText = String.format(Locale.US, "%+.1f dB", settings.gainDb).localizeDigits(),
                    bipolar = true,
                )
                val nrEntitled = entitlements.has(ProFeature.AUDIO_NOISE_REDUCTION)
                SwitchRow(
                    stringResource(R.string.camera_noise_reduction),
                    settings.noiseReduction && nrEntitled,
                    { v -> if (!nrEntitled) onRequirePro(ProFeature.AUDIO_NOISE_REDUCTION) else onChange { it.copy(noiseReduction = v) } },
                    stringResource(R.string.camera_noise_reduction_hint),
                    pro = !nrEntitled,
                )
                if (settings.noiseReduction && nrEntitled) {
                    RgLabeledSlider(
                        label = stringResource(R.string.camera_noise_strength),
                        value = settings.noiseReductionStrength * 100f,
                        onValueChange = { v -> onChange { it.copy(noiseReductionStrength = v / 100f) } },
                    )
                }
                SwitchRow(stringResource(R.string.camera_voice_enhance), settings.voiceEnhancement, { v -> onChange { it.copy(voiceEnhancement = v) } }, stringResource(R.string.camera_voice_enhance_hint))
                SwitchRow(stringResource(R.string.camera_high_pass), settings.highPassFilter, { v -> onChange { it.copy(highPassFilter = v) } }, stringResource(R.string.camera_high_pass_hint))
                SwitchRow(stringResource(R.string.camera_limiter), settings.limiter, { v -> onChange { it.copy(limiter = v) } }, stringResource(R.string.camera_limiter_hint))
                SwitchRow(
                    stringResource(R.string.camera_monitoring),
                    settings.monitoring && monitoringAvailable,
                    { v -> onChange { it.copy(monitoring = v) } },
                    stringResource(if (monitoringAvailable) R.string.camera_monitoring_hint else R.string.camera_monitoring_unavailable),
                    enabled = monitoringAvailable,
                )
            }
        }
    }
}
