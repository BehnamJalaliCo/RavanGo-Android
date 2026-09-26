package com.ravango.feature.camera.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ravango.core.common.format.localizeDigits
import com.ravango.core.designsystem.theme.HapticEvent
import com.ravango.core.designsystem.theme.Motion
import com.ravango.core.designsystem.theme.Palette
import com.ravango.core.designsystem.theme.rememberHaptics
import com.ravango.core.model.GridType
import com.ravango.core.model.SafeAreaType
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

private val GridColor = Color.White.copy(alpha = 0.42f)

/** Composition guides drawn inside the frame rect. */
internal fun DrawScope.drawGrid(type: GridType, frame: Rect) {
    val stroke = 1.dp.toPx()
    fun vLine(f: Float) = drawLine(GridColor, Offset(frame.left + frame.width * f, frame.top), Offset(frame.left + frame.width * f, frame.bottom), stroke)
    fun hLine(f: Float) = drawLine(GridColor, Offset(frame.left, frame.top + frame.height * f), Offset(frame.right, frame.top + frame.height * f), stroke)
    when (type) {
        GridType.NONE -> Unit
        GridType.THIRDS -> {
            vLine(1f / 3f); vLine(2f / 3f); hLine(1f / 3f); hLine(2f / 3f)
        }
        GridType.GOLDEN -> {
            vLine(0.382f); vLine(0.618f); hLine(0.382f); hLine(0.618f)
        }
        GridType.SQUARE -> {
            val cell = min(frame.width, frame.height) / 4f
            var x = frame.left + cell
            while (x < frame.right - 1f) { drawLine(GridColor, Offset(x, frame.top), Offset(x, frame.bottom), stroke); x += cell }
            var y = frame.top + cell
            while (y < frame.bottom - 1f) { drawLine(GridColor, Offset(frame.left, y), Offset(frame.right, y), stroke); y += cell }
        }
        GridType.CENTER -> {
            val c = frame.center
            val arm = 18.dp.toPx()
            drawLine(Color.White.copy(alpha = 0.7f), Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), stroke * 1.5f)
            drawLine(Color.White.copy(alpha = 0.7f), Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), stroke * 1.5f)
        }
    }
}

/**
 * Safe-area guides. Title/action safe follow broadcast conventions (80% / 90%). Social vertical marks the zones
 * covered by app chrome on vertical platforms: top bar, caption + buttons at the bottom, action rail at the end.
 * [rotationCw] is the rotation of the frame on screen (the zones follow the recording's own "up").
 */
internal fun DrawScope.drawSafeArea(type: SafeAreaType, frame: Rect, rotationCw: Int) {
    val dash = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 6.dp.toPx()))
    val stroke = Stroke(width = 1.2.dp.toPx(), pathEffect = dash)
    fun inset(f: Float) = Rect(frame.left + frame.width * f, frame.top + frame.height * f, frame.right - frame.width * f, frame.bottom - frame.height * f)
    when (type) {
        SafeAreaType.NONE -> Unit
        SafeAreaType.TITLE_SAFE -> inset(0.1f).let { drawRect(Palette.Butter400, it.topLeft, it.size, style = stroke) }
        SafeAreaType.ACTION_SAFE -> inset(0.05f).let { drawRect(Palette.Mint400, it.topLeft, it.size, style = stroke) }
        SafeAreaType.SOCIAL_VERTICAL -> {
            val shade = Color.Black.copy(alpha = 0.28f)
            rotate(rotationCw.toFloat(), frame.center) {
                // Work in the frame's own orientation: swap dimensions when displayed sideways.
                val sideways = rotationCw % 180 == 90
                val w = if (sideways) frame.height else frame.width
                val h = if (sideways) frame.width else frame.height
                val left = frame.center.x - w / 2f
                val top = frame.center.y - h / 2f
                drawRect(shade, Offset(left, top), Size(w, h * 0.12f))
                drawRect(shade, Offset(left, top + h * 0.78f), Size(w, h * 0.22f))
                drawRect(shade, Offset(left + w * 0.84f, top + h * 0.12f), Size(w * 0.16f, h * 0.66f))
                drawRect(Color.White.copy(alpha = 0.5f), Offset(left, top + h * 0.12f), Size(w * 0.84f, h * 0.66f), style = stroke)
            }
        }
    }
}

/** Aspect frame outline + dim outside the frame (visible when the frame is letterboxed). */
internal fun DrawScope.drawAspectFrame(frame: Rect) {
    drawRect(Color.White.copy(alpha = 0.35f), frame.topLeft, frame.size, style = Stroke(1.dp.toPx()))
}

/** Horizon line that snaps green within 1° of level. */
@Composable
internal fun LevelIndicator(angle: State<Float>, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    // Recomposes only when the level state flips, not on every sensor update.
    val isLevel by remember { derivedStateOf { angle.value.let { abs(it - (it / 90f).roundToInt() * 90f) < 1f } } }
    LaunchedEffect(isLevel) { if (isLevel) haptics.perform(HapticEvent.SNAP) }
    Canvas(modifier.fillMaxSize()) {
        val raw = angle.value
        val nearest = (raw / 90f).roundToInt() * 90f
        val level = abs(raw - nearest) < 1f
        val shown = if (level) nearest else raw
        val color = if (level) Palette.Mint400 else Color.White.copy(alpha = 0.8f)
        val c = center
        val half = size.minDimension * 0.28f
        rotate(shown, c) {
            val gap = 14.dp.toPx()
            drawLine(color, Offset(c.x - half, c.y), Offset(c.x - gap, c.y), 2.dp.toPx())
            drawLine(color, Offset(c.x + gap, c.y), Offset(c.x + half, c.y), 2.dp.toPx())
        }
        drawCircle(color, radius = 3.dp.toPx(), center = c)
    }
}

/** Countdown number that pops each second. */
@Composable
internal fun CountdownOverlay(value: Int?, rotation: Float, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    LaunchedEffect(value) { if (value != null) haptics.perform(HapticEvent.TICK) }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedContent(
            targetState = value,
            transitionSpec = { (scaleIn(initialScale = 1.6f) + fadeIn()) togetherWith fadeOut() },
            label = "countdown",
        ) { v ->
            if (v != null) {
                Text(
                    v.toString().localizeDigits(),
                    modifier = Modifier.graphicsLayer { rotationZ = rotation },
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 120.sp, fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
            }
        }
    }
}

/** Ring-light style front "screen flash": white around (and at the edge of) the frame. */
@Composable
internal fun ScreenFlashOverlay(frame: Rect?, modifier: Modifier = Modifier) {
    val alpha by animateFloatAsState(1f, Motion.quick(), label = "flash")
    Canvas(modifier.fillMaxSize()) {
        val white = Color.White.copy(alpha = 0.96f * alpha)
        val f = frame ?: Rect(0f, 0f, size.width, size.height)
        val ring = 26.dp.toPx()
        // Everything outside the frame is white.
        drawRect(white, Offset.Zero, Size(size.width, f.top))
        drawRect(white, Offset(0f, f.bottom), Size(size.width, size.height - f.bottom))
        drawRect(white, Offset(0f, f.top), Size(f.left, f.height))
        drawRect(white, Offset(f.right, f.top), Size(size.width - f.right, f.height))
        // A soft ring inside the frame edge.
        drawRect(white, Offset(f.left + ring / 2, f.top + ring / 2), Size(f.width - ring, f.height - ring), style = Stroke(ring))
    }
}
