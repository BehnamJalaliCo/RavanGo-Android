package com.ravango.feature.camera.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ravango.core.designsystem.theme.HapticEvent
import com.ravango.core.designsystem.theme.Motion
import com.ravango.core.designsystem.theme.Palette
import com.ravango.core.designsystem.theme.rememberHaptics
import com.ravango.core.model.CameraSettings
import com.ravango.core.model.ManualControls
import com.ravango.engine.camera.AfStatus
import com.ravango.engine.camera.FocusMode
import com.ravango.engine.camera.FocusState
import com.ravango.engine.camera.PreviewFrame
import com.ravango.engine.camera.capability.CameraCapabilities
import com.ravango.engine.camera.gl.FrameGeometry
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** SurfaceView hosted in Compose; the engine renders into it from its GL thread. */
@Composable
internal fun CameraSurface(
    onSurface: (android.view.Surface, Int, Int) -> Unit,
    onSurfaceGone: (android.view.Surface) -> Unit,
    modifier: Modifier = Modifier,
) {
    val available by rememberUpdatedState(onSurface)
    val gone by rememberUpdatedState(onSurfaceGone)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = Unit
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        available(holder.surface, width, height)
                    }
                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        gone(holder.surface)
                    }
                })
            }
        },
    )
}

/** Letterboxed rect of the displayed frame inside a container. */
internal fun frameRect(frame: PreviewFrame?, container: IntSize): Rect? {
    if (frame == null || container.width == 0 || container.height == 0) return null
    val fit = FrameGeometry.fit(frame.displayWidth.toFloat(), frame.displayHeight.toFloat(), container.width.toFloat(), container.height.toFloat())
    return Rect(fit.left, fit.top, fit.left + fit.width, fit.top + fit.height)
}

/**
 * Live preview + overlays + gestures: tap to focus/meter, long-press for AE/AF lock, drag near the reticle for
 * exposure compensation, pinch to zoom.
 */
@Composable
internal fun StudioPreview(
    settings: CameraSettings,
    frame: PreviewFrame?,
    capabilities: CameraCapabilities?,
    controls: ManualControls,
    focus: FocusState,
    showOverlays: Boolean,
    iconRotation: Float,
    onSurface: (android.view.Surface, Int, Int) -> Unit,
    onSurfaceGone: (android.view.Surface) -> Unit,
    onContainerSize: (IntSize) -> Unit,
    onFocus: (Float, Float) -> Unit,
    onLock: (Float, Float) -> Unit,
    onZoom: (Float) -> Unit,
    onExposure: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    var container by remember { mutableStateOf(IntSize.Zero) }
    val rect = frameRect(frame, container)
    val currentRect by rememberUpdatedState(rect)
    val currentControls by rememberUpdatedState(controls)
    val currentCaps by rememberUpdatedState(capabilities)
    var reticle by remember { mutableStateOf<Offset?>(null) }
    var reticleStamp by remember { mutableStateOf(0L) }
    var dragAccumulator by remember { mutableFloatStateOf(0f) }
    var showEv by remember { mutableStateOf(false) }
    val stepPx = with(LocalDensity.current) { 28.dp.toPx() }

    // Hide the reticle a few seconds after the last interaction unless AE/AF is locked.
    LaunchedEffect(reticleStamp, focus.aeLocked, focus.mode) {
        if (reticle != null && !focus.aeLocked && focus.mode != FocusMode.LOCKED) {
            delay(3_500)
            reticle = null
            showEv = false
        }
    }
    LaunchedEffect(focus.mode) { if (focus.mode == FocusMode.CONTINUOUS && !focus.aeLocked) reticle = null }

    fun normalized(p: Offset): Pair<Float, Float>? {
        val r = currentRect ?: return null
        if (!r.contains(p)) return null
        return (p.x - r.left) / r.width to (p.y - r.top) / r.height
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged {
                container = it
                onContainerSize(it)
            },
    ) {
        CameraSurface(onSurface, onSurfaceGone, Modifier.fillMaxSize())

        if (showOverlays && rect != null) {
            Canvas(Modifier.fillMaxSize()) {
                drawAspectFrame(rect)
                drawGrid(settings.grid, rect)
                drawSafeArea(settings.safeArea, rect, frame?.rotationCw ?: 0)
            }
        }

        // Gesture layer.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { p ->
                            val n = normalized(p) ?: return@detectTapGestures
                            reticle = p
                            reticleStamp = System.nanoTime()
                            dragAccumulator = 0f
                            onFocus(n.first, n.second)
                        },
                        onLongPress = { p ->
                            val n = normalized(p) ?: return@detectTapGestures
                            haptics.perform(HapticEvent.LONG_PRESS)
                            reticle = p
                            reticleStamp = System.nanoTime()
                            onLock(n.first, n.second)
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (zoom != 1f) {
                            val caps = currentCaps
                            val range = caps?.zoomRange ?: (1f..1f)
                            val next = (currentControls.zoomRatio * zoom).coerceIn(range.start, range.endInclusive)
                            if (next != currentControls.zoomRatio) onZoom(next)
                        } else if (reticle != null && currentCaps?.exposureCompensation == true && currentControls.iso == null && currentControls.shutterNs == null) {
                            dragAccumulator -= pan.y
                            val steps = (dragAccumulator / stepPx).toInt()
                            if (steps != 0) {
                                dragAccumulator -= steps * stepPx
                                val range = currentCaps!!.exposureCompensationRange
                                val next = (currentControls.exposureCompensation + steps).coerceIn(range)
                                if (next != currentControls.exposureCompensation) {
                                    haptics.perform(HapticEvent.TICK)
                                    onExposure(next)
                                }
                                showEv = true
                                reticleStamp = System.nanoTime()
                            }
                        }
                    }
                },
        )

        reticle?.let { p ->
            FocusReticle(
                position = p,
                focus = focus,
                evText = if (showEv || controls.exposureCompensation != 0) evLabel(controls.exposureCompensation, capabilities?.exposureCompensationStep ?: 0f) else null,
                iconRotation = iconRotation,
            )
        }
    }
}

@Composable
private fun FocusReticle(position: Offset, focus: FocusState, evText: String?, iconRotation: Float) {
    val density = LocalDensity.current
    val sizeDp = 76.dp
    val half = with(density) { (sizeDp / 2).roundToPx() }
    val scale by animateFloatAsState(if (focus.afStatus == AfStatus.SCANNING) 1.12f else 1f, Motion.bouncy(), label = "reticle")
    val color = when {
        focus.aeLocked || focus.mode == FocusMode.LOCKED -> Palette.Butter400
        focus.afStatus == AfStatus.FOCUSED -> Palette.Mint400
        focus.afStatus == AfStatus.FAILED -> Palette.Rose400
        else -> Color.White
    }
    Box(Modifier.offset { IntOffset(position.x.roundToInt() - half, position.y.roundToInt() - half) }) {
        AnimatedVisibility(true, enter = scaleIn(initialScale = 1.5f) + fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .size(sizeDp)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .border(1.5.dp, color, RoundedCornerShape(18.dp)),
            )
        }
        Column(
            Modifier
                .offset(x = sizeDp + 6.dp, y = 10.dp)
                .graphicsLayer { rotationZ = iconRotation },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Rounded.WbSunny, null, tint = color, modifier = Modifier.size(20.dp))
            if (evText != null) {
                Text(
                    evText,
                    style = MaterialTheme.typography.labelMedium,
                    color = color,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 4.dp),
                )
            }
        }
    }
}
