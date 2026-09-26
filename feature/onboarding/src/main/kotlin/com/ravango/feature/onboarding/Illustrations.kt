package com.ravango.feature.onboarding

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.ravango.core.designsystem.theme.Palette
import com.ravango.core.designsystem.theme.Radius
import com.ravango.core.designsystem.theme.RgTheme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * A 0→1 looping clock for illustrations. Returns a frozen, pleasant frame when the user prefers reduced motion.
 * Read `.value` only inside draw/graphicsLayer lambdas so animation never triggers recomposition.
 */
@Composable
internal fun rememberLoop(durationMs: Int, frozenAt: Float = 0.3f): State<Float> {
    if (RgTheme.reduceMotion) return remember { mutableFloatStateOf(frozenAt) }
    val transition = rememberInfiniteTransition(label = "loop")
    return transition.animateFloat(0f, 1f, infiniteRepeatable(tween(durationMs, easing = LinearEasing), RepeatMode.Restart), label = "t")
}

private fun wave(t: Float): Float = sin(t * 2f * PI.toFloat())

/** Draws [block] mirrored horizontally in RTL so the composition reads in the same direction as the UI. */
private inline fun DrawScope.directional(rtl: Boolean, block: DrawScope.() -> Unit) {
    if (rtl) withTransform({ scale(-1f, 1f, center) }) { block() } else block()
}

// region Language

/** Two greeting bubbles gently bobbing — hello in both languages. */
@Composable
internal fun LanguageIllustration(modifier: Modifier = Modifier) {
    val t = rememberLoop(5_000)
    val colors = RgTheme.colors
    BoxWithConstraints(modifier) {
        val pw = constraints.maxWidth.toFloat()
        val ph = constraints.maxHeight.toFloat()
        Canvas(Modifier.matchParentSize()) {
            val p = t.value
            val c = center
            val r = size.minDimension * 0.38f
            drawCircle(Brush.radialGradient(listOf(Palette.Lavender300.copy(alpha = 0.55f), Color.Transparent), c, r * 1.35f), r * 1.35f, c)
            // Orbit dots.
            repeat(6) { i ->
                val a = (p + i / 6f) * 2f * PI.toFloat()
                val o = Offset(c.x + r * kotlin.math.cos(a), c.y + r * 0.55f * sin(a))
                drawCircle(listOf(Palette.Rose300, Palette.Mint300, Palette.Sky300)[i % 3].copy(alpha = 0.85f), 4.dp.toPx() + 2.dp.toPx() * ((i % 2)), o)
            }
            drawCircle(Color.White.copy(alpha = 0.6f), r * 0.98f, c, style = Stroke(1.dp.toPx()))
        }
        Bubble(
            text = "سلام",
            background = Brush.linearGradient(listOf(Palette.Lavender500, Palette.Rose400)),
            textColor = Color.White,
            modifier = Modifier
                .align(Alignment.Center)
                .graphicsLayer {
                    translationX = -pw * 0.17f
                    translationY = -ph * 0.13f + wave(t.value) * 6.dp.toPx()
                    rotationZ = -4f
                },
        )
        Bubble(
            text = "Hello",
            background = Brush.linearGradient(listOf(Color.White, colors.pastelSky)),
            textColor = Palette.Ink800,
            modifier = Modifier
                .align(Alignment.Center)
                .graphicsLayer {
                    translationX = pw * 0.17f
                    translationY = ph * 0.13f + wave(t.value + 0.5f) * 6.dp.toPx()
                    rotationZ = 4f
                },
        )
    }
}

@Composable
private fun Bubble(text: String, background: Brush, textColor: Color, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Radius.xl)
    Box(
        modifier
            .shadow(16.dp, shape, ambientColor = Palette.Lavender500.copy(alpha = 0.3f), spotColor = Palette.Lavender500.copy(alpha = 0.3f))
            .clip(shape)
            .background(background)
            .border(1.dp, Color.White.copy(alpha = 0.5f), shape)
            .padding(horizontal = 26.dp, vertical = 14.dp),
    ) {
        Text(text, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold), color = textColor)
    }
}

// endregion

// region Prompter → camera

/** Script lines leave a script card, flow into a camera frame and scroll past the eye line while "recording". */
@Composable
internal fun PrompterIllustration(modifier: Modifier = Modifier) {
    val t = rememberLoop(6_000)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(modifier) {
        val p = t.value
        directional(rtl) {
            val w = size.width
            val h = size.height
            // Camera frame.
            val fw = w * 0.52f
            val fh = h * 0.86f
            val frame = Rect(Offset(w * 0.40f, h * 0.07f), Size(fw, fh))
            val corner = CornerRadius(fw * 0.12f)
            drawRoundRect(Color.Black.copy(alpha = 0.12f), frame.topLeft + Offset(0f, 8.dp.toPx()), frame.size, corner)
            drawRoundRect(Brush.verticalGradient(listOf(Palette.Ink800, Palette.Ink900), frame.top, frame.bottom), frame.topLeft, frame.size, corner)

            // Speaker silhouette.
            val headR = fw * 0.16f
            val head = Offset(frame.center.x, frame.top + fh * 0.60f)
            drawCircle(Brush.radialGradient(listOf(Palette.Peach300, Palette.Rose300), head, headR), headR, head)
            drawRoundRect(
                Brush.verticalGradient(listOf(Palette.Lavender400, Palette.Lavender600), head.y + headR, frame.bottom),
                Offset(head.x - headR * 2.1f, head.y + headR * 1.2f),
                Size(headR * 4.2f, frame.bottom - head.y - headR * 1.2f),
                CornerRadius(headR * 1.8f),
            )

            // Scrolling prompter lines inside the top of the frame.
            val area = Rect(frame.left + fw * 0.1f, frame.top + fh * 0.07f, frame.right - fw * 0.1f, frame.top + fh * 0.40f)
            val spacing = 13.dp.toPx()
            val lineH = 5.dp.toPx()
            val widths = floatArrayOf(0.92f, 0.7f, 0.84f, 0.56f, 0.88f, 0.64f, 0.78f, 0.5f)
            val cycle = spacing * widths.size
            val scroll = (p * cycle * 2f) % cycle
            val eyeY = area.top + area.height * 0.38f
            drawRoundRect(Palette.Lavender400.copy(alpha = 0.22f), Offset(frame.left + 4.dp.toPx(), eyeY - spacing * 0.55f), Size(fw - 8.dp.toPx(), spacing * 1.1f), CornerRadius(6.dp.toPx()))
            clipRect(area.left, area.top, area.right, area.bottom) {
                for (i in 0 until widths.size * 2) {
                    val y = area.top + i * spacing - scroll
                    if (y < area.top - spacing || y > area.bottom + spacing) continue
                    val edge = (1f - abs((y - eyeY) / (area.height * 0.75f))).coerceIn(0.15f, 1f)
                    drawRoundRect(Color.White.copy(alpha = edge), Offset(area.left, y), Size(area.width * widths[i % widths.size], lineH), CornerRadius(lineH / 2))
                }
            }

            // Viewfinder corners.
            val bracket = fw * 0.13f
            val inset = 10.dp.toPx()
            val stroke = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round)
            listOf(
                Triple(Offset(frame.left + inset, frame.top + inset), 1f, 1f),
                Triple(Offset(frame.right - inset, frame.top + inset), -1f, 1f),
                Triple(Offset(frame.left + inset, frame.bottom - inset), 1f, -1f),
                Triple(Offset(frame.right - inset, frame.bottom - inset), -1f, -1f),
            ).forEach { (o, dx, dy) ->
                val path = Path().apply {
                    moveTo(o.x, o.y + dy * bracket)
                    lineTo(o.x, o.y)
                    lineTo(o.x + dx * bracket, o.y)
                }
                drawPath(path, Color.White.copy(alpha = 0.75f), style = stroke)
            }
            // REC indicator.
            val pulse = 0.55f + 0.45f * ((wave(p * 3f) + 1f) / 2f)
            drawCircle(Palette.Record.copy(alpha = pulse), 5.dp.toPx(), Offset(frame.right - inset - 8.dp.toPx(), frame.bottom - inset - 18.dp.toPx()))

            // Script card with lines flowing into the frame.
            val card = Rect(Offset(w * 0.03f, h * 0.16f), Size(w * 0.30f, h * 0.36f))
            withTransform({ rotate(-7f, card.center) }) {
                drawRoundRect(Color.Black.copy(alpha = 0.08f), card.topLeft + Offset(0f, 6.dp.toPx()), card.size, CornerRadius(14.dp.toPx()))
                drawRoundRect(Color.White, card.topLeft, card.size, CornerRadius(14.dp.toPx()))
                repeat(6) { i ->
                    val lw = card.width * (if (i % 3 == 2) 0.45f else 0.72f)
                    drawRoundRect(
                        if (i == 0) Palette.Lavender500 else Palette.Ink200,
                        Offset(card.left + card.width * 0.14f, card.top + card.height * 0.14f + i * card.height * 0.13f),
                        Size(lw, 4.dp.toPx()),
                        CornerRadius(2.dp.toPx()),
                    )
                }
            }
            val from = Offset(card.right - 6.dp.toPx(), card.top + card.height * 0.35f)
            val to = Offset(area.left + 8.dp.toPx(), area.top + area.height * 0.3f)
            repeat(5) { i ->
                val k = (p * 2f + i / 5f) % 1f
                val x = from.x + (to.x - from.x) * k
                val y = from.y + (to.y - from.y) * k - sin(k * PI.toFloat()) * h * 0.10f
                val a = sin(k * PI.toFloat())
                val lw = 26.dp.toPx() * (1f - 0.45f * k)
                drawRoundRect(
                    Brush.horizontalGradient(listOf(Palette.Lavender400, Palette.Rose400), x, x + lw),
                    Offset(x, y),
                    Size(lw, 4.dp.toPx()),
                    CornerRadius(2.dp.toPx()),
                    alpha = a,
                )
            }
        }
    }
}

// endregion

// region Beauty + AI editing

private fun DrawScope.sparkle(center: Offset, radius: Float, color: Color, alpha: Float) {
    if (alpha <= 0.01f || radius <= 0f) return
    val path = Path().apply {
        moveTo(center.x, center.y - radius)
        quadraticTo(center.x, center.y, center.x + radius, center.y)
        quadraticTo(center.x, center.y, center.x, center.y + radius)
        quadraticTo(center.x, center.y, center.x - radius, center.y)
        quadraticTo(center.x, center.y, center.x, center.y - radius)
        close()
    }
    drawPath(path, color, alpha = alpha)
}

/** A softly glowing face with twinkling sparkles above a timeline whose clips are arranged by an AI sparkle. */
@Composable
internal fun BeautyEditIllustration(modifier: Modifier = Modifier) {
    val t = rememberLoop(5_200)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(modifier) {
        val p = t.value
        directional(rtl) {
            val w = size.width
            val h = size.height
            // Face.
            val faceR = minOf(w, h) * 0.2f
            val face = Offset(w * 0.5f, h * 0.33f)
            drawCircle(Brush.radialGradient(listOf(Palette.Rose200.copy(alpha = 0.9f), Color.Transparent), face, faceR * 2f), faceR * 2f, face)
            // Hair.
            drawCircle(Brush.verticalGradient(listOf(Palette.Lavender500, Palette.Lavender700), face.y - faceR * 1.2f, face.y + faceR), faceR * 1.14f, Offset(face.x, face.y - faceR * 0.06f))
            drawCircle(Brush.radialGradient(listOf(Palette.Peach200, Palette.Peach300), face, faceR), faceR, Offset(face.x, face.y + faceR * 0.08f))
            // Fringe.
            drawArc(Palette.Lavender600, 190f, 150f, useCenter = true, topLeft = Offset(face.x - faceR * 1.02f, face.y - faceR * 1.02f), size = Size(faceR * 2.04f, faceR * 1.4f))
            // Closed happy eyes.
            val eyeStroke = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round)
            val eyeW = faceR * 0.34f
            listOf(-1f, 1f).forEach { side ->
                drawArc(Palette.Ink800, 200f, 140f, false, Offset(face.x + side * faceR * 0.38f - eyeW / 2, face.y + faceR * 0.02f), Size(eyeW, eyeW * 0.7f), style = eyeStroke)
                drawCircle(Palette.Rose300.copy(alpha = 0.7f), faceR * 0.14f, Offset(face.x + side * faceR * 0.55f, face.y + faceR * 0.38f))
            }
            drawArc(Palette.Rose500, 20f, 140f, false, Offset(face.x - faceR * 0.26f, face.y + faceR * 0.28f), Size(faceR * 0.52f, faceR * 0.34f), style = eyeStroke)
            // Sparkles.
            val spots = listOf(Offset(-1.45f, -0.9f), Offset(1.5f, -0.6f), Offset(1.25f, 0.85f), Offset(-1.3f, 0.7f), Offset(0.2f, -1.55f))
            spots.forEachIndexed { i, o ->
                val k = (wave(p * 2f + i * 0.21f) + 1f) / 2f
                sparkle(Offset(face.x + o.x * faceR, face.y + o.y * faceR), faceR * (0.12f + 0.12f * k), if (i % 2 == 0) Color.White else Palette.Butter300, 0.35f + 0.65f * k)
            }

            // Timeline panel.
            val panel = Rect(Offset(w * 0.08f, h * 0.66f), Size(w * 0.84f, h * 0.28f))
            drawRoundRect(Color.Black.copy(alpha = 0.08f), panel.topLeft + Offset(0f, 6.dp.toPx()), panel.size, CornerRadius(18.dp.toPx()))
            drawRoundRect(Color.White.copy(alpha = 0.92f), panel.topLeft, panel.size, CornerRadius(18.dp.toPx()))
            val trackH = panel.height * 0.2f
            val tracks = listOf(
                listOf(0.02f to 0.30f, 0.34f to 0.26f, 0.63f to 0.33f) to listOf(Palette.Lavender300, Palette.Rose300, Palette.Peach300),
                listOf(0.10f to 0.42f, 0.56f to 0.36f) to listOf(Palette.Mint300, Palette.Sky300),
                listOf(0.04f to 0.9f) to listOf(Palette.Butter300),
            )
            val inner = panel.width * 0.9f
            val left = panel.left + panel.width * 0.05f
            tracks.forEachIndexed { row, (clips, palette) ->
                val y = panel.top + panel.height * 0.14f + row * trackH * 1.35f
                clips.forEachIndexed { i, (start, len) ->
                    // Clips glide into place, as if arranged by the assistant.
                    val settle = ((p * 3f - row * 0.2f - i * 0.1f).coerceIn(0f, 1f))
                    val drift = (1f - settle) * inner * 0.05f * (if (i % 2 == 0) 1 else -1)
                    drawRoundRect(palette[i % palette.size], Offset(left + inner * start + drift, y), Size(inner * len, trackH), CornerRadius(trackH / 2))
                }
            }
            // Playhead.
            val px = left + inner * ((p * 1.5f) % 1f)
            drawLine(Palette.Lavender600, Offset(px, panel.top + 6.dp.toPx()), Offset(px, panel.bottom - 6.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
            drawCircle(Palette.Lavender600, 4.dp.toPx(), Offset(px, panel.top + 6.dp.toPx()))
            // AI badge.
            val badge = Offset(panel.right - 6.dp.toPx(), panel.top - 4.dp.toPx())
            val badgeR = 18.dp.toPx()
            drawCircle(Brush.linearGradient(listOf(Palette.Lavender500, Palette.Rose400, Palette.Peach400), badge - Offset(badgeR, badgeR), badge + Offset(badgeR, badgeR)), badgeR, badge)
            val pulse = (wave(p * 2f) + 1f) / 2f
            sparkle(badge, badgeR * (0.5f + 0.1f * pulse), Color.White, 1f)
            sparkle(badge + Offset(badgeR * 0.55f, -badgeR * 0.55f), badgeR * 0.22f, Color.White, 0.6f + 0.4f * pulse)
        }
    }
}

// endregion

// region Privacy

/** A calm shield with a check mark and a slow breathing halo. */
@Composable
internal fun PrivacyIllustration(modifier: Modifier = Modifier) {
    val t = rememberLoop(4_000, frozenAt = 0.25f)
    Canvas(modifier) {
        val p = t.value
        val c = center
        val s = size.minDimension
        val halo = s * (0.42f + 0.04f * wave(p))
        drawCircle(Brush.radialGradient(listOf(Palette.Mint300.copy(alpha = 0.6f), Color.Transparent), c, halo), halo, c)
        val w = s * 0.5f
        val h = s * 0.6f
        val top = c.y - h / 2
        val shield = Path().apply {
            moveTo(c.x, top)
            cubicTo(c.x + w * 0.35f, top + h * 0.08f, c.x + w * 0.5f, top + h * 0.1f, c.x + w * 0.5f, top + h * 0.12f)
            lineTo(c.x + w * 0.5f, top + h * 0.48f)
            cubicTo(c.x + w * 0.5f, top + h * 0.78f, c.x + w * 0.2f, top + h * 0.92f, c.x, top + h)
            cubicTo(c.x - w * 0.2f, top + h * 0.92f, c.x - w * 0.5f, top + h * 0.78f, c.x - w * 0.5f, top + h * 0.48f)
            lineTo(c.x - w * 0.5f, top + h * 0.12f)
            cubicTo(c.x - w * 0.5f, top + h * 0.1f, c.x - w * 0.35f, top + h * 0.08f, c.x, top)
            close()
        }
        drawPath(shield, Brush.linearGradient(listOf(Palette.Mint400, Palette.Sky400), Offset(c.x - w / 2, top), Offset(c.x + w / 2, top + h)))
        drawPath(shield, Color.White.copy(alpha = 0.6f), style = Stroke(1.5.dp.toPx()))
        val check = Path().apply {
            moveTo(c.x - w * 0.2f, c.y + h * 0.02f)
            lineTo(c.x - w * 0.04f, c.y + h * 0.16f)
            lineTo(c.x + w * 0.24f, c.y - h * 0.12f)
        }
        drawPath(check, Color.White, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

// endregion
