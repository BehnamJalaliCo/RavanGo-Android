package com.ravango.feature.camera.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ravango.core.common.format.localizeDigits
import com.ravango.core.designsystem.component.GlassSurface
import com.ravango.core.designsystem.component.ProBadge
import com.ravango.core.designsystem.component.RgChip
import com.ravango.core.designsystem.component.RgLabeledSlider
import com.ravango.core.designsystem.component.RgSecondaryButton
import com.ravango.core.designsystem.component.RgButtonSize
import com.ravango.core.designsystem.component.RgSwitch
import com.ravango.core.designsystem.component.RgTextButton
import com.ravango.core.model.CameraSettings
import com.ravango.core.model.Entitlements
import com.ravango.core.model.ManualControls
import com.ravango.core.model.ProFeature
import com.ravango.core.model.StabilizationMode
import com.ravango.core.model.WhiteBalanceMode
import com.ravango.engine.camera.LiveExposure
import com.ravango.engine.camera.capability.CameraCapabilities
import com.ravango.feature.camera.R
import com.ravango.engine.camera.session.WhiteBalanceMath
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

internal enum class ProTab { EV, ISO, SHUTTER, FOCUS, WB, STABILIZATION, HDR }

/** Tabs to show for a camera: only what the device supports. */
internal fun availableProTabs(caps: CameraCapabilities): List<ProTab> = buildList {
    if (caps.exposureCompensation) add(ProTab.EV)
    if (caps.manualExposure) { add(ProTab.ISO); add(ProTab.SHUTTER) }
    if (caps.manualFocus) add(ProTab.FOCUS)
    if (caps.whiteBalanceModes.size > 1) add(ProTab.WB)
    if (caps.stabilizationModes.size > 1) add(ProTab.STABILIZATION)
    if (caps.hdrOptions.isNotEmpty()) add(ProTab.HDR)
}

private fun ProTab.gated(): Boolean = this == ProTab.ISO || this == ProTab.SHUTTER || this == ProTab.FOCUS

/** Pro controls drawer: EV, ISO, shutter, focus, white balance, stabilization, HDR — each only if supported. */
@Composable
internal fun ProControlsPanel(
    caps: CameraCapabilities,
    settings: CameraSettings,
    controls: ManualControls,
    live: LiveExposure,
    entitlements: Entitlements,
    locked: Boolean,
    onExposure: (Int) -> Unit,
    onIso: (Int?) -> Unit,
    onShutter: (Long?) -> Unit,
    onFocus: (Float?) -> Unit,
    onWhiteBalance: (WhiteBalanceMode, Int) -> Unit,
    onStabilization: (StabilizationMode) -> Unit,
    onHdr: (Boolean) -> Unit,
    onReset: () -> Unit,
    onRequirePro: (ProFeature) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = availableProTabs(caps)
    if (tabs.isEmpty()) {
        GlassSurface(modifier.fillMaxWidth()) {
            Text(stringResource(R.string.camera_pro_none), style = MaterialTheme.typography.bodyMedium, color = Color.White)
        }
        return
    }
    var tabName by rememberSaveable { mutableStateOf(tabs.first().name) }
    val tab = tabs.firstOrNull { it.name == tabName } ?: tabs.first()
    val pro = entitlements.has(ProFeature.MANUAL_CAMERA_PRO)

    GlassSurface(modifier.fillMaxWidth(), tint = Color(0xCC141220)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                tabs.forEach { t ->
                    RgChip(
                        text = t.title(),
                        selected = t == tab,
                        onClick = { tabName = t.name },
                        glass = true,
                        trailing = if (t.gated() && !pro) ({ ProBadge() }) else null,
                    )
                }
                RgTextButton(stringResource(R.string.camera_pro_reset), onReset, color = Color.White.copy(alpha = 0.8f))
            }
            AnimatedContent(tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "proTab") { t ->
                if (t.gated() && !pro) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.camera_pro_manual_locked), style = MaterialTheme.typography.bodyMedium, color = Color.White, modifier = Modifier.weight(1f).padding(end = 8.dp))
                        RgSecondaryButton(stringResource(R.string.camera_unlock_pro), { onRequirePro(ProFeature.MANUAL_CAMERA_PRO) }, size = RgButtonSize.SMALL)
                    }
                } else {
                    ProTabContent(t, caps, settings, controls, live, pro, locked, onExposure, onIso, onShutter, onFocus, onWhiteBalance, onStabilization, onHdr, onRequirePro)
                }
            }
        }
    }
}

@Composable
private fun ProTab.title(): String = stringResource(
    when (this) {
        ProTab.EV -> R.string.camera_pro_ev
        ProTab.ISO -> R.string.camera_pro_iso
        ProTab.SHUTTER -> R.string.camera_pro_shutter
        ProTab.FOCUS -> R.string.camera_pro_focus
        ProTab.WB -> R.string.camera_pro_wb
        ProTab.STABILIZATION -> R.string.camera_pro_stabilization
        ProTab.HDR -> R.string.camera_pro_hdr
    },
)

@Composable
private fun ProTabContent(
    tab: ProTab,
    caps: CameraCapabilities,
    settings: CameraSettings,
    controls: ManualControls,
    live: LiveExposure,
    pro: Boolean,
    locked: Boolean,
    onExposure: (Int) -> Unit,
    onIso: (Int?) -> Unit,
    onShutter: (Long?) -> Unit,
    onFocus: (Float?) -> Unit,
    onWhiteBalance: (WhiteBalanceMode, Int) -> Unit,
    onStabilization: (StabilizationMode) -> Unit,
    onHdr: (Boolean) -> Unit,
    onRequirePro: (ProFeature) -> Unit,
) {
    val auto = stringResource(R.string.camera_auto)
    when (tab) {
        ProTab.EV -> {
            val range = caps.exposureCompensationRange
            val manual = controls.iso != null || controls.shutterNs != null
            RgLabeledSlider(
                label = stringResource(R.string.camera_pro_ev),
                value = controls.exposureCompensation.toFloat(),
                onValueChange = { onExposure(it.roundToInt()) },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                valueText = evLabel(controls.exposureCompensation, caps.exposureCompensationStep),
                bipolar = true,
                steps = (range.last - range.first - 1).coerceAtLeast(0),
                enabled = !manual,
            )
            if (manual) Text(stringResource(R.string.camera_pro_ev_manual_hint), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
        }
        ProTab.ISO -> {
            val range = caps.isoRange ?: return
            val current = controls.iso
            AutoRow(isAuto = current == null, autoText = live.iso?.let { "$auto · ${isoLabel(it)}" } ?: auto, onAuto = { onIso(null) }) {
                val value = isoToFraction(current ?: live.iso ?: range.first, range)
                RgLabeledSlider(
                    label = stringResource(R.string.camera_pro_iso),
                    value = value,
                    onValueChange = { onIso(fractionToIso(it, range)) },
                    valueRange = 0f..1f,
                    valueText = current?.let { isoLabel(it) } ?: auto,
                )
            }
        }
        ProTab.SHUTTER -> {
            val range = caps.exposureTimeRangeNs ?: return
            val maxForFps = 1_000_000_000L / settings.frameRate.coerceAtLeast(1)
            val stops = SHUTTER_STOPS_NS.filter { it in range && it <= maxForFps }.ifEmpty { listOf(maxForFps.coerceIn(range)) }
            val current = controls.shutterNs
            val index = current?.let { c -> stops.indices.minByOrNull { kotlin.math.abs(stops[it] - c) } } ?: 0
            AutoRow(isAuto = current == null, autoText = live.exposureTimeNs?.let { "$auto · ${shutterLabel(it)}" } ?: auto, onAuto = { onShutter(null) }) {
                RgLabeledSlider(
                    label = stringResource(R.string.camera_pro_shutter),
                    value = index.toFloat(),
                    onValueChange = { onShutter(stops[it.roundToInt().coerceIn(stops.indices)]) },
                    valueRange = 0f..(stops.size - 1).coerceAtLeast(1).toFloat(),
                    steps = (stops.size - 2).coerceAtLeast(0),
                    valueText = current?.let { shutterLabel(it) } ?: auto,
                )
            }
        }
        ProTab.FOCUS -> {
            val current = controls.focusDistanceDiopters
            AutoRow(isAuto = current == null, autoText = auto, onAuto = { onFocus(null) }) {
                RgLabeledSlider(
                    label = stringResource(R.string.camera_pro_focus),
                    value = current ?: 0f,
                    onValueChange = { onFocus(it) },
                    valueRange = 0f..caps.minFocusDistanceDiopters,
                    valueText = current?.let { focusLabel(it) } ?: auto,
                )
            }
        }
        ProTab.WB -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    caps.whiteBalanceModes.forEach { mode ->
                        val gated = mode == WhiteBalanceMode.MANUAL_KELVIN && !pro
                        RgChip(
                            text = mode.label(),
                            selected = controls.whiteBalance == mode,
                            onClick = { if (gated) onRequirePro(ProFeature.MANUAL_CAMERA_PRO) else onWhiteBalance(mode, controls.kelvin) },
                            glass = true,
                            trailing = if (gated) ({ ProBadge() }) else null,
                        )
                    }
                }
                if (controls.whiteBalance == WhiteBalanceMode.MANUAL_KELVIN && caps.manualKelvin && pro) {
                    RgLabeledSlider(
                        label = stringResource(R.string.camera_wb_kelvin),
                        value = controls.kelvin.toFloat(),
                        onValueChange = { onWhiteBalance(WhiteBalanceMode.MANUAL_KELVIN, (it / 100f).roundToInt() * 100) },
                        valueRange = WhiteBalanceMath.MIN_KELVIN.toFloat()..WhiteBalanceMath.MAX_KELVIN.toFloat(),
                        valueText = kelvinLabel(controls.kelvin),
                    )
                }
            }
        }
        ProTab.STABILIZATION -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            caps.stabilizationModes.forEach { mode ->
                RgChip(mode.label(), settings.stabilization == mode, { if (!locked) onStabilization(mode) }, glass = true)
            }
        }
        ProTab.HDR -> Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.camera_pro_hdr), style = MaterialTheme.typography.titleSmall, color = Color.White)
                Text(stringResource(R.string.camera_pro_hdr_hint), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
            }
            RgSwitch(settings.hdr, onHdr, enabled = !locked)
        }
    }
}

@Composable
private fun AutoRow(isAuto: Boolean, autoText: String, onAuto: () -> Unit, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RgChip(autoText, isAuto, onAuto, glass = true)
        content()
    }
}

private fun isoToFraction(iso: Int, range: IntRange): Float {
    val min = range.first.coerceAtLeast(1).toDouble()
    val max = range.last.toDouble()
    if (max <= min) return 0f
    return (ln(iso.coerceIn(range).toDouble() / min) / ln(max / min)).toFloat()
}

private fun fractionToIso(f: Float, range: IntRange): Int {
    val min = range.first.coerceAtLeast(1).toDouble()
    val max = range.last.toDouble()
    val raw = min * (max / min).pow(f.toDouble())
    val step = if (raw < 200) 10 else 50
    return ((raw / step).roundToInt() * step).coerceIn(range)
}

@Composable
private fun focusLabel(diopters: Float): String {
    if (diopters <= 0.01f) return "∞"
    val cm = (100f / diopters).roundToInt()
    return if (cm >= 100) String.format(Locale.US, "%.1f m", cm / 100f).localizeDigits() else stringResource(R.string.camera_focus_cm, cm.toString().localizeDigits())
}
