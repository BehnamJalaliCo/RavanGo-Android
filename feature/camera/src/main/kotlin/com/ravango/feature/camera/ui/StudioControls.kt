package com.ravango.feature.camera.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Subject
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.EmojiEmotions
import androidx.compose.material.icons.rounded.FaceRetouchingNatural
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
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TimerOff
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ravango.core.common.format.formatBytes
import com.ravango.core.common.format.formatDuration
import com.ravango.core.common.format.localizeDigits
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Glass tokens for controls floating over the live preview (contrast stays ≥ 4.5:1 for white text on any image). */
internal object StudioGlass {
    val Fill = Color.Black.copy(alpha = 0.36f)
    val FillStrong = Color.Black.copy(alpha = 0.5f)
    val Stroke = Color.White.copy(alpha = 0.14f)
    val Muted = Color.White.copy(alpha = 0.72f)
    /** Touch target of every studio control. */
    val Target: Dp = 44.dp
    val Icon: Dp = 22.dp
}

/** Text drawn directly over the preview gets a soft dark shadow so it stays readable on bright scenes. */
internal fun TextStyle.overPreview(): TextStyle = copy(shadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 2f), 8f))

/** A round glass icon button (44dp touch target) that can rotate with the device. */
@Composable
internal fun GlassIcon(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    rotation: Float = 0f,
    selected: Boolean = false,
    enabled: Boolean = true,
    size: Dp = StudioGlass.Target,
    fill: Color = StudioGlass.Fill,
    tint: Color = Color.White,
    border: Boolean = true,
) {
    val accent = RgTheme.colors.accent
    val bg by animateColorAsState(if (selected) accent else fill, Motion.quick(), label = "glassIcon")
    Box(
        modifier
            .size(size)
            .graphicsLayer { rotationZ = rotation; alpha = if (enabled) 1f else 0.4f }
            .clip(CircleShape)
            .background(bg)
            .then(if (border) Modifier.border(1.dp, StudioGlass.Stroke, CircleShape) else Modifier)
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription }
            .pressable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(StudioGlass.Icon))
    }
}

/** Glass pill used for text controls in the top bar (same 44dp height as the icon buttons). */
@Composable
internal fun GlassPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, rotation: Float = 0f) {
    Box(
        modifier
            .height(StudioGlass.Target)
            .graphicsLayer { rotationZ = rotation }
            .clip(RoundedCornerShape(50))
            .pressable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.45f),
            maxLines = 1,
        )
    }
}

/** A glass capsule grouping related controls. */
@Composable
internal fun GlassCapsule(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .height(StudioGlass.Target)
            .clip(RoundedCornerShape(50))
            .background(StudioGlass.Fill)
            .border(1.dp, StudioGlass.Stroke, RoundedCornerShape(50)),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * Balanced top bar: close at the start, settings at the end, and the capture format (resolution · fps, aspect)
 * with flash and timer grouped in one centred glass capsule. Mirrors naturally in RTL.
 */
@Composable
internal fun StudioTopBar(
    videoMode: String?,
    aspect: String?,
    flash: FlashMode,
    flashEnabled: Boolean,
    timerSeconds: Int,
    iconRotation: Float,
    locked: Boolean,
    onClose: () -> Unit,
    onResolution: () -> Unit,
    onFlash: () -> Unit,
    onTimer: () -> Unit,
    onAspect: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        GlassIcon(Icons.Rounded.Close, stringResource(R.string.camera_close), onClose, rotation = iconRotation, enabled = !locked)
        Spacer(Modifier.weight(1f))
        GlassCapsule {
            if (videoMode != null) GlassPill(videoMode, onResolution, enabled = !locked, rotation = iconRotation)
            if (flashEnabled) {
                GlassIcon(
                    icon = when (flash) {
                        FlashMode.OFF -> Icons.Rounded.FlashOff
                        FlashMode.TORCH -> Icons.Rounded.FlashOn
                        FlashMode.SCREEN -> Icons.Rounded.LightMode
                    },
                    contentDescription = stringResource(R.string.camera_flash) + ": " + flash.label(),
                    onClick = onFlash,
                    rotation = iconRotation,
                    fill = Color.Transparent,
                    tint = if (flash != FlashMode.OFF) Palette.Butter400 else Color.White,
                    border = false,
                )
            }
            Box {
                GlassIcon(
                    if (timerSeconds == 0) Icons.Rounded.TimerOff else Icons.Rounded.Timer,
                    stringResource(R.string.camera_timer),
                    onTimer,
                    rotation = iconRotation,
                    enabled = !locked,
                    fill = Color.Transparent,
                    tint = if (timerSeconds > 0) Palette.Butter400 else Color.White,
                    border = false,
                )
                if (timerSeconds > 0) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 4.dp, end = 2.dp)
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(Palette.Butter400),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            timerSeconds.toString().localizeDigits(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                        )
                    }
                }
            }
            if (aspect != null) GlassPill(aspect, onAspect, enabled = !locked, rotation = iconRotation)
        }
        Spacer(Modifier.weight(1f))
        GlassIcon(Icons.Rounded.Settings, stringResource(R.string.camera_settings), onSettings, rotation = iconRotation, enabled = !locked)
    }
}

/** Vertical tool rail at the end edge: one glass column, 44dp targets on an 8dp rhythm. */
@Composable
internal fun StudioToolRail(
    iconRotation: Float,
    videoMode: Boolean,
    micOn: Boolean,
    prompterActive: Boolean,
    proOpen: Boolean,
    showPro: Boolean,
    grid: GridType,
    beautyActive: Boolean,
    onBeauty: () -> Unit,
    onPro: () -> Unit,
    onGrid: () -> Unit,
    onAudio: () -> Unit,
    onPrompter: () -> Unit,
    onCompare: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(StudioGlass.Fill)
            .border(1.dp, StudioGlass.Stroke, RoundedCornerShape(28.dp))
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (videoMode) {
            RailIcon(Icons.Rounded.FaceRetouchingNatural, stringResource(R.string.camera_beauty), onBeauty, iconRotation, dot = beautyActive)
            CompareButton(onCompare, iconRotation)
            if (showPro) RailIcon(Icons.Rounded.Tune, stringResource(R.string.camera_pro_controls), onPro, iconRotation, selected = proOpen)
            RailIcon(if (grid == GridType.NONE) Icons.Rounded.GridOff else Icons.Rounded.Grid3x3, stringResource(R.string.camera_grid) + ": " + grid.label(), onGrid, iconRotation, selected = grid != GridType.NONE)
        }
        RailIcon(if (micOn) Icons.Rounded.Mic else Icons.Rounded.MicOff, stringResource(R.string.camera_audio), onAudio, iconRotation)
        RailIcon(Icons.AutoMirrored.Rounded.Subject, stringResource(R.string.camera_prompter), onPrompter, iconRotation, selected = prompterActive)
    }
}

@Composable
private fun RailIcon(icon: ImageVector, description: String, onClick: () -> Unit, rotation: Float, selected: Boolean = false, dot: Boolean = false) {
    Box {
        GlassIcon(icon, description, onClick, rotation = rotation, selected = selected, fill = Color.Transparent, border = false)
        if (dot) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 8.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(RgTheme.colors.pastelMint),
            )
        }
    }
}

/** Hold to see the image without effects (the recording keeps them). */
@Composable
private fun CompareButton(onCompare: (Boolean) -> Unit, rotation: Float) {
    val haptics = rememberHaptics()
    var pressed by remember { mutableStateOf(false) }
    val current by rememberUpdatedState(onCompare)
    val label = stringResource(R.string.camera_compare)
    val bg by animateColorAsState(if (pressed) RgTheme.colors.accent else Color.Transparent, Motion.quick(), label = "compare")
    Box(
        Modifier
            .size(StudioGlass.Target)
            .graphicsLayer { rotationZ = rotation }
            .clip(CircleShape)
            .background(bg)
            .semantics { contentDescription = label; role = Role.Button }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressed = true
                    haptics.perform(HapticEvent.TICK)
                    current(true)
                    try {
                        tryAwaitRelease()
                    } finally {
                        current(false)
                        pressed = false
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Rounded.Compare, null, tint = Color.White, modifier = Modifier.size(StudioGlass.Icon)) }
}

/** Visual mode of the shutter. */
internal enum class ShutterStyle { VIDEO, AUDIO, LENS }

/**
 * The big shutter (84dp). Idle: white ring around a red disc. Tap: the disc morphs to a rounded square *immediately*
 * (optimistic "arming" state) while the recorder starts; recording adds a sweeping arc on the ring and a soft pulse.
 * In lens mode ([ShutterStyle.LENS]) only the ring is drawn, framing the lens preview underneath.
 */
@Composable
internal fun RecordButton(
    recording: Boolean,
    busy: Boolean,
    countingDown: Boolean,
    style: ShutterStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val reduceMotion = RgTheme.reduceMotion
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(recording, armed) {
        if (recording) armed = false
        if (armed) { delay(1_500); armed = false }
    }
    val active = recording || armed
    val lens = style == ShutterStyle.LENS && !active
    val innerSize by animateDpAsState(
        when {
            active -> 30.dp
            lens -> 0.dp
            else -> 66.dp
        },
        Motion.bouncy(), label = "inner",
    )
    val corner by animateDpAsState(if (active) 9.dp else 33.dp, Motion.bouncy(), label = "corner")
    val color by animateColorAsState(
        when {
            countingDown -> Palette.Butter400
            style == ShutterStyle.AUDIO && !active -> Palette.Lavender400
            else -> Palette.Record
        },
        Motion.quick(), label = "color",
    )
    val ringWidth by animateDpAsState(if (lens) 5.dp else 4.dp, Motion.quick(), label = "ring")
    // The looping ring animation exists only while recording (an idle shutter keeps Compose idle).
    val animateRing = recording && !reduceMotion
    val sweepState = if (animateRing) {
        rememberInfiniteTransition(label = "rec").animateFloat(0f, 360f, infiniteRepeatable(tween(1_600, easing = LinearEasing)), label = "sweep")
    } else null
    val pulseState = if (animateRing) {
        rememberInfiniteTransition(label = "pulse").animateFloat(0f, 1f, infiniteRepeatable(tween(1_200), RepeatMode.Restart), label = "pulse")
    } else null
    val description = stringResource(if (recording) R.string.camera_stop_recording else R.string.camera_start_recording)
    Box(modifier.size(96.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(84.dp)
                .drawBehind {
                    val stroke = ringWidth.toPx()
                    val r = size.minDimension / 2 - stroke / 2
                    val pulse = pulseState?.value ?: 0f
                    if (pulseState != null) {
                        // Soft pulse outside the ring.
                        drawCircle(Palette.Record.copy(alpha = 0.35f * (1f - pulse)), r + stroke + 10.dp.toPx() * pulse, style = Stroke(3.dp.toPx()))
                    }
                    drawCircle(Color.Black.copy(alpha = 0.18f), r + stroke / 2 + 1.dp.toPx(), style = Stroke(2.dp.toPx()))
                    drawCircle(Color.White, r, style = Stroke(stroke))
                    if (recording) {
                        val start = (sweepState?.value ?: 0f) - 90f
                        drawArc(
                            Palette.Record, start, 70f, false,
                            topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke),
                            style = Stroke(stroke, cap = StrokeCap.Round),
                        )
                    }
                }
                .clip(CircleShape)
                .semantics { contentDescription = description; role = Role.Button }
                .pressable(enabled = !busy, haptic = null) {
                    haptics.perform(if (recording) HapticEvent.RECORD_STOP else HapticEvent.RECORD_START)
                    if (!recording && !countingDown) armed = true
                    onClick()
                },
            contentAlignment = Alignment.Center,
        ) {
            if (innerSize > 0.dp) {
                Box(
                    Modifier
                        .size(innerSize)
                        .clip(RoundedCornerShape(corner))
                        .background(Brush.verticalGradient(listOf(color.copy(alpha = 0.92f), color))),
                )
            }
            if (style == ShutterStyle.AUDIO && !active) Icon(Icons.Rounded.Mic, null, tint = Color.White, modifier = Modifier.size(28.dp))
        }
    }
}

/** Zoom-lens chips (0.5× / 1× / 2×…); the active one shows the live zoom. */
@Composable
internal fun LensChips(lenses: List<LensOption>, zoom: Float, currentCameraId: String?, iconRotation: Float, onSelect: (LensOption) -> Unit, modifier: Modifier = Modifier) {
    if (lenses.size <= 1 && abs(zoom - 1f) < 0.05f) return
    val active = lenses.filter { it.cameraId == currentCameraId }.minByOrNull { abs(it.zoomRatio - zoom) }
    Row(
        modifier.clip(RoundedCornerShape(50)).background(StudioGlass.Fill).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        lenses.forEach { lens ->
            val selected = lens == active
            val text = if (selected && abs(zoom - lens.zoomRatio) > 0.05f) zoomLabel(zoom * lens.factor / lens.zoomRatio) else ltr(lens.label.localizeDigits())
            val size by animateDpAsState(if (selected) 40.dp else 36.dp, Motion.snappy(), label = "zoomChip")
            Box(
                Modifier
                    .size(size)
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
internal fun LastTakeThumbnail(path: String?, onClick: () -> Unit, modifier: Modifier = Modifier, preview: ImageBitmap? = null) {
    val bitmap by produceState(preview, path) {
        if (value == null) {
            value = path?.let {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                        BitmapFactory.decodeFile(it, opts)?.asImageBitmap()
                    }.getOrNull()
                }
            }
        }
    }
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .size(StudioGlass.Target)
            .clip(shape)
            .background(StudioGlass.Fill)
            .border(2.dp, Color.White.copy(alpha = 0.85f), shape)
            .pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(image, stringResource(R.string.camera_last_take), contentScale = ContentScale.Crop, modifier = Modifier.size(StudioGlass.Target))
        } else {
            Icon(Icons.Rounded.VideoLibrary, stringResource(R.string.camera_last_take), tint = Color.White, modifier = Modifier.size(StudioGlass.Icon))
        }
    }
}

@Composable
internal fun FlipButton(enabled: Boolean, iconRotation: Float, onClick: () -> Unit) {
    GlassIcon(Icons.Rounded.FlipCameraAndroid, stringResource(R.string.camera_flip), onClick, rotation = iconRotation, enabled = enabled)
}

@Composable
internal fun PauseButton(paused: Boolean, iconRotation: Float, onClick: () -> Unit) {
    GlassIcon(
        if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
        stringResource(if (paused) R.string.camera_resume else R.string.camera_pause),
        onClick,
        rotation = iconRotation,
    )
}

/** Opens the lens carousel (Snapchat's smiley next to the shutter). */
@Composable
internal fun LensesButton(active: Boolean, iconRotation: Float, onClick: () -> Unit) {
    GlassIcon(Icons.Rounded.EmojiEmotions, stringResource(R.string.camera_lenses), onClick, rotation = iconRotation, selected = active)
}

/** Opens filters & background effects. */
@Composable
internal fun EffectsButton(active: Boolean, iconRotation: Float, onClick: () -> Unit) {
    GlassIcon(Icons.Rounded.AutoAwesome, stringResource(R.string.camera_effects), onClick, rotation = iconRotation, selected = active)
}

/** Recording HUD: blinking dot + time, size and remaining storage time. Reads the clock state itself. */
@Composable
internal fun RecordingHud(clock: State<RecordingStatus>, iconRotation: Float, modifier: Modifier = Modifier) {
    val status = clock.value
    val paused = status.phase == RecordingPhase.PAUSED
    val blinkState = if (!RgTheme.reduceMotion && !paused) {
        rememberInfiniteTransition(label = "blink").animateFloat(1f, 0.25f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dot")
    } else null
    Row(
        modifier
            .graphicsLayer { rotationZ = iconRotation }
            .height(32.dp)
            .clip(RoundedCornerShape(50))
            .background(if (paused) StudioGlass.FillStrong else Palette.Record.copy(alpha = 0.88f))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .graphicsLayer { alpha = blinkState?.value ?: 1f }
                .clip(CircleShape)
                .background(if (paused) Palette.Butter400 else Color.White),
        )
        Spacer(Modifier.width(8.dp))
        Text(formatDuration(status.durationUs), style = MaterialTheme.typography.titleSmall, color = Color.White, fontWeight = FontWeight.SemiBold)
        if (status.bytesWritten > 0) {
            Spacer(Modifier.width(8.dp))
            Text(formatBytes(status.bytesWritten), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f))
        }
        if (status.remainingSeconds in 0 until 24 * 3600) {
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.camera_remaining, formatDuration(status.remainingSeconds * 1_000_000)),
                style = MaterialTheme.typography.labelMedium,
                color = if (status.remainingSeconds < 120) Palette.Butter300 else Color.White.copy(alpha = 0.85f),
            )
        }
    }
}
