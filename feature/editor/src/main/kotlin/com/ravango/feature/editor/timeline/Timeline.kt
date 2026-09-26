package com.ravango.feature.editor.timeline

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ravango.core.common.format.formatDuration
import com.ravango.core.designsystem.theme.HapticEvent
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.rememberHaptics
import com.ravango.core.model.EditorDocument
import com.ravango.engine.editor.media.ThumbnailProvider
import com.ravango.engine.editor.media.WaveformProvider
import com.ravango.engine.editor.ops.Edge
import com.ravango.engine.editor.timeline.TimelineMath
import com.ravango.feature.editor.Selection
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Everything the timeline can ask the editor to do. */
class TimelineActions(
    val onSeek: (timeUs: Long, scrubbing: Boolean) -> Unit,
    val onSelect: (Selection) -> Unit,
    val onTrimClip: (clipId: String, edge: Edge, deltaUs: Long) -> Unit,
    val onMoveClip: (clipId: String, toIndex: Int) -> Unit,
    val onTransition: (clipId: String) -> Unit,
    val onRetimeOverlay: (id: String, startUs: Long, endUs: Long) -> Unit,
    val onTrimAudio: (id: String, edge: Edge, deltaUs: Long) -> Unit,
    val onMoveAudio: (id: String, startUs: Long) -> Unit,
    val onRetimeCue: (id: String, startUs: Long, endUs: Long) -> Unit,
    val onGestureEnd: () -> Unit,
)

/** Zoom/scroll geometry shared by every lane. */
class TimelineGeometry(val pxPerUs: Float, val centerPx: Float, val playhead: State<Long>) {
    fun x(timeUs: Long): Float = timeUs * pxPerUs
    fun us(px: Float): Long = (px / pxPerUs).toLong()
}

/**
 * Multi-track timeline. Time always runs left→right (like a ruler and like every major editor), independent of the UI
 * direction: in RTL locales the surrounding chrome mirrors but the timeline stays LTR so "later" is always to the right
 * and scrubbing matches the playback direction. The playhead is fixed at the centre; the content scrolls under it.
 *
 * Gestures: horizontal drag scrubs (with fling), pinch zooms, tap selects, selected items show trim handles and can be
 * dragged in time (with snapping + haptic ticks), long-press drags a clip to reorder.
 */
@Composable
fun EditorTimeline(
    document: EditorDocument,
    selection: Selection,
    playhead: State<Long>,
    reversing: Set<String>,
    thumbnails: ThumbnailProvider,
    waveforms: WaveformProvider,
    actions: TimelineActions,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        val density = LocalDensity.current
        var pxPerSecond by rememberSaveable { mutableFloatStateOf(with(density) { 64.dp.toPx() }) }
        val minPxPerSecond = with(density) { 4.dp.toPx() }
        val maxPxPerSecond = with(density) { 600.dp.toPx() }
        val haptics = rememberHaptics()
        val scope = rememberCoroutineScope()
        val touchSlop = LocalViewConfiguration.current.touchSlop
        val docState = rememberUpdatedState(document)
        val actionsState = rememberUpdatedState(actions)

        BoxWithConstraints(
            modifier
                .fillMaxWidth()
                .background(Color(0xFF12101C)),
        ) {
            val widthPx = constraints.maxWidth.toFloat()
            val geometry = TimelineGeometry(pxPerSecond / 1_000_000f, widthPx / 2f, playhead)
            val durationUs = document.durationUs
            val snapPoints = remember(document) { TimelineMath.snapPoints(document) }
            var flingJob by remember { mutableStateOf<Job?>(null) }

            Column(
                Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            flingJob?.cancel()
                            val tracker = VelocityTracker()
                            tracker.addPosition(down.uptimeMillis, down.position)
                            var dragged = false
                            var zoomed = false
                            var accum = 0f
                            var startTime = playhead.value
                            var lastBoundary = boundaryIndex(docState.value, startTime)
                            var consumedByChild = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (event.changes.any { it.isConsumed } && !dragged && !zoomed) consumedByChild = true
                                if (consumedByChild) {
                                    if (pressed.isEmpty()) break else continue
                                }
                                if (pressed.size >= 2) {
                                    val z = event.calculateZoom()
                                    if (z != 1f) {
                                        zoomed = true
                                        pxPerSecond = (pxPerSecond * z).coerceIn(minPxPerSecond, maxPxPerSecond)
                                    }
                                    event.changes.forEach { it.consume() }
                                } else if (pressed.size == 1) {
                                    val c = pressed.first()
                                    val dx = c.positionChange().x
                                    accum += dx
                                    tracker.addPosition(c.uptimeMillis, c.position)
                                    if (!dragged && abs(accum) > touchSlop && !zoomed) {
                                        dragged = true
                                        startTime = playhead.value
                                        accum = 0f
                                    }
                                    if (dragged) {
                                        val perUs = pxPerSecond / 1_000_000f
                                        val t = (startTime - (accum / perUs).toLong()).coerceIn(0, docState.value.durationUs)
                                        actionsState.value.onSeek(t, true)
                                        val b = boundaryIndex(docState.value, t)
                                        if (b != lastBoundary) {
                                            haptics.perform(HapticEvent.TICK)
                                            lastBoundary = b
                                        }
                                        c.consume()
                                    }
                                }
                                if (pressed.isEmpty()) break
                            }
                            if (dragged) {
                                val vx = tracker.calculateVelocity().x
                                val perUs = pxPerSecond / 1_000_000f
                                val start = playhead.value
                                if (abs(vx) > 400f) {
                                    flingJob = scope.launch {
                                        AnimationState(initialValue = 0f, initialVelocity = -vx).animateDecay(exponentialDecay(frictionMultiplier = 1.6f)) {
                                            val t = (start + (value / perUs).toLong()).coerceIn(0, docState.value.durationUs)
                                            actionsState.value.onSeek(t, true)
                                            if (t == 0L || t == docState.value.durationUs) cancelAnimation()
                                        }
                                        actionsState.value.onSeek(playhead.value, false)
                                    }
                                } else actionsState.value.onSeek(playhead.value, false)
                            } else if (!zoomed && !consumedByChild) {
                                actionsState.value.onSelect(Selection.None)
                            }
                        }
                    },
            ) {
                val textMeasurer = rememberTextMeasurer()
                Ruler(geometry, durationUs, textMeasurer, Modifier.fillMaxWidth().height(20.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 230.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    val contentWidthPx = (geometry.x(durationUs) + widthPx).roundToInt()
                    val contentWidthDp = with(density) { contentWidthPx.toDp() }
                    Column(
                        Modifier
                            .wrapContentWidth(Alignment.Start, unbounded = true)
                            .width(contentWidthDp)
                            .offset { IntOffset((geometry.centerPx - geometry.x(playhead.value)).roundToInt(), 0) },
                    ) {
                        val visible = rememberVisibleWindow(playhead, geometry, widthPx)
                        MainTrackLane(document, selection, geometry, reversing, thumbnails, visible, snapPoints, actions)
                        document.overlayTracks.filterNot { it.hidden }.forEach { track ->
                            OverlayLane(track, selection, geometry, snapPoints, actions)
                        }
                        document.audioTracks.forEach { track ->
                            AudioLane(track, selection, geometry, waveforms, snapPoints, actions)
                        }
                        if (document.subtitles.cues.isNotEmpty()) {
                            SubtitleLane(document.subtitles.cues, selection, geometry, snapPoints, actions)
                        }
                    }
                }
            }
            // Fixed playhead.
            Canvas(Modifier.matchParentSize()) {
                val x = size.width / 2f
                drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
                drawCircle(Color.White, radius = 4.dp.toPx(), center = Offset(x, 4.dp.toPx()))
            }
        }
    }
}

private fun boundaryIndex(doc: EditorDocument, t: Long): Int = TimelineMath.clipAt(doc, t)?.index ?: -1

/** Visible time window, recomputed only when the playhead moves by a quarter screen (limits recomposition). */
@Composable
internal fun rememberVisibleWindow(playhead: State<Long>, geometry: TimelineGeometry, widthPx: Float): State<LongRange> {
    val windowUs = geometry.us(widthPx).coerceAtLeast(1)
    val quantum = (windowUs / 4).coerceAtLeast(1)
    return remember(geometry.pxPerUs, widthPx) {
        derivedStateOf {
            val q = playhead.value / quantum
            val center = q * quantum
            (center - windowUs)..(center + windowUs)
        }
    }
}

@Composable
private fun Ruler(geometry: TimelineGeometry, durationUs: Long, textMeasurer: TextMeasurer, modifier: Modifier) {
    val labelStyle = TextStyle(color = Color.White.copy(alpha = 0.55f), fontSize = 9.sp)
    Canvas(modifier) {
        val offset = geometry.centerPx - geometry.x(geometry.playhead.value)
        // Pick a tick step giving ~70dp between labels.
        val targetUs = (70.dp.toPx() / geometry.pxPerUs).toLong()
        val step = listOf(100_000L, 250_000L, 500_000L, 1_000_000L, 2_000_000L, 5_000_000L, 10_000_000L, 30_000_000L, 60_000_000L, 300_000_000L).firstOrNull { it >= targetUs } ?: 600_000_000L
        val startUs = ((-offset / geometry.pxPerUs).toLong() / step - 1).coerceAtLeast(0) * step
        val endUs = minOf(durationUs, ((size.width - offset) / geometry.pxPerUs).toLong() + step)
        var t = startUs
        while (t <= endUs) {
            val x = offset + geometry.x(t)
            drawLine(Color.White.copy(alpha = 0.35f), Offset(x, size.height * 0.55f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
            val half = x + geometry.x(step / 2) - geometry.x(0)
            drawLine(Color.White.copy(alpha = 0.18f), Offset(half, size.height * 0.75f), Offset(half, size.height), strokeWidth = 1.dp.toPx())
            val label = textMeasurer.measure(formatDuration(t, showTenths = step < 1_000_000), labelStyle)
            drawText(label, topLeft = Offset(x + 3.dp.toPx(), 0f))
            t += step
        }
    }
}

/** Common pill frame style for lanes. */
internal fun Modifier.laneItem(selected: Boolean, color: Color, shape: androidx.compose.ui.graphics.Shape): Modifier =
    this.then(
        Modifier
            .background(color, shape)
            .then(if (selected) Modifier.border(2.dp, Color.White, shape) else Modifier),
    )

/** Draws a waveform (0..1 peaks) centered vertically. */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWaveform(values: FloatArray, color: Color) {
    if (values.isEmpty()) return
    val w = size.width / values.size
    val mid = size.height / 2
    for (i in values.indices) {
        val h = (values[i] * size.height * 0.9f).coerceAtLeast(1f)
        drawLine(color, Offset(i * w + w / 2, mid - h / 2), Offset(i * w + w / 2, mid + h / 2), strokeWidth = (w * 0.7f).coerceIn(1f, 4f))
    }
}

@Suppress("unused")
private val HandleStroke = Stroke(width = 2f)

@Composable
internal fun themeAccent(): Color = RgTheme.colors.accent
