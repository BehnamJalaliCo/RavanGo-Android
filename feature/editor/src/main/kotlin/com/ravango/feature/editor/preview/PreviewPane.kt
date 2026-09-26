package com.ravango.feature.editor.preview

import com.ravango.core.designsystem.component.RgSpinner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.model.EditorDocument
import com.ravango.core.model.OverlayItem
import com.ravango.engine.editor.effects.CanvasPlacement
import com.ravango.engine.editor.ops.EditOps
import com.ravango.feature.editor.R
import com.ravango.feature.editor.Selection
import kotlin.math.abs

/**
 * The letterboxed preview: the composition renders into a SurfaceView sized to the canvas aspect ratio. Tap toggles
 * playback; with a text/sticker/image/PiP overlay (or a clip) selected, one-finger drag moves it and pinch scales and
 * rotates it. A dashed frame marks the selection.
 */
@Composable
fun PreviewPane(
    /** The video surface (the player's SurfaceView in the app, a still frame in screenshot tests). */
    surface: @Composable () -> Unit,
    document: EditorDocument,
    selection: Selection,
    overlaySizes: Map<String, Pair<Float, Float>>,
    isPlaying: State<Boolean>,
    building: Boolean,
    error: Boolean,
    onTogglePlay: () -> Unit,
    onTransform: (dx: Float, dy: Float, zoom: Float, rotation: Float) -> Unit,
    onGestureEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val transformEnabled = selection is Selection.Overlay || selection is Selection.Clip
    val transformState = rememberUpdatedState(onTransform)
    val endState = rememberUpdatedState(onGestureEnd)
    val toggle = rememberUpdatedState(onTogglePlay)
    val slop = LocalViewConfiguration.current.touchSlop
    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        val ratio = document.canvas.aspectRatio.ratio.takeIf { it.isFinite() && it > 0f } ?: (9f / 16f)
        Box(Modifier.aspectRatio(ratio)) {
            surface()
            SelectionFrame(document, selection, overlaySizes, Modifier.fillMaxSize())
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { toggle.value() } }
                    .pointerInput(transformEnabled) {
                        if (!transformEnabled) return@pointerInput
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var moved = false
                            var total = Offset.Zero
                            do {
                                val event = awaitPointerEvent()
                                val pan = event.calculatePan()
                                val zoom = event.calculateZoom()
                                val rotation = event.calculateRotation()
                                total += event.changes.firstOrNull()?.positionChange() ?: Offset.Zero
                                if (!moved && (total.getDistance() > slop || abs(zoom - 1f) > 0.01f || abs(rotation) > 0.5f)) moved = true
                                if (moved) {
                                    transformState.value(pan.x / size.width, pan.y / size.height, zoom, rotation)
                                    event.changes.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                            if (moved) endState.value()
                        }
                    },
            )
            AnimatedVisibility(!isPlaying.value && !building, Modifier.align(Alignment.Center), enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.size(56.dp).background(Color.Black.copy(alpha = 0.35f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PlayArrow, stringResource(R.string.editor_play), tint = Color.White, modifier = Modifier.size(32.dp))
                }
            }
            if (building) {
                RgSpinner(Modifier.align(Alignment.TopEnd).padding(10.dp).size(18.dp), strokeWidth = 2.dp, color = Color.White)
            }
            if (error) {
                Text(
                    stringResource(R.string.editor_preview_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).background(RgTheme.colors.danger.copy(alpha = 0.8f), CircleShape).padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun SelectionFrame(document: EditorDocument, selection: Selection, overlaySizes: Map<String, Pair<Float, Float>>, modifier: Modifier) {
    val accent = RgTheme.colors.accent
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val frame: Pair<Pair<Offset, Size>, Float>? = when (selection) {
            is Selection.Overlay -> {
                val item = EditOps.findOverlay(document, selection.id) ?: return@Canvas
                val (nw, nh) = if (item is OverlayItem.Video) {
                    val aspect = if (item.source.width > 0) item.source.height.toFloat() / item.source.width else 9f / 16f
                    item.transform.scale to item.transform.scale * aspect * w / h
                } else overlaySizes[item.id] ?: return@Canvas
                (Offset(item.transform.centerX * w, item.transform.centerY * h) to Size(nw * w, nh * h)) to item.transform.rotationDegrees
            }
            is Selection.Clip -> {
                val clip = document.mainTrack.firstOrNull { it.id == selection.id } ?: return@Canvas
                val sw = clip.source.width.takeIf { it > 0 } ?: 1080
                val sh = clip.source.height.takeIf { it > 0 } ?: 1920
                val p = CanvasPlacement.compute(sw, sh, w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1), clip.crop, clip.rotationQuarterTurns, document.canvas.fit, clip.transform)
                (Offset(p.centerX, p.centerY) to Size(p.width, p.height)) to p.rotationDegrees
            }
            else -> null
        }
        frame ?: return@Canvas
        val (geom, rotation) = frame
        val (center, sz) = geom
        rotate(rotation, center) {
            drawRect(
                color = accent,
                topLeft = Offset(center.x - sz.width / 2, center.y - sz.height / 2),
                size = sz,
                style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))),
            )
            listOf(
                Offset(center.x - sz.width / 2, center.y - sz.height / 2), Offset(center.x + sz.width / 2, center.y - sz.height / 2),
                Offset(center.x - sz.width / 2, center.y + sz.height / 2), Offset(center.x + sz.width / 2, center.y + sz.height / 2),
            ).forEach { drawCircle(Color.White, 5.dp.toPx(), it) }
        }
    }
}
