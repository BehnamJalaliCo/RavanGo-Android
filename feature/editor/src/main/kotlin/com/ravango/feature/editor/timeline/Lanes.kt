package com.ravango.feature.editor.timeline

import com.ravango.core.designsystem.component.RgSpinner
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.ravango.core.designsystem.theme.HapticEvent
import com.ravango.core.designsystem.theme.Palette
import com.ravango.core.designsystem.theme.rememberHaptics
import com.ravango.core.model.AudioTrack
import com.ravango.core.model.AudioTrackKind
import com.ravango.core.model.EditorDocument
import com.ravango.core.model.MediaKind
import com.ravango.core.model.OverlayItem
import com.ravango.core.model.OverlayTrack
import com.ravango.core.model.SubtitleCue
import com.ravango.core.model.TransitionType
import com.ravango.engine.editor.media.ThumbnailProvider
import com.ravango.engine.editor.media.WaveformProvider
import com.ravango.engine.editor.ops.Edge
import com.ravango.engine.editor.timeline.ClipPlacement
import com.ravango.engine.editor.timeline.TimelineMath
import com.ravango.feature.editor.Selection
import com.ravango.feature.editor.ui.localized
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

private val MainLaneHeight = 58.dp
private val OverlayLaneHeight = 30.dp
private val AudioLaneHeight = 40.dp
private val CueLaneHeight = 28.dp
private val LaneGap = 4.dp
private val HandleWidth = 14.dp
private val ItemShape = RoundedCornerShape(8.dp)

@Composable
private fun pxToDp(px: Float): Dp = with(LocalDensity.current) { px.toDp() }

// ---------------------------------------------------------------------------------------------- main track

@Composable
internal fun MainTrackLane(
    document: EditorDocument,
    selection: Selection,
    geometry: TimelineGeometry,
    reversing: Set<String>,
    thumbnails: ThumbnailProvider,
    visible: State<LongRange>,
    snapPoints: LongArray,
    actions: TimelineActions,
) {
    val placements = remember(document.mainTrack) { TimelineMath.placements(document) }
    Box(Modifier.fillMaxWidth().height(MainLaneHeight + LaneGap).padding(top = LaneGap)) {
        placements.forEach { p ->
            key(p.clip.id) {
                ClipItem(p, placements, selection is Selection.Clip && selection.id == p.clip.id, p.clip.id in reversing, geometry, thumbnails, visible, snapPoints, actions)
            }
        }
        placements.dropLast(1).forEach { p ->
            key("t-" + p.clip.id) {
                val size = 22.dp
                val sizePx = with(LocalDensity.current) { size.toPx() }
                val hasTransition = p.clip.transitionOut.type != TransitionType.NONE
                Box(
                    Modifier
                        .offset { IntOffset((geometry.x(p.endUs) - sizePx / 2).roundToInt(), ((MainLaneHeight.toPx() - sizePx) / 2).roundToInt()) }
                        .size(size)
                        .zIndex(2f)
                        .clip(CircleShape)
                        .background(if (hasTransition) Palette.Lavender500 else Color.White)
                        .pointerInput(p.clip.id) { detectTapGestures { actions.onTransition(p.clip.id) } },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (hasTransition) Icons.Rounded.SwapHoriz else Icons.Rounded.Add, null, tint = if (hasTransition) Color.White else Color.Black, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

@Composable
private fun ClipItem(
    p: ClipPlacement,
    all: List<ClipPlacement>,
    selected: Boolean,
    reversing: Boolean,
    geometry: TimelineGeometry,
    thumbnails: ThumbnailProvider,
    visible: State<LongRange>,
    snapPoints: LongArray,
    actions: TimelineActions,
) {
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val widthPx = geometry.x(p.durationUs).coerceAtLeast(2f)
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableFloatStateOf(0f) }
    val placement by rememberUpdatedState(p)
    val allPlacements by rememberUpdatedState(all)
    var endRaw by remember { mutableLongStateOf(0L) }
    var lastSnap by remember { mutableLongStateOf(Long.MIN_VALUE) }
    val snapPx = with(density) { 10.dp.toPx() }
    Box(
        Modifier
            .offset { IntOffset(geometry.x(p.startUs).roundToInt(), 0) }
            .width(pxToDp(widthPx))
            .height(MainLaneHeight)
            .zIndex(if (dragging > 0f) 4f else if (selected) 3f else 0f)
            .graphicsLayer {
                translationX = dragX
                val s = if (dragging > 0f) 1.04f else 1f
                scaleX = s; scaleY = s
                shadowElevation = if (dragging > 0f) 12f else 0f
            }
            .clip(ItemShape)
            .background(Color(0xFF2A2640))
            .then(if (selected) Modifier.border(2.dp, Color.White, ItemShape) else Modifier)
            .pointerInput(p.clip.id) { detectTapGestures { actions.onSelect(Selection.Clip(placement.clip.id)) } }
            .pointerInput(p.clip.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { dragging = 1f; haptics.perform(HapticEvent.LONG_PRESS) },
                    onDragEnd = {
                        val center = placement.startUs + placement.durationUs / 2 + (dragX / geometry.pxPerUs).toLong()
                        val target = allPlacements.indexOfFirst { center < it.endUs }.let { if (it < 0) allPlacements.lastIndex else it }
                        if (target != placement.index) {
                            actions.onMoveClip(placement.clip.id, target)
                            haptics.perform(HapticEvent.CONFIRM)
                        }
                        dragX = 0f; dragging = 0f
                    },
                    onDragCancel = { dragX = 0f; dragging = 0f },
                ) { change, amount ->
                    change.consume()
                    dragX += amount.x
                }
            },
    ) {
        ThumbnailStrip(p, geometry, thumbnails, visible, widthPx)
        // Badges.
        Row(Modifier.align(Alignment.TopStart).padding(start = HandleWidth + 2.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (p.clip.speed != 1f) Badge(localized(trimFloat(p.clip.speed)) + "×")
            if (p.clip.reversed) {
                Spacer(Modifier.width(3.dp))
                if (reversing) RgSpinner(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = Color.White)
                else Icon(Icons.Rounded.Replay, null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
            if (p.clip.muted) {
                Spacer(Modifier.width(3.dp))
                Icon(Icons.AutoMirrored.Rounded.VolumeOff, null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
            if (p.clip.source.kind == MediaKind.IMAGE) {
                Spacer(Modifier.width(3.dp))
                Icon(Icons.Rounded.Image, null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
        if (widthPx > with(density) { 64.dp.toPx() }) {
            Text(
                com.ravango.feature.editor.ui.timecode(p.durationUs, tenths = true),
                color = Color.White, fontSize = 9.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = HandleWidth + 2.dp, bottom = 4.dp).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
            )
        }
        if (selected) {
            TrimHandle(Edge.START, Modifier.align(Alignment.CenterStart), onStart = {}, onEnd = actions.onGestureEnd) { dx ->
                actions.onTrimClip(placement.clip.id, Edge.START, (dx / geometry.pxPerUs).toLong())
            }
            TrimHandle(Edge.END, Modifier.align(Alignment.CenterEnd), onStart = { endRaw = placement.endUs; lastSnap = Long.MIN_VALUE }, onEnd = actions.onGestureEnd) { dx ->
                endRaw += (dx / geometry.pxPerUs).toLong()
                val snapped = TimelineMath.snap(endRaw, snapPoints, (snapPx / geometry.pxPerUs).toLong())
                if (snapped != endRaw && snapped != lastSnap) haptics.perform(HapticEvent.SNAP)
                lastSnap = if (snapped != endRaw) snapped else Long.MIN_VALUE
                val delta = snapped - placement.endUs
                if (delta != 0L) actions.onTrimClip(placement.clip.id, Edge.END, delta)
            }
        }
    }
}

@Composable
private fun BoxScope.ThumbnailStrip(p: ClipPlacement, geometry: TimelineGeometry, thumbnails: ThumbnailProvider, visible: State<LongRange>, widthPx: Float) {
    val density = LocalDensity.current
    val tileWidthPx = with(density) { 36.dp.toPx() }
    val heightPx = with(density) { MainLaneHeight.roundToPx() }
    val tiles = ceil(widthPx / tileWidthPx).toInt().coerceAtLeast(1)
    val tileUs = (tileWidthPx / geometry.pxPerUs).toLong().coerceAtLeast(1)
    val window by visible
    val first = (((window.first - p.startUs) / tileUs).toInt() - 1).coerceIn(0, tiles - 1)
    val last = (((window.last - p.startUs) / tileUs).toInt() + 1).coerceIn(0, tiles - 1)
    if (window.last < p.startUs || window.first > p.endUs) return
    for (i in first..last) {
        key(i) {
            val t = p.startUs + i * tileUs + tileUs / 2
            val sourceUs = TimelineMath.timelineToSource(p.clip, p.startUs, t.coerceAtMost(p.endUs - 1))
            val quantized = sourceUs / 250_000 * 250_000
            val bitmap by produceState<Bitmap?>(thumbnails.cached(p.clip.source.uri, quantized, heightPx), p.clip.source.uri, quantized) {
                value = thumbnails.frame(p.clip.source.uri, p.clip.source.kind, quantized, heightPx)
            }
            Box(
                Modifier
                    .offset { IntOffset((i * tileWidthPx).roundToInt(), 0) }
                    .width(with(density) { tileWidthPx.toDp() })
                    .fillMaxHeight(),
            ) {
                bitmap?.let {
                    Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String) {
    Text(text, color = Color.White, fontSize = 9.sp, modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp))
}

private fun trimFloat(v: Float): String = if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()

/** White grip at a clip edge; reports raw horizontal drag deltas in px. */
@Composable
private fun TrimHandle(edge: Edge, modifier: Modifier, onStart: () -> Unit, onEnd: () -> Unit, onDrag: (Float) -> Unit) {
    val onDragState by rememberUpdatedState(onDrag)
    val onStartState by rememberUpdatedState(onStart)
    val onEndState by rememberUpdatedState(onEnd)
    Box(
        modifier
            .width(HandleWidth)
            .fillMaxHeight()
            .zIndex(4f)
            .background(Color.White, if (edge == Edge.START) RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp) else RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp))
            .pointerInput(edge) {
                detectHorizontalDragGestures(onDragStart = { onStartState() }, onDragEnd = { onEndState() }, onDragCancel = { onEndState() }) { change, dx ->
                    change.consume()
                    onDragState(dx)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(3.dp).height(16.dp).background(Color(0xFF2A2640), RoundedCornerShape(2.dp)))
    }
}

// ---------------------------------------------------------------------------------------------- generic timed items

@Composable
private fun TimedItem(
    id: String,
    startUs: Long,
    endUs: Long,
    height: Dp,
    color: Color,
    selected: Boolean,
    geometry: TimelineGeometry,
    snapPoints: LongArray,
    onTap: () -> Unit,
    onRetime: (startUs: Long, endUs: Long) -> Unit,
    onGestureEnd: () -> Unit,
    minStartUs: Long = 0,
    content: @Composable BoxScope.() -> Unit,
) {
    val haptics = rememberHaptics()
    val start by rememberUpdatedState(startUs)
    val end by rememberUpdatedState(endUs)
    val retime by rememberUpdatedState(onRetime)
    val tap by rememberUpdatedState(onTap)
    val gestureEnd by rememberUpdatedState(onGestureEnd)
    val widthPx = geometry.x(endUs - startUs).coerceAtLeast(6f)
    Box(
        Modifier
            .offset { IntOffset(geometry.x(startUs).roundToInt(), 0) }
            .width(pxToDp(widthPx))
            .height(height)
            .zIndex(if (selected) 1f else 0f)
            .clip(ItemShape)
            .laneItem(selected, color, ItemShape)
            .pointerInput(id) { detectTapGestures { tap() } }
            .then(
                if (selected) Modifier.pointerInput(id, geometry.pxPerUs) {
                    var raw = 0L
                    var lastSnapped = Long.MIN_VALUE
                    detectHorizontalDragGestures(
                        onDragStart = { raw = start },
                        onDragEnd = { gestureEnd() },
                        onDragCancel = { gestureEnd() },
                    ) { change, dx ->
                        change.consume()
                        raw += (dx / geometry.pxPerUs).toLong()
                        val len = end - start
                        val threshold = (10.dp.toPx() / geometry.pxPerUs).toLong()
                        val snapStart = TimelineMath.snap(raw, snapPoints, threshold)
                        val snapEnd = TimelineMath.snap(raw + len, snapPoints, threshold) - len
                        val s = when {
                            snapStart != raw -> snapStart
                            snapEnd != raw -> snapEnd
                            else -> raw
                        }.coerceAtLeast(minStartUs)
                        if (s != raw && s != lastSnapped) haptics.perform(HapticEvent.SNAP)
                        lastSnapped = if (s != raw) s else Long.MIN_VALUE
                        retime(s, s + len)
                    }
                } else Modifier,
            ),
    ) {
        content()
        if (selected) {
            EdgeHandle(Edge.START, Modifier.align(Alignment.CenterStart), geometry, snapPoints, { start }, { gestureEnd() }) { newStart ->
                retime(newStart.coerceIn(minStartUs, end - TimelineMath.MIN_CLIP_US), end)
            }
            EdgeHandle(Edge.END, Modifier.align(Alignment.CenterEnd), geometry, snapPoints, { end }, { gestureEnd() }) { newEnd ->
                retime(start, newEnd.coerceAtLeast(start + TimelineMath.MIN_CLIP_US))
            }
        }
    }
}

@Composable
private fun EdgeHandle(
    edge: Edge,
    modifier: Modifier,
    geometry: TimelineGeometry,
    snapPoints: LongArray,
    current: () -> Long,
    onEnd: () -> Unit,
    onEdge: (Long) -> Unit,
) {
    val haptics = rememberHaptics()
    val currentState by rememberUpdatedState(current)
    val onEdgeState by rememberUpdatedState(onEdge)
    val onEndState by rememberUpdatedState(onEnd)
    Box(
        modifier
            .width(12.dp)
            .fillMaxHeight()
            .zIndex(4f)
            .background(Color.White)
            .pointerInput(edge, geometry.pxPerUs) {
                var raw = 0L
                detectHorizontalDragGestures(onDragStart = { raw = currentState() }, onDragEnd = { onEndState() }, onDragCancel = { onEndState() }) { change, dx ->
                    change.consume()
                    raw += (dx / geometry.pxPerUs).toLong()
                    val snapped = TimelineMath.snap(raw, snapPoints, (10.dp.toPx() / geometry.pxPerUs).toLong())
                    if (snapped != raw && snapped != currentState()) haptics.perform(HapticEvent.SNAP)
                    onEdgeState(snapped)
                }
            },
    )
}

// ---------------------------------------------------------------------------------------------- lanes

@Composable
internal fun OverlayLane(track: OverlayTrack, selection: Selection, geometry: TimelineGeometry, snapPoints: LongArray, actions: TimelineActions) {
    Box(Modifier.fillMaxWidth().height(OverlayLaneHeight + LaneGap).padding(top = LaneGap)) {
        track.items.forEach { item ->
            key(item.id) {
                val color = when (item) {
                    is OverlayItem.Text -> Palette.Lavender500
                    is OverlayItem.Sticker -> Color(0xFFE0A93B)
                    is OverlayItem.Image -> Palette.Mint500
                    is OverlayItem.Video -> Color(0xFF4F8FE0)
                }
                val label = when (item) {
                    is OverlayItem.Text -> item.text
                    is OverlayItem.Sticker -> item.emoji ?: "★"
                    is OverlayItem.Image -> if (item.isWatermark) "©" else "🖼"
                    is OverlayItem.Video -> "▶"
                }
                TimedItem(
                    id = item.id, startUs = item.startUs, endUs = item.endUs, height = OverlayLaneHeight, color = color,
                    selected = selection is Selection.Overlay && selection.id == item.id, geometry = geometry, snapPoints = snapPoints,
                    onTap = { actions.onSelect(Selection.Overlay(item.id)) },
                    onRetime = { s, e -> actions.onRetimeOverlay(item.id, s, e) },
                    onGestureEnd = actions.onGestureEnd,
                ) {
                    Text(label, color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp))
                }
            }
        }
    }
}

@Composable
internal fun AudioLane(track: AudioTrack, selection: Selection, geometry: TimelineGeometry, waveforms: WaveformProvider, snapPoints: LongArray, actions: TimelineActions) {
    val color = when (track.kind) {
        AudioTrackKind.MUSIC -> Color(0xFF2E7D6B)
        AudioTrackKind.VOICEOVER -> Color(0xFFB0476A)
        AudioTrackKind.SFX -> Color(0xFF8A6D2E)
        AudioTrackKind.EXTRACTED -> Color(0xFF3F5FA8)
    }
    Box(Modifier.fillMaxWidth().height(AudioLaneHeight + LaneGap).padding(top = LaneGap).graphicsLayer { alpha = if (track.muted) 0.45f else 1f }) {
        track.clips.forEach { clip ->
            key(clip.id) {
                val selected = selection is Selection.Audio && selection.id == clip.id
                val wave by produceState(waveforms.cached(clip.source.uri), clip.source.uri) {
                    value = waveforms.waveform(clip.source.uri, clip.source.durationUs)
                }
                TimedItem(
                    id = clip.id, startUs = clip.startUs, endUs = clip.endUs, height = AudioLaneHeight, color = color,
                    selected = selected, geometry = geometry, snapPoints = snapPoints,
                    onTap = { actions.onSelect(Selection.Audio(clip.id)) },
                    onRetime = { s, e ->
                        if (s != clip.startUs && e - s == clip.endUs - clip.startUs) actions.onMoveAudio(clip.id, s)
                        else if (s != clip.startUs) actions.onTrimAudio(clip.id, Edge.START, s - clip.startUs)
                        else if (e != clip.endUs) actions.onTrimAudio(clip.id, Edge.END, e - clip.endUs)
                    },
                    onGestureEnd = actions.onGestureEnd,
                ) {
                    val data = wave
                    if (data != null) {
                        Canvas(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 4.dp)) {
                            val count = (size.width / 3.dp.toPx()).toInt().coerceIn(1, 2000)
                            drawWaveform(WaveformProvider.slice(data, clip.source.durationUs, clip.trimStartUs, clip.trimEndUs, count), Color.White.copy(alpha = 0.75f))
                        }
                    }
                    Icon(
                        if (track.kind == AudioTrackKind.VOICEOVER) Icons.Rounded.Mic else Icons.Rounded.MusicNote,
                        null,
                        tint = Color.White,
                        modifier = Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 3.dp).size(12.dp),
                    )
                    if (clip.loop) Text("∞", color = Color.White, fontSize = 11.sp, modifier = Modifier.align(Alignment.TopEnd).padding(end = 14.dp))
                }
            }
        }
    }
}

@Composable
internal fun SubtitleLane(cues: List<SubtitleCue>, selection: Selection, geometry: TimelineGeometry, snapPoints: LongArray, actions: TimelineActions) {
    Box(Modifier.fillMaxWidth().height(CueLaneHeight + LaneGap).padding(top = LaneGap)) {
        cues.forEach { cue ->
            key(cue.id) {
                TimedItem(
                    id = cue.id, startUs = cue.startUs, endUs = cue.endUs, height = CueLaneHeight, color = Color(0xFF3B3552),
                    selected = selection is Selection.Cue && selection.id == cue.id, geometry = geometry, snapPoints = snapPoints,
                    onTap = { actions.onSelect(Selection.Cue(cue.id)) },
                    onRetime = { s, e -> actions.onRetimeCue(cue.id, s, e) },
                    onGestureEnd = actions.onGestureEnd,
                ) {
                    Text(cue.text, color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 12.dp))
                }
            }
        }
    }
}
