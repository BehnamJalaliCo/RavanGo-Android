package com.ravango.feature.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ravango.core.designsystem.theme.Palette
import com.ravango.core.model.AudioLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter

internal const val METER_FLOOR_DB = -60f

/** Maps dBFS to 0..1 on a -60…0 dB scale. */
internal fun meterFraction(db: Float): Float = ((db - METER_FLOOR_DB) / -METER_FLOOR_DB).coerceIn(0f, 1f)

/** Remembers a clipping indicator that latches for 2 s after the last clipped buffer. */
@Composable
internal fun rememberClipLatch(level: State<AudioLevel>): State<Boolean> {
    val latched = remember { mutableStateOf(false) }
    var stamp by remember { mutableStateOf(0L) }
    LaunchedEffect(level) {
        snapshotFlow { level.value.clipping }.filter { it }.collect {
            latched.value = true
            stamp = System.nanoTime()
        }
    }
    LaunchedEffect(stamp) {
        if (stamp != 0L) {
            delay(2_000)
            latched.value = false
        }
    }
    return latched
}

/**
 * Horizontal peak + RMS meter (green / yellow / red zones). The level is read only in the draw phase, so the
 * 30 Hz updates never recompose anything.
 */
@Composable
internal fun LevelMeterBar(level: State<AudioLevel>, clipping: State<Boolean>, modifier: Modifier = Modifier, height: Dp = 10.dp) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer { if (rtl) scaleX = -1f }
            .clip(RoundedCornerShape(height / 2))
            .background(Color.White.copy(alpha = 0.14f))
            .drawBehind {
                val l = level.value
                val w = size.width
                val h = size.height
                val rms = meterFraction(l.rmsDbfs) * w
                val peak = meterFraction(l.peakDbfs) * w
                val greenEnd = meterFraction(-12f) * w
                val yellowEnd = meterFraction(-3f) * w
                fun seg(from: Float, to: Float, color: Color) {
                    val end = minOf(to, rms)
                    if (end > from) drawRect(color, Offset(from, 0f), Size(end - from, h))
                }
                seg(0f, greenEnd, Palette.Mint400)
                seg(greenEnd, yellowEnd, Palette.Butter400)
                seg(yellowEnd, w, Palette.Rose400)
                val peakColor = when {
                    l.peakDbfs > -3f -> Palette.Rose400
                    l.peakDbfs > -12f -> Palette.Butter400
                    else -> Color.White
                }
                drawRoundRect(peakColor, Offset((peak - 2.dp.toPx()).coerceAtLeast(0f), 0f), Size(2.dp.toPx(), h), CornerRadius(1.dp.toPx()))
                if (clipping.value) drawRect(Palette.Record, Offset(w - 6.dp.toPx(), 0f), Size(6.dp.toPx(), h))
            },
    )
}
