package com.ravango.feature.beauty.looks

import android.util.LruCache
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.ravango.core.model.MakeupFeature
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * The look's thumbnail: an illustrated face (no photos of real people) wearing the look's actual colours — brows,
 * two-tone shadow, liner and wing, lashes, iris, blush, contour, highlight, lips with gloss / ombré, freckles — on the
 * look's backdrop with its hair and accessory. Rendered once per look and pixel size into a cached bitmap, so
 * scrolling a carousel only blits images.
 */
@Composable
fun LookAvatar(look: LookDef, modifier: Modifier = Modifier) {
    Spacer(
        modifier.drawWithCache {
            val px = min(size.width, size.height).toInt().coerceAtLeast(1)
            val bitmap = LookAvatars.bitmap(look, px)
            val left = ((size.width - px) / 2f).toInt()
            val top = ((size.height - px) / 2f).toInt()
            onDrawBehind { drawImage(bitmap, dstOffset = androidx.compose.ui.unit.IntOffset(left, top), dstSize = IntSize(px, px)) }
        },
    )
}

/** Process-wide cache of rendered look avatars (keyed by look, recipe and pixel size). */
object LookAvatars {
    private val cache = LruCache<String, ImageBitmap>(72)

    fun bitmap(look: LookDef, px: Int): ImageBitmap {
        val key = "${look.id}:${look.recipe.hashCode()}:${look.avatar.hashCode()}:$px"
        cache.get(key)?.let { return it }
        val bmp = ImageBitmap(px, px)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp), Size(px.toFloat(), px.toFloat())) {
            drawAvatar(look.recipe, look.avatar)
        }
        cache.put(key, bmp)
        return bmp
    }
}

// ------------------------------------------------------------------------------------------------ drawing

private fun c(argb: Long) = Color(argb)
private fun Color.darker(f: Float) = Color(red * (1f - f), green * (1f - f), blue * (1f - f), alpha)
private fun Color.lighter(f: Float) = Color(red + (1f - red) * f, green + (1f - green) * f, blue + (1f - blue) * f, alpha)

/** Visual strength of a layer in the thumbnail: slightly exaggerated so it reads at 56 dp. */
private fun LookRecipe.k(f: MakeupFeature): Float {
    val i = makeup[f]?.intensity ?: 0
    return if (i <= 0) 0f else (0.28f + i / 100f * 0.9f).coerceAtMost(1f)
}

private fun LookRecipe.color(f: MakeupFeature): Color = c(makeup[f]?.color ?: f.defaultColor)

/** Draws the avatar in a unit square scaled to the scope size (square). */
internal fun DrawScope.drawAvatar(recipe: LookRecipe, traits: AvatarTraits) {
    val s = size.minDimension
    fun o(x: Float, y: Float) = Offset(x * s, y * s)
    fun radial(color: Color, cx: Float, cy: Float, r: Float, alpha: Float) {
        if (alpha <= 0f) return
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = alpha.coerceIn(0f, 1f)), color.copy(alpha = 0f)), o(cx, cy), r * s), r * s, o(cx, cy))
    }

    // ---- backdrop
    drawRect(Brush.verticalGradient(listOf(c(traits.backdropTop), c(traits.backdropBottom))))
    radial(Color.White, 0.3f, 0.2f, 0.55f, 0.28f)
    // Close-up framing (like a lens thumbnail): the face fills most of the circle.
    withTransform({ scale(ZOOM, ZOOM, pivot = o(0.5f, 0.47f)) }) { drawPortrait(recipe, traits) }
}

private const val ZOOM = 1.24f

private fun DrawScope.drawPortrait(recipe: LookRecipe, traits: AvatarTraits) {
    val s = size.minDimension
    fun o(x: Float, y: Float) = Offset(x * s, y * s)
    fun path(block: Path.() -> Unit) = Path().apply(block)
    fun Path.m(x: Float, y: Float) = moveTo(x * s, y * s)
    fun Path.l(x: Float, y: Float) = lineTo(x * s, y * s)
    fun Path.cu(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) = cubicTo(x1 * s, y1 * s, x2 * s, y2 * s, x3 * s, y3 * s)
    fun Path.q(x1: Float, y1: Float, x2: Float, y2: Float) = quadraticTo(x1 * s, y1 * s, x2 * s, y2 * s)
    fun radial(color: Color, cx: Float, cy: Float, r: Float, alpha: Float) {
        if (alpha <= 0f) return
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = alpha.coerceIn(0f, 1f)), color.copy(alpha = 0f)), o(cx, cy), r * s), r * s, o(cx, cy))
    }
    val style = recipe.style
    val skin = c(traits.skin)
    val hair = c(traits.hair)
    val skinShade = skin.darker(0.16f)

    // ---- hair (back)
    val hairBrush = Brush.linearGradient(listOf(hair.lighter(0.18f), hair, hair.darker(0.25f)), o(0.2f, 0.1f), o(0.8f, 1f))
    val hairBack = when (traits.hairStyle) {
        HairStyle.LONG -> path {
            m(0.5f, 0.1f); cu(0.24f, 0.1f, 0.17f, 0.3f, 0.19f, 0.5f); cu(0.2f, 0.7f, 0.14f, 0.88f, 0.12f, 1.05f)
            l(0.88f, 1.05f); cu(0.86f, 0.88f, 0.8f, 0.7f, 0.81f, 0.5f); cu(0.83f, 0.3f, 0.76f, 0.1f, 0.5f, 0.1f); close()
        }
        HairStyle.WAVY -> path {
            m(0.5f, 0.09f); cu(0.22f, 0.09f, 0.13f, 0.28f, 0.16f, 0.46f)
            q(0.09f, 0.56f, 0.15f, 0.66f); q(0.07f, 0.78f, 0.13f, 0.88f); q(0.06f, 0.98f, 0.12f, 1.05f)
            l(0.88f, 1.05f); q(0.94f, 0.98f, 0.87f, 0.88f); q(0.93f, 0.78f, 0.85f, 0.66f); q(0.91f, 0.56f, 0.84f, 0.46f)
            cu(0.87f, 0.28f, 0.78f, 0.09f, 0.5f, 0.09f); close()
        }
        HairStyle.BOB -> path {
            m(0.5f, 0.1f); cu(0.23f, 0.1f, 0.16f, 0.32f, 0.18f, 0.55f); cu(0.19f, 0.7f, 0.22f, 0.76f, 0.3f, 0.77f)
            l(0.7f, 0.77f); cu(0.78f, 0.76f, 0.81f, 0.7f, 0.82f, 0.55f); cu(0.84f, 0.32f, 0.77f, 0.1f, 0.5f, 0.1f); close()
        }
        HairStyle.BUN -> path {
            m(0.5f, 0.15f); cu(0.3f, 0.15f, 0.24f, 0.3f, 0.25f, 0.45f); l(0.75f, 0.45f); cu(0.76f, 0.3f, 0.7f, 0.15f, 0.5f, 0.15f); close()
        }
    }
    if (traits.hairStyle == HairStyle.BUN) {
        drawCircle(hairBrush, 0.12f * s, o(0.5f, 0.12f))
        radial(Color.White, 0.47f, 0.09f, 0.07f, 0.18f)
    }
    drawPath(hairBack, hairBrush)

    // ---- neck, shoulders, garment
    val neck = path { m(0.43f, 0.66f); l(0.57f, 0.66f); cu(0.57f, 0.76f, 0.58f, 0.82f, 0.6f, 0.86f); l(0.4f, 0.86f); cu(0.42f, 0.82f, 0.43f, 0.76f, 0.43f, 0.66f); close() }
    drawPath(neck, Brush.verticalGradient(listOf(skinShade.darker(0.18f), skinShade, skin.darker(0.08f)), 0.7f * s, 0.88f * s))
    val shoulders = path {
        m(-0.08f, 1.1f); cu(-0.04f, 0.95f, 0.18f, 0.885f, 0.41f, 0.855f); q(0.5f, 0.875f, 0.59f, 0.855f)
        cu(0.82f, 0.885f, 1.04f, 0.95f, 1.08f, 1.1f); close()
    }
    drawPath(shoulders, Brush.verticalGradient(listOf(skin.darker(0.04f), skinShade), 0.86f * s, 1.05f * s))
    radial(skin.lighter(0.3f), 0.3f, 0.92f, 0.1f, 0.35f)
    radial(skin.lighter(0.3f), 0.7f, 0.92f, 0.1f, 0.35f)
    // ---- ears (visible with a bun)
    if (traits.hairStyle == HairStyle.BUN) {
        for (x in floatArrayOf(0.262f, 0.738f)) drawOval(skinShade, o(x - 0.028f, 0.44f), Size(0.056f * s, 0.1f * s))
    }

    // ---- face
    val face = path {
        m(0.5f, 0.2f)
        cu(0.64f, 0.2f, 0.735f, 0.3f, 0.735f, 0.45f)
        cu(0.735f, 0.58f, 0.69f, 0.68f, 0.605f, 0.742f)
        cu(0.565f, 0.772f, 0.535f, 0.785f, 0.5f, 0.785f)
        cu(0.465f, 0.785f, 0.435f, 0.772f, 0.395f, 0.742f)
        cu(0.31f, 0.68f, 0.265f, 0.58f, 0.265f, 0.45f)
        cu(0.265f, 0.3f, 0.36f, 0.2f, 0.5f, 0.2f)
        close()
    }
    drawPath(face, Brush.radialGradient(listOf(skin.lighter(0.1f), skin, skin.darker(0.1f)), o(0.47f, 0.42f), 0.36f * s))

    val foundationK = recipe.k(MakeupFeature.FOUNDATION)
    clipPath(face) {
        // Soft natural shading of the face edge, then contour, blush, highlight, freckles, glow.
        radial(skinShade, 0.5f, 0.88f, 0.2f, 0.4f)
        val contourK = recipe.k(MakeupFeature.CONTOUR)
        val contour = recipe.color(MakeupFeature.CONTOUR)
        for (x in floatArrayOf(0.285f, 0.715f)) {
            radial(contour, x, 0.575f, 0.11f, 0.55f * contourK)
            radial(contour, x + (if (x < 0.5f) -0.01f else 0.01f), 0.33f, 0.08f, 0.3f * contourK)
        }
        radial(contour, 0.5f, 0.83f, 0.16f, 0.35f * contourK)
        val blushK = recipe.k(MakeupFeature.BLUSH)
        for (x in floatArrayOf(0.345f, 0.655f)) radial(recipe.color(MakeupFeature.BLUSH), x, 0.555f, 0.085f, 0.8f * blushK)
        if (style.freckles > 0f) {
            val rnd = Random(7)
            repeat(34) {
                val onNose = it % 4 == 0
                val side = if (rnd.nextBoolean()) -1f else 1f
                val x = if (onNose) 0.5f + (rnd.nextFloat() - 0.5f) * 0.07f else 0.5f + side * (0.1f + rnd.nextFloat() * 0.1f)
                val y = if (onNose) 0.52f + rnd.nextFloat() * 0.05f else 0.5f + rnd.nextFloat() * 0.07f
                drawCircle(Color(0xFF8A5238).copy(alpha = (0.35f + rnd.nextFloat() * 0.4f) * style.freckles), (0.0045f + rnd.nextFloat() * 0.003f) * s, o(x, y))
            }
        }
        val hlK = recipe.k(MakeupFeature.HIGHLIGHT)
        val hl = recipe.color(MakeupFeature.HIGHLIGHT)
        for (x in floatArrayOf(0.34f, 0.66f)) radial(hl, x, 0.505f, 0.055f, 0.85f * hlK)
        radial(hl, 0.5f, 0.3f, 0.07f, 0.45f * hlK)
        if (style.skinFinish > 0f) {
            radial(Color.White, 0.44f, 0.3f, 0.09f, 0.35f * style.skinFinish)
            for (x in floatArrayOf(0.35f, 0.65f)) radial(Color.White, x, 0.51f, 0.045f, 0.45f * style.skinFinish)
        }
        if (foundationK > 0f) drawRect(recipe.color(MakeupFeature.FOUNDATION).copy(alpha = 0.12f * foundationK))
    }

    // ---- nose
    drawLine(skinShade.copy(alpha = 0.35f), o(0.478f, 0.46f), o(0.472f, 0.565f), 0.008f * s, StrokeCap.Round)
    radial(Color.White, 0.505f, 0.555f, 0.02f, 0.45f)
    for (x in floatArrayOf(0.476f, 0.524f)) drawOval(skinShade.darker(0.2f).copy(alpha = 0.45f), o(x - 0.011f, 0.578f), Size(0.022f * s, 0.011f * s))
    drawArc(skinShade.copy(alpha = 0.5f), 20f, 140f, false, o(0.47f, 0.555f), Size(0.06f * s, 0.035f * s), style = Stroke(0.006f * s, cap = StrokeCap.Round))

    // ---- eyes
    val eyeY = 0.462f
    for (side in intArrayOf(-1, 1)) {
        val sgn = side.toFloat() // outward direction in x
        val ex = 0.5f + sgn * 0.098f
        val w = 0.062f
        val ix = ex - sgn * w; val iy = eyeY + 0.004f
        val ox = ex + sgn * w; val oy = eyeY - 0.006f
        val upper = path { m(ix, iy); cu(ix + sgn * w * 0.4f, eyeY - 0.034f, ox - sgn * w * 0.55f, eyeY - 0.036f, ox, oy) }
        val lower = path { m(ox, oy); cu(ox - sgn * w * 0.35f, eyeY + 0.022f, ix + sgn * w * 0.45f, eyeY + 0.026f, ix, iy) }
        val almond = path { addPath(upper); cu(ox - sgn * w * 0.35f, eyeY + 0.022f, ix + sgn * w * 0.45f, eyeY + 0.026f, ix, iy); close() }

        // Eyeshadow on the lid (lash line → crease), second tone in the outer V, shimmer on the lid centre.
        val shadowK = recipe.k(MakeupFeature.EYESHADOW)
        val lid = path {
            m(ix - sgn * 0.006f, iy); cu(ix + sgn * w * 0.1f, eyeY - 0.082f, ox + sgn * 0.01f, eyeY - 0.084f, ox + sgn * 0.034f, oy - 0.02f)
            cu(ox + sgn * 0.01f, oy - 0.004f, ox, oy, ox, oy)
            cu(ox - sgn * w * 0.55f, eyeY - 0.036f, ix + sgn * w * 0.4f, eyeY - 0.034f, ix, iy); close()
        }
        if (shadowK > 0f) {
            val sc = recipe.color(MakeupFeature.EYESHADOW)
            drawPath(lid, Brush.verticalGradient(listOf(sc.copy(alpha = shadowK), sc.copy(alpha = 0.75f * shadowK), sc.copy(alpha = 0f)), (eyeY - 0.015f) * s, (eyeY - 0.08f) * s))
        }
        clipPath(lid) {
            radial(c(style.shadowAccent), ox + sgn * 0.006f, oy - 0.026f, 0.055f, style.shadowAccentAmount)
            radial(c(style.shimmerColor).lighter(0.4f), ex - sgn * 0.004f, eyeY - 0.03f, 0.028f, 0.95f * style.shimmer)
        }
        // Lower lash line kohl / smudge.
        val linerK = recipe.k(MakeupFeature.EYELINER)
        val liner = recipe.color(MakeupFeature.EYELINER)
        if (style.lowerLiner > 0f) drawPath(lower, liner.copy(alpha = 0.9f * style.lowerLiner), style = Stroke(0.007f * s, cap = StrokeCap.Round))
        if (style.shadowAccentAmount > 0f) drawPath(lower, c(style.shadowAccent).copy(alpha = 0.35f * style.shadowAccentAmount), style = Stroke(0.012f * s, cap = StrokeCap.Round))

        // Eyeball, iris (coloured lenses), pupil and catch-light.
        drawPath(almond, Color(0xFFF7F2EF))
        clipPath(almond) {
            drawRect(Color(0x33000000), o(ix - 0.1f, eyeY - 0.05f), Size(0.4f * s, 0.022f * s))
            val irisColor = recipe.eyeColor?.let { c(it) } ?: Color(0xFF4A2E22)
            val ic = o(ex - sgn * 0.002f, eyeY - 0.002f)
            val ir = 0.025f * s
            drawCircle(Brush.radialGradient(listOf(irisColor.lighter(0.35f), irisColor, irisColor.darker(0.45f)), ic, ir), ir, ic)
            drawCircle(Color(0xFF120C0A), ir * 0.42f, ic)
            drawCircle(Color.White.copy(alpha = 0.9f), ir * 0.24f, ic + Offset(ir * 0.38f, -ir * 0.4f))
        }
        // Natural lash line, then eyeliner (and its wing), then lashes.
        drawPath(upper, Color(0xFF2A1A16).copy(alpha = 0.55f), style = Stroke(0.006f * s, cap = StrokeCap.Round))
        if (linerK > 0f) {
            drawPath(upper, liner.copy(alpha = 0.95f), style = Stroke((0.005f + 0.007f * linerK) * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
            val wing = style.wing
            if (wing > 0.3f) {
                val len = 0.012f + 0.045f * (wing - 0.3f) / 0.7f
                val tip = o(ox + sgn * len, oy - len * 0.62f)
                val wingPath = Path().apply {
                    moveTo(o(ox - sgn * 0.02f, oy - 0.012f).x, o(0f, oy - 0.012f).y)
                    lineTo(tip.x, tip.y)
                    lineTo(o(ox, 0f).x, o(0f, oy + 0.004f).y)
                    close()
                }
                drawPath(wingPath, liner)
            }
        }
        val lashK = max(recipe.k(MakeupFeature.EYELASHES), 0.3f)
        val lash = recipe.color(MakeupFeature.EYELASHES)
        val volume = style.lashVolume
        val count = 6 + (volume * 4).toInt()
        val lashLen = 0.011f + 0.012f * lashK + 0.014f * volume
        val baseW = (0.0042f + 0.0026f * volume) * s
        for (i in 0 until count) {
            val t = 0.3f + 0.72f * i / (count - 1)
            val tt = t.coerceAtMost(1f)
            val u = 1f - tt
            // Point on the upper lid curve (the cubic above).
            val px = u * u * u * ix + 3 * u * u * tt * (ix + sgn * w * 0.4f) + 3 * u * tt * tt * (ox - sgn * w * 0.55f) + tt * tt * tt * ox
            val py = u * u * u * iy + 3 * u * u * tt * (eyeY - 0.034f) + 3 * u * tt * tt * (eyeY - 0.036f) + tt * tt * tt * oy
            val l = lashLen * (0.5f + 0.7f * tt)
            val root = o(px, py)
            val tip = o(px + sgn * l * (0.25f + 0.85f * tt), py - l * (1f - 0.45f * tt))
            val mid = o(px + sgn * l * 0.1f, py - l * 0.75f)
            // Tapered, curled lash: two quadratic edges meeting at the tip.
            val lashPath = Path().apply {
                moveTo(root.x - baseW / 2, root.y)
                quadraticTo(mid.x - baseW * 0.3f, mid.y, tip.x, tip.y)
                quadraticTo(mid.x + baseW * 0.4f, mid.y + baseW * 0.2f, root.x + baseW / 2, root.y)
                close()
            }
            drawPath(lashPath, lash.copy(alpha = 0.95f))
        }
        // Brows.
        val browK = recipe.k(MakeupFeature.EYEBROW)
        val browColor = if (browK > 0f) recipe.color(MakeupFeature.EYEBROW) else hair.darker(0.1f)
        val def = style.browDefinition
        val hx = ex - sgn * 0.052f; val hy = 0.418f
        val axx = ex + sgn * 0.018f; val ay = 0.392f - 0.006f * def
        val tx = ex + sgn * 0.07f; val ty = 0.41f
        val brow = path {
            m(hx, hy - 0.012f); q(axx - sgn * 0.03f, ay - 0.012f, axx, ay - 0.007f); q(tx - sgn * 0.02f, ay - 0.004f, tx, ty)
            q(tx - sgn * 0.024f, ay + 0.006f, axx, ay + 0.008f); q(axx - sgn * 0.03f, ay + 0.006f, hx, hy + 0.008f); close()
        }
        drawPath(brow, browColor.copy(alpha = (0.5f + 0.45f * browK) * (0.85f + 0.15f * def)))
        if (def > 0.5f) drawPath(brow, browColor.darker(0.2f), style = Stroke(0.0025f * s, join = StrokeJoin.Round), alpha = 0.6f * def)
    }

    // ---- lips
    val lipBase = lerp(skin, Color(0xFFBF6A66), 0.6f)
    val lipstickK = recipe.k(MakeupFeature.LIPSTICK)
    val tintK = recipe.k(MakeupFeature.LIP_COLOR)
    var lipColor = lipBase
    if (tintK > 0f) lipColor = lerp(lipColor, recipe.color(MakeupFeature.LIP_COLOR), 0.8f * tintK)
    if (lipstickK > 0f) lipColor = lerp(lipColor, recipe.color(MakeupFeature.LIPSTICK), 0.97f * lipstickK)
    val upperLip = path {
        m(0.425f, 0.664f); cu(0.445f, 0.65f, 0.468f, 0.63f, 0.482f, 0.631f); cu(0.49f, 0.632f, 0.495f, 0.639f, 0.5f, 0.64f)
        cu(0.505f, 0.639f, 0.51f, 0.632f, 0.518f, 0.631f); cu(0.532f, 0.63f, 0.555f, 0.65f, 0.575f, 0.664f)
        cu(0.545f, 0.669f, 0.52f, 0.667f, 0.5f, 0.668f); cu(0.48f, 0.667f, 0.455f, 0.669f, 0.425f, 0.664f); close()
    }
    val lowerLip = path {
        m(0.425f, 0.664f); cu(0.46f, 0.67f, 0.54f, 0.67f, 0.575f, 0.664f); cu(0.56f, 0.694f, 0.532f, 0.706f, 0.5f, 0.706f)
        cu(0.468f, 0.706f, 0.44f, 0.694f, 0.425f, 0.664f); close()
    }
    drawPath(upperLip, lipColor.darker(0.08f))
    drawPath(lowerLip, lipColor)
    if (style.lipCenterAmount > 0f) {
        val center = c(style.lipCenter)
        clipPath(upperLip) { radial(center, 0.5f, 0.668f, 0.05f, 0.95f * style.lipCenterAmount) }
        clipPath(lowerLip) { radial(center, 0.5f, 0.668f, 0.05f, 0.95f * style.lipCenterAmount) }
    }
    drawLine(lipColor.darker(0.4f).copy(alpha = 0.6f), o(0.44f, 0.665f), o(0.56f, 0.665f), 0.003f * s, StrokeCap.Round)
    val gloss = style.lipFinish
    if (gloss > -0.3f) {
        val g = ((gloss + 0.3f) / 1.3f).coerceIn(0f, 1f)
        drawOval(Color.White.copy(alpha = 0.25f + 0.55f * g), o(0.487f, 0.676f), Size(0.03f * s, 0.008f * s))
        drawOval(Color.White.copy(alpha = 0.15f + 0.35f * g), o(0.505f, 0.645f), Size(0.012f * s, 0.005f * s))
    }

    // ---- hair (front)
    val hairFront = when (traits.hairStyle) {
        HairStyle.LONG, HairStyle.WAVY -> path {
            m(0.5f, 0.175f); cu(0.36f, 0.17f, 0.26f, 0.26f, 0.25f, 0.42f); cu(0.245f, 0.5f, 0.25f, 0.56f, 0.262f, 0.62f)
            cu(0.275f, 0.5f, 0.31f, 0.34f, 0.47f, 0.22f); cu(0.49f, 0.21f, 0.5f, 0.2f, 0.5f, 0.2f)
            cu(0.5f, 0.2f, 0.51f, 0.21f, 0.53f, 0.22f); cu(0.69f, 0.34f, 0.725f, 0.5f, 0.738f, 0.62f)
            cu(0.75f, 0.56f, 0.755f, 0.5f, 0.75f, 0.42f); cu(0.74f, 0.26f, 0.64f, 0.17f, 0.5f, 0.175f); close()
        }
        HairStyle.BOB -> path {
            m(0.5f, 0.17f); cu(0.34f, 0.17f, 0.25f, 0.24f, 0.25f, 0.4f); cu(0.25f, 0.5f, 0.255f, 0.6f, 0.265f, 0.7f)
            cu(0.28f, 0.55f, 0.28f, 0.4f, 0.31f, 0.33f); cu(0.4f, 0.345f, 0.6f, 0.345f, 0.69f, 0.33f)
            cu(0.72f, 0.4f, 0.72f, 0.55f, 0.735f, 0.7f); cu(0.745f, 0.6f, 0.75f, 0.5f, 0.75f, 0.4f); cu(0.75f, 0.24f, 0.66f, 0.17f, 0.5f, 0.17f); close()
        }
        HairStyle.BUN -> path {
            m(0.5f, 0.165f); cu(0.36f, 0.165f, 0.27f, 0.25f, 0.262f, 0.4f); cu(0.29f, 0.3f, 0.38f, 0.225f, 0.5f, 0.222f)
            cu(0.62f, 0.225f, 0.71f, 0.3f, 0.738f, 0.4f); cu(0.73f, 0.25f, 0.64f, 0.165f, 0.5f, 0.165f); close()
        }
    }
    drawPath(hairFront, hairBrush)
    drawPath(hairFront, Brush.linearGradient(listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0f)), o(0.3f, 0.2f), o(0.45f, 0.4f)))

    // ---- accessory
    when (traits.accessory) {
        Accessory.NONE -> Unit
        Accessory.HOOPS -> for (x in floatArrayOf(0.262f, 0.738f)) {
            drawCircle(Color(0xFFE8C170), 0.028f * s, o(x, 0.585f), style = Stroke(0.007f * s))
        }
        Accessory.PEARLS -> for (x in floatArrayOf(0.264f, 0.736f)) {
            drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFFE7DED6)), o(x - 0.004f, 0.55f), 0.016f * s), 0.013f * s, o(x, 0.555f))
        }
        Accessory.TIARA -> {
            val gold = Color(0xFFF1D08A)
            drawArc(gold, 200f, 140f, false, o(0.34f, 0.17f), Size(0.32f * s, 0.12f * s), style = Stroke(0.008f * s, cap = StrokeCap.Round))
            for ((i, x) in floatArrayOf(0.4f, 0.45f, 0.5f, 0.55f, 0.6f).withIndex()) {
                val y = 0.182f - (if (i == 2) 0.028f else if (i == 1 || i == 3) 0.016f else 0.006f)
                drawCircle(if (i == 2) Color(0xFFFFF7F0) else gold, (if (i == 2) 0.013f else 0.008f) * s, o(x, y))
            }
        }
    }
}
