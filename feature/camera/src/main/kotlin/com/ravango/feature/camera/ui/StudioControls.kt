package com.ravango.feature.camera.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.FlipCameraAndroid
import androidx.compose.material.icons.rounded.Grid3x3
import androidx.compose.material.icons.rounded.GridOff
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.automirrored.rounded.Subject
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TimerOff
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ravango.core.common.format.formatBytes
import com.ravango.core.common.format.formatDuration
import com.ravango.core.common.format.localizeDigits
import com.ravango.core.designsystem.component.RgIconButton
import com.ravango.core.designsystem.component.pressable
import com.ravango.core.designsystem.theme.HapticEvent
import com.ravango.core.designsystem.theme.Motion
import com.ravango.core.designsystem.theme.Palette
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.rememberHaptics
import com.ravango.core.model.FlashMode
import com.ravango.core.model.GridType
import com.ravango.engine.camera.RecordingPhase
import com.ravango.engine.camera.RecordingStatus
import com.ravango.engine.camera.capability.LensOption
import com.ravango.feature.camera.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Glass pill used for text controls in the top bar. */
@Composable
internal fun GlassPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, highlighted: Boolean = false) {
    Box(
        modifier
            .height(36.dp)
            .clip(RoundedCornerShape(50))
            .background(if (highlighted) RgTheme.colors.accent else Color.Black.copy(alpha = 0.34f))
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(50))
            .pressable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) Color.White else Color.White.copy(alpha = 0.45f), maxLines = 1)
    }
}

@Composable
internal fun StudioTopBar(
    videoMode: String?,
    aspect: String?,
    flash: FlashMode,
    flashEnabled: Boolean,
    timerSeconds: Int,
    grid: GridType,
    iconRotation: Float,
    locked: Boolean,
    onClose: () -> Unit,
    onResolution: () -> Unit,
    onFlash: () -> Unit,
    onTimer: () -> Unit,
    onGrid: () -> Unit,
    onAspect: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rotate = Modifier.graphicsLayer { rotationZ = iconRotation }
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.22f))
            .padding(4.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RgIconButton(Icons.Rounded.Close, stringResource(R.string.camera_close), onClose, rotate, size = 36.dp, iconSize = 20.dp, glass = true, enabled = !locked)
        if (videoMode != null) GlassPill(videoMode, onResolution, rotate, enabled = !locked)
        if (flashEnabled) {
            RgIconButton(
                icon = when (flash) {
                    FlashMode.OFF -> Icons.Rounded.FlashOff
                    FlashMode.TORCH -> Icons.Rounded.FlashOn
                    FlashMode.SCREEN -> Icons.Rounded.LightMode
                },
                contentDescription = stringResource(R.string.camera_flash) + ": " + flash.label(),
                onClick = onFlash,
                modifier = rotate,
                size = 36.dp,
                iconSize = 20.dp,
                glass = true,
                selected = flash != FlashMode.OFF,
            )
        }
        Box(rotate) {
            RgIconButton(
                if (timerSeconds == 0) Icons.Rounded.TimerOff else Icons.Rounded.Timer,
                stringResource(R.string.camera_timer),
                onTimer,
                size = 36.dp,
                iconSize = 20.dp,
                glass = true,
                selected = timerSeconds > 0,
                enabled = !locked,
            )
            if (timerSeconds > 0) {
                Text(
                    timerSeconds.toString().localizeDigits(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).clip(CircleShape).background(Palette.Record).padding(horizontal = 4.dp),
                )
            }
        }
        RgIconButton(
            if (grid == GridType.NONE) Icons.Rounded.GridOff else Icons.Rounded.Grid3x3,
            stringResource(R.string.camera_grid) + ": " + grid.label(),
            onGrid,
            rotate,
            size = 36.dp,
            iconSize = 20.dp,
            glass = true,
            selected = grid != GridType.NONE,
        )
        if (aspect != null) GlassPill(aspect, onAspect, rotate, enabled = !locked)
        RgIconButton(Icons.Rounded.Settings, stringResource(R.string.camera_settings), onSettings, rotate, size = 36.dp, iconSize = 20.dp, glass = true, enabled = !locked)
    }
}

/** Vertical tool rail at the end edge. */
@Composable
internal fun StudioToolRail(
    iconRotation: Float,
    videoMode: Boolean,
    micOn: Boolean,
    prompterActive: Boolean,
    proOpen: Boolean,
    showPro: Boolean,
    onBeauty: () -> Unit,
    onPro: () -> Unit,
    onAudio: () -> Unit,
    onPrompter: () -> Unit,
    onCompare: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rotate = Modifier.graphicsLayer { rotationZ = iconRotation }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (videoMode) {
            RgIconButton(Icons.Rounded.Face, stringResource(R.string.camera_beauty), onBeauty, rotate, glass = true)
            CompareButton(onCompare, rotate)
            if (showPro) RgIconButton(Icons.Rounded.Tune, stringResource(R.string.camera_pro_controls), onPro, rotate, glass = true, selected = proOpen)
        }
        RgIconButton(if (micOn) Icons.Rounded.Mic else Icons.Rounded.MicOff, stringResource(R.string.camera_audio), onAudio, rotate, glass = true)
        RgIconButton(Icons.AutoMirrored.Rounded.Subject, stringResource(R.string.camera_prompter), onPrompter, rotate, glass = true, selected = prompterActive)
    }
}

/** Hold to see the image without beauty effects. */
@Composable
private fun CompareButton(onCompare: (Boolean) -> Unit, modifier: Modifier) {
    val haptics = rememberHaptics()
    var pressed by remember { mutableStateOf(false) }
    val label = stringResource(R.string.camera_compare)
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (pressed) RgTheme.colors.accent else Color.Black.copy(alpha = 0.32f))
            .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
            .semantics { contentDescription = label; role = Role.Button }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressed = true
                    haptics.perform(HapticEvent.TICK)
                    onCompare(true)
                    tryAwaitRelease()
                    onCompare(false)
                    pressed = false
                })
            },
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Rounded.Compare, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
}

/** The big shutter: circle → rounded square while recording, with a pulse ring. */
@Composable
internal fun RecordButton(
    recording: Boolean,
    busy: Boolean,
    countingDown: Boolean,
    audioMode: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val reduceMotion = RgTheme.reduceMotion
    val innerSize by animateDpAsState(if (recording) 30.dp else 62.dp, Motion.bouncy(), label = "inner")
    val corner by animateDpAsState(if (recording) 9.dp else 31.dp, Motion.bouncy(), label = "corner")
    val color by animateColorAsState(
        when {
            countingDown -> Palette.Butter400
            audioMode && !recording -> Palette.Lavender400
            else -> Palette.Record
        },
        Motion.quick(), label = "color",
    )
    val pulse = rememberInfiniteTransition(label = "pulse")
    val pulseScale by pulse.animateFloat(1f, 1.28f, infiniteRepeatable(tween(1_100), RepeatMode.Restart), label = "scale")
    val pulseAlpha by pulse.animateFloat(0.5f, 0f, infiniteRepeatable(tween(1_100), RepeatMode.Restart), label = "alpha")
    val description = stringResource(if (recording) R.string.camera_stop_recording else R.string.camera_start_recording)
    Box(modifier.size(88.dp), contentAlignment = Alignment.Center) {
        if (recording && !reduceMotion) {
            Box(
                Modifier
                    .size(80.dp)
                    .graphicsLayer { scaleX = pulseScale; scaleY = pulseScale; alpha = pulseAlpha }
                    .border(3.dp, Palette.Record, CircleShape),
            )
        }
        Box(
            Modifier
                .size(80.dp)
                .border(4.dp, Color.White, CircleShape)
                .clip(CircleShape)
                .semantics { contentDescription = description; role = Role.Button }
                .pressable(enabled = !busy, haptic = null) {
                    haptics.perform(if (recording) HapticEvent.RECORD_STOP else HapticEvent.RECORD_START)
                    onClick()
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(innerSize).clip(RoundedCornerShape(corner)).background(color))
            if (audioMode && !recording) Icon(Icons.Rounded.Mic, null, tint = Color.White, modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
internal fun LensChips(lenses: List<LensOption>, zoom: Float, currentCameraId: String?, iconRotation: Float, onSelect: (LensOption) -> Unit, modifier: Modifier = Modifier) {
    if (lenses.size <= 1 && abs(zoom - 1f) < 0.05f) return
    val active = lenses.filter { it.cameraId == currentCameraId }
        .minByOrNull { abs(it.zoomRatio - zoom) }
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.3f)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        lenses.forEach { lens ->
            val selected = lens == active
            val text = if (selected && abs(zoom - lens.zoomRatio) > 0.05f) zoomLabel(zoom * lens.factor / lens.zoomRatio) else lens.label.localizeDigits()
            Box(
                Modifier
                    .size(if (selected) 42.dp else 36.dp)
                    .graphicsLayer { rotationZ = iconRotation }
                    .clip(CircleShape)
                    .background(if (selected) Color.White.copy(alpha = 0.22f) else Color.Transparent)
                    .pressable(haptic = HapticEvent.SNAP) { onSelect(lens) },
                contentAlignment = Alignment.Center,
            ) {
                Text(text, style = MaterialTheme.typography.labelMedium, color = if (selected) Palette.Butter400 else Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Last recording thumbnail (decoded off the main thread). */
@Composable
internal fun LastTakeThumbnail(path: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = path?.let {
            withContext(Dispatchers.IO) {
                runCatching {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    BitmapFactory.decodeFile(it, opts)?.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    Box(
        modifier
            .size(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.14f))
            .border(1.5.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
            .pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(image, stringResource(R.string.camera_last_take), contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp))
        } else {
            Icon(Icons.Rounded.VideoLibrary, stringResource(R.string.camera_last_take), tint = Color.White)
        }
    }
}

@Composable
internal fun FlipButton(enabled: Boolean, iconRotation: Float, onClick: () -> Unit) {
    RgIconButton(
        Icons.Rounded.FlipCameraAndroid,
        stringResource(R.string.camera_flip),
        onClick,
        Modifier.graphicsLayer { rotationZ = iconRotation },
        size = 48.dp,
        glass = true,
        enabled = enabled,
    )
}

@Composable
internal fun PauseButton(paused: Boolean, iconRotation: Float, onClick: () -> Unit) {
    RgIconButton(
        if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
        stringResource(if (paused) R.string.camera_resume else R.string.camera_pause),
        onClick,
        Modifier.graphicsLayer { rotationZ = iconRotation },
        size = 48.dp,
        glass = true,
    )
}

/** Recording HUD: blinking dot + time, size and remaining storage time. */
@Composable
internal fun RecordingHud(status: RecordingStatus, iconRotation: Float, modifier: Modifier = Modifier) {
    val blink = rememberInfiniteTransition(label = "blink")
    val dotAlpha by blink.animateFloat(1f, 0.2f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dot")
    val paused = status.phase == RecordingPhase.PAUSED
    Row(
        modifier
            .graphicsLayer { rotationZ = iconRotation }
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(9.dp)
                .graphicsLayer { alpha = if (paused) 1f else dotAlpha }
                .clip(CircleShape)
                .background(if (paused) Palette.Butter400 else Palette.Record),
        )
        Spacer(Modifier.width(8.dp))
        Text(formatDuration(status.durationUs), style = MaterialTheme.typography.titleSmall, color = Color.White, fontWeight = FontWeight.SemiBold)
        if (status.bytesWritten > 0) {
            Spacer(Modifier.width(10.dp))
            Text(formatBytes(status.bytesWritten), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f))
        }
        if (status.remainingSeconds in 0 until 24 * 3600) {
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.camera_remaining, formatDuration(status.remainingSeconds * 1_000_000)),
                style = MaterialTheme.typography.labelMedium,
                color = if (status.remainingSeconds < 120) Palette.Butter400 else Color.White.copy(alpha = 0.8f),
            )
        }
    }
}
