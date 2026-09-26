package com.ravango.feature.camera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.ravango.engine.beauty.effects.Lens
import com.ravango.engine.beauty.effects.LiveFilter
import com.ravango.engine.beauty.effects.LutGenerator
import com.ravango.engine.beauty.effects.SpriteAtlas
import com.ravango.engine.beauty.effects.SpriteAtlasLayout
import com.ravango.feature.camera.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun Lens?.label(): String = stringResource(
    when (this) {
        null -> R.string.camera_lens_none
        Lens.BIG_EYES -> R.string.camera_lens_big_eyes
        Lens.PUFFY_CHEEKS -> R.string.camera_lens_puffy_cheeks
        Lens.TINY_FACE -> R.string.camera_lens_tiny_face
        Lens.ALIEN -> R.string.camera_lens_alien
        Lens.FACE_SWIRL -> R.string.camera_lens_face_swirl
        Lens.SMOOTH_GLOW -> R.string.camera_lens_smooth_glow
        Lens.FRECKLES -> R.string.camera_lens_freckles
        Lens.SPARKLES -> R.string.camera_lens_sparkles
        Lens.SUNGLASSES -> R.string.camera_lens_sunglasses
        Lens.CAT -> R.string.camera_lens_cat
        Lens.CROWN -> R.string.camera_lens_crown
        Lens.RAINBOW -> R.string.camera_lens_rainbow
    },
)

@Composable
internal fun LiveFilter.label(): String = stringResource(
    when (this) {
        LiveFilter.NONE -> R.string.camera_filter_none
        LiveFilter.VIVID -> R.string.camera_filter_vivid
        LiveFilter.WARM -> R.string.camera_filter_warm
        LiveFilter.COOL -> R.string.camera_filter_cool
        LiveFilter.MONO -> R.string.camera_filter_mono
        LiveFilter.PASTEL -> R.string.camera_filter_pastel
        LiveFilter.FADE -> R.string.camera_filter_fade
        LiveFilter.FILM -> R.string.camera_filter_film
        LiveFilter.NOIR -> R.string.camera_filter_noir
        LiveFilter.SUNSET -> R.string.camera_filter_sunset
        LiveFilter.TEAL_ORANGE -> R.string.camera_filter_teal_orange
        LiveFilter.VINTAGE -> R.string.camera_filter_vintage
        LiveFilter.CINEMA -> R.string.camera_filter_cinema
    },
)

/** The lens sprite atlas as an ImageBitmap (drawn once off the main thread; null until ready). */
@Composable
internal fun rememberLensAtlas(): ImageBitmap? {
    val atlas by produceState(SpriteAtlas.bitmap?.asImageBitmap()) {
        if (value == null) value = withContext(Dispatchers.Default) { runCatching { SpriteAtlas.obtain().asImageBitmap() }.getOrNull() }
    }
    return atlas
}

private val Skin = Color(0xFFF7CDB0)
private val SkinShade = Color(0xFFE9A987)
private val Ink = Color(0xFF2A2233)

/** Two-tone backdrop of each lens preview. */
internal fun lensBackdrop(lens: Lens?): List<Color> = when (lens) {
    null -> listOf(Color(0xFF3A3550), Color(0xFF221F30))
    Lens.BIG_EYES -> listOf(Color(0xFFA394FB), Color(0xFF7DB8FF))
    Lens.PUFFY_CHEEKS -> listOf(Color(0xFFFFB3C7), Color(0xFFFF93AF))
    Lens.TINY_FACE -> listOf(Color(0xFFA3EAD9), Color(0xFF6FD9C0))
    Lens.ALIEN -> listOf(Color(0xFF6FD9C0), Color(0xFF3B8C8A))
    Lens.FACE_SWIRL -> listOf(Color(0xFFBFB2FF), Color(0xFFFF93AF))
    Lens.SMOOTH_GLOW -> listOf(Color(0xFFFFE1CC), Color(0xFFFFAE7A))
    Lens.FRECKLES -> listOf(Color(0xFFFFD3DF), Color(0xFFFFC9A3))
    Lens.SPARKLES -> listOf(Color(0xFF5A48CC), Color(0xFF2F2B44))
    Lens.SUNGLASSES -> listOf(Color(0xFFFFE7A3), Color(0xFFFFAE7A))
    Lens.CAT -> listOf(Color(0xFFFFD3DF), Color(0xFFBFB2FF))
    Lens.CROWN -> listOf(Color(0xFF7160E8), Color(0xFF45405F))
    Lens.RAINBOW -> listOf(Color(0xFFD3E8FF), Color(0xFFA394FB))
}

/**
 * Round lens preview: a pastel backdrop with the lens art — crops of the real sprite atlas for sprite lenses, and a
 * small illustrated face for mesh-warp / paint / glow lenses. Drawn in the draw phase only.
 */
@Composable
internal fun LensGlyph(lens: Lens?, atlas: ImageBitmap?, modifier: Modifier = Modifier) {
    val colors = remember(lens) { lensBackdrop(lens) }
    Canvas(modifier) {
        drawCircle(Brush.linearGradient(colors, Offset.Zero, Offset(size.width, size.height)))
        val s = size.minDimension
        when (lens) {
            null -> {
                val r = s * 0.22f
                drawCircle(Color.White.copy(alpha = 0.85f), r, style = Stroke(s * 0.05f))
                drawLine(Color.White.copy(alpha = 0.85f), center + Offset(-r * 0.7f, r * 0.7f), center + Offset(r * 0.7f, -r * 0.7f), s * 0.05f, StrokeCap.Round)
            }
            Lens.BIG_EYES -> face(s) {
                eye(Offset(-0.14f, -0.04f), 0.13f); eye(Offset(0.14f, -0.04f), 0.13f)
                smile(0.16f)
            }
            Lens.PUFFY_CHEEKS -> face(s, widthScale = 1.22f) {
                drawCircle(Color(0xFFFF8FAB).copy(alpha = 0.7f), s * 0.085f, center + Offset(-s * 0.2f, s * 0.07f))
                drawCircle(Color(0xFFFF8FAB).copy(alpha = 0.7f), s * 0.085f, center + Offset(s * 0.2f, s * 0.07f))
                eye(Offset(-0.1f, -0.06f), 0.045f); eye(Offset(0.1f, -0.06f), 0.045f)
                drawCircle(Ink, s * 0.035f, center + Offset(0f, s * 0.12f))
            }
            Lens.TINY_FACE -> {
                drawOval(Skin.copy(alpha = 0.55f), center - Offset(s * 0.26f, s * 0.3f), Size(s * 0.52f, s * 0.6f))
                scale(0.45f, center) {
                    face(s) { eye(Offset(-0.1f, -0.05f), 0.05f); eye(Offset(0.1f, -0.05f), 0.05f); smile(0.12f) }
                }
            }
            Lens.ALIEN -> {
                val head = Path().apply {
                    moveTo(center.x, center.y + s * 0.3f)
                    cubicTo(center.x - s * 0.2f, center.y + s * 0.18f, center.x - s * 0.32f, center.y - s * 0.34f, center.x, center.y - s * 0.34f)
                    cubicTo(center.x + s * 0.32f, center.y - s * 0.34f, center.x + s * 0.2f, center.y + s * 0.18f, center.x, center.y + s * 0.3f)
                    close()
                }
                drawPath(head, Color(0xFFB8F2C9))
                drawOval(Ink, center + Offset(-s * 0.2f, -s * 0.06f), Size(s * 0.15f, s * 0.1f))
                drawOval(Ink, center + Offset(s * 0.05f, -s * 0.06f), Size(s * 0.15f, s * 0.1f))
                drawCircle(Color.White.copy(alpha = 0.8f), s * 0.018f, center + Offset(-s * 0.15f, -s * 0.035f))
                drawCircle(Color.White.copy(alpha = 0.8f), s * 0.018f, center + Offset(s * 0.1f, -s * 0.035f))
            }
            Lens.FACE_SWIRL -> {
                val path = Path()
                for (k in 0..90) {
                    val t = k / 90f
                    val a = t * 4.2f * PI.toFloat()
                    val r = s * 0.34f * t
                    val p = center + Offset(cos(a) * r, sin(a) * r)
                    if (k == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                drawPath(path, Color.White, style = Stroke(s * 0.045f, cap = StrokeCap.Round))
            }
            Lens.SMOOTH_GLOW -> {
                drawCircle(Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), center, s * 0.42f), s * 0.42f)
                sparkle(center + Offset(s * 0.12f, -s * 0.12f), s * 0.12f)
                sparkle(center + Offset(-s * 0.14f, s * 0.1f), s * 0.07f)
            }
            Lens.FRECKLES -> face(s) {
                drawCircle(Color(0xFFFF8FAB).copy(alpha = 0.55f), s * 0.07f, center + Offset(-s * 0.15f, s * 0.05f))
                drawCircle(Color(0xFFFF8FAB).copy(alpha = 0.55f), s * 0.07f, center + Offset(s * 0.15f, s * 0.05f))
                for ((dx, dy) in FRECKLES) drawCircle(Color(0xFF8A5238), s * 0.011f, center + Offset(dx * s, dy * s))
                eye(Offset(-0.1f, -0.07f), 0.04f); eye(Offset(0.1f, -0.07f), 0.04f); smile(0.1f)
            }
            Lens.SPARKLES -> {
                atlasRegion(atlas, SpriteAtlasLayout.STAR, center + Offset(-s * 0.12f, s * 0.08f), s * 0.36f)
                atlasRegion(atlas, SpriteAtlasLayout.SPARKLE, center + Offset(s * 0.16f, -s * 0.12f), s * 0.3f)
                atlasRegion(atlas, SpriteAtlasLayout.STAR, center + Offset(s * 0.14f, s * 0.18f), s * 0.18f)
                if (atlas == null) sparkle(center, s * 0.2f)
            }
            Lens.SUNGLASSES -> {
                face(s) { smile(0.12f) }
                atlasRegion(atlas, SpriteAtlasLayout.SUNGLASSES, center + Offset(0f, -s * 0.06f), s * 0.64f)
            }
            Lens.CAT -> {
                atlasRegion(atlas, SpriteAtlasLayout.CAT_EAR, center + Offset(-s * 0.19f, -s * 0.24f), s * 0.2f)
                atlasRegion(atlas, SpriteAtlasLayout.CAT_EAR, center + Offset(s * 0.19f, -s * 0.24f), s * 0.2f, flip = true)
                face(s) { eye(Offset(-0.1f, -0.06f), 0.04f); eye(Offset(0.1f, -0.06f), 0.04f) }
                atlasRegion(atlas, SpriteAtlasLayout.CAT_FACE, center + Offset(0f, s * 0.09f), s * 0.5f)
            }
            Lens.CROWN -> {
                face(s, dy = 0.08f) { eye(Offset(-0.1f, 0.02f), 0.04f); eye(Offset(0.1f, 0.02f), 0.04f); smile(0.1f, 0.1f) }
                atlasRegion(atlas, SpriteAtlasLayout.CROWN, center + Offset(0f, -s * 0.22f), s * 0.46f)
            }
            Lens.RAINBOW -> {
                val bands = listOf(0xFFFF5A5F, 0xFFFF9F43, 0xFFFFE066, 0xFF5FD38D, 0xFF4FC3F7, 0xFF6C7BFF, 0xFFB57BFF)
                val stroke = s * 0.035f
                bands.forEachIndexed { i, c ->
                    val r = s * 0.3f - i * stroke
                    drawArc(Color(c), 180f, 180f, false, center + Offset(-r, -r + s * 0.12f), Size(r * 2, r * 2), style = Stroke(stroke))
                }
                sparkle(center + Offset(s * 0.2f, -s * 0.2f), s * 0.09f)
            }
        }
    }
}

private val FRECKLES = listOf(-0.08f to 0.0f, -0.05f to 0.03f, -0.11f to 0.03f, -0.07f to 0.06f, 0.08f to 0.0f, 0.05f to 0.03f, 0.11f to 0.03f, 0.07f to 0.06f, 0f to 0.01f)

private class FaceScope(val scope: DrawScope, val s: Float, val cx: Float, val cy: Float) {
    fun eye(at: Offset, r: Float) {
        val c = Offset(cx + at.x * s, cy + at.y * s)
        scope.drawCircle(Color.White, r * s, c)
        scope.drawCircle(Ink, r * s * 0.55f, c + Offset(0f, r * s * 0.12f))
        scope.drawCircle(Color.White, r * s * 0.18f, c + Offset(-r * s * 0.2f, -r * s * 0.15f))
    }

    fun smile(width: Float, dy: Float = 0.12f) {
        val w = width * s
        scope.drawArc(Ink, 20f, 140f, false, Offset(cx - w / 2, cy + dy * s - w * 0.35f), Size(w, w * 0.6f), style = Stroke(s * 0.025f, cap = StrokeCap.Round))
    }
}

private inline fun DrawScope.face(s: Float, widthScale: Float = 1f, dy: Float = 0f, block: FaceScope.() -> Unit) {
    val w = s * 0.5f * widthScale
    val h = s * 0.58f
    val c = center + Offset(0f, dy * s)
    drawOval(Brush.verticalGradient(listOf(Skin, SkinShade), c.y - h / 2, c.y + h / 2), c - Offset(w / 2, h / 2), Size(w, h))
    FaceScope(this, s, c.x, c.y).block()
}

private fun DrawScope.sparkle(c: Offset, r: Float) {
    val p = Path().apply {
        moveTo(c.x, c.y - r)
        quadraticTo(c.x + r * 0.15f, c.y - r * 0.15f, c.x + r, c.y)
        quadraticTo(c.x + r * 0.15f, c.y + r * 0.15f, c.x, c.y + r)
        quadraticTo(c.x - r * 0.15f, c.y + r * 0.15f, c.x - r, c.y)
        quadraticTo(c.x - r * 0.15f, c.y - r * 0.15f, c.x, c.y - r)
        close()
    }
    drawPath(p, Color.White)
}

/** Draws an atlas region centred at [c] with the given [width] (aspect kept), optionally mirrored. */
private fun DrawScope.atlasRegion(atlas: ImageBitmap?, r: SpriteAtlasLayout.Region, c: Offset, width: Float, flip: Boolean = false) {
    atlas ?: return
    val height = width * r.h / r.w
    val dst = IntOffset((c.x - width / 2).toInt(), (c.y - height / 2).toInt())
    val dstSize = IntSize(width.toInt().coerceAtLeast(1), height.toInt().coerceAtLeast(1))
    if (flip) {
        scale(-1f, 1f, c) {
            drawImage(atlas, IntOffset(r.x, r.y), IntSize(r.w, r.h), dst, dstSize)
        }
    } else {
        drawImage(atlas, IntOffset(r.x, r.y), IntSize(r.w, r.h), dst, dstSize)
    }
}

/** Round filter preview: the filter applied to three reference colours (skin, sky, foliage) as a sweep. */
@Composable
internal fun FilterSwatch(filter: LiveFilter, modifier: Modifier = Modifier) {
    val colors = remember(filter) { LutGenerator.previewSwatch(filter).map { Color(it) } }
    Canvas(modifier) {
        drawCircle(Brush.sweepGradient(colors + colors.first(), center))
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.28f), Color.Transparent), center + Offset(-size.width * 0.18f, -size.height * 0.2f), size.minDimension * 0.5f))
        if (filter == LiveFilter.NONE) {
            drawRoundRect(Color.White.copy(alpha = 0.85f), center - Offset(size.width * 0.02f, size.height * 0.2f), Size(size.width * 0.04f, size.height * 0.4f), CornerRadius(4f))
        }
    }
}
