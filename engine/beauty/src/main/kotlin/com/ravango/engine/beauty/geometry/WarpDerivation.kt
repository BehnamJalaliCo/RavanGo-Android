package com.ravango.engine.beauty.geometry

import com.ravango.core.model.BeautyFeature
import com.ravango.core.model.BeautyState
import com.ravango.engine.beauty.tracking.Contour
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A packed list of local warps, laid out exactly as the warp shader's uniform arrays:
 * - `a[i] = (centerX, centerY, radius, type)`
 * - `b[i] = (vx, vy, strength, 0)`
 *
 * All values are in frame space (isotropic, y down). Types:
 * - [TYPE_TRANSLATE]: content inside the radius moves by (vx, vy) with a smooth falloff.
 * - [TYPE_SCALE]: radial magnification by `strength` (> 0 enlarges).
 * - [TYPE_AXIS_SCALE]: magnification only along the unit axis (vx, vy).
 *
 * Falloff is `(1 - (d/r)^2)^2`; displacements are clamped so the mapping stays monotonic (no tearing/folding).
 */
class WarpSet {
    val a = FloatArray(MAX_WARPS * 4)
    val b = FloatArray(MAX_WARPS * 4)
    var count = 0
        private set

    fun clear() { count = 0 }

    fun type(i: Int): Int = a[i * 4 + 3].toInt()
    fun centerX(i: Int) = a[i * 4]
    fun centerY(i: Int) = a[i * 4 + 1]
    fun radius(i: Int) = a[i * 4 + 2]
    fun vx(i: Int) = b[i * 4]
    fun vy(i: Int) = b[i * 4 + 1]
    fun strength(i: Int) = b[i * 4 + 2]

    fun addTranslate(cx: Float, cy: Float, radius: Float, vx: Float, vy: Float) {
        if (count >= MAX_WARPS || radius <= 0f) return
        var x = vx; var y = vy
        val len = sqrt(x * x + y * y)
        if (len < MIN_DISPLACEMENT) return
        val maxLen = radius * MAX_TRANSLATE_RATIO
        if (len > maxLen) { x *= maxLen / len; y *= maxLen / len }
        put(cx, cy, radius, TYPE_TRANSLATE, x, y, 0f)
    }

    fun addScale(cx: Float, cy: Float, radius: Float, strength: Float) {
        if (count >= MAX_WARPS || radius <= 0f || abs(strength) < MIN_STRENGTH) return
        put(cx, cy, radius, TYPE_SCALE, 0f, 0f, strength.coerceIn(-MAX_SCALE, MAX_SCALE))
    }

    fun addAxisScale(cx: Float, cy: Float, radius: Float, axisX: Float, axisY: Float, strength: Float) {
        if (count >= MAX_WARPS || radius <= 0f || abs(strength) < MIN_STRENGTH) return
        val len = sqrt(axisX * axisX + axisY * axisY)
        if (len < 1e-6f) return
        put(cx, cy, radius, TYPE_AXIS_SCALE, axisX / len, axisY / len, strength.coerceIn(-MAX_SCALE, MAX_SCALE))
    }

    private fun put(cx: Float, cy: Float, r: Float, type: Int, vx: Float, vy: Float, s: Float) {
        val o = count * 4
        a[o] = cx; a[o + 1] = cy; a[o + 2] = r; a[o + 3] = type.toFloat()
        b[o] = vx; b[o + 1] = vy; b[o + 2] = s; b[o + 3] = 0f
        count++
    }

    /**
     * CPU reference of the shader mapping: returns the source point sampled for output point (px, py).
     * Used by tests; mirrors `WARP_FRAGMENT` exactly.
     */
    fun sourceOf(px: Float, py: Float, out: FloatArray) {
        var dx = 0f; var dy = 0f
        for (i in 0 until count) {
            val cx = centerX(i); val cy = centerY(i); val r = radius(i)
            val ox = px - cx; val oy = py - cy
            val d2 = (ox * ox + oy * oy) / (r * r)
            if (d2 >= 1f) continue
            val f = (1f - d2) * (1f - d2)
            when (type(i)) {
                TYPE_TRANSLATE -> { dx += vx(i) * f; dy += vy(i) * f }
                TYPE_SCALE -> { dx += ox * strength(i) * f; dy += oy * strength(i) * f }
                else -> {
                    val proj = ox * vx(i) + oy * vy(i)
                    dx += vx(i) * proj * strength(i) * f
                    dy += vy(i) * proj * strength(i) * f
                }
            }
        }
        out[0] = px - dx
        out[1] = py - dy
    }

    companion object {
        /** 16 warps × 2 vec4 = 32 uniform vectors: comfortably inside GLES 2 fragment uniform limits. */
        const val MAX_WARPS = 16
        const val TYPE_TRANSLATE = 0
        const val TYPE_SCALE = 1
        const val TYPE_AXIS_SCALE = 2
        /** |v| / r bound: the falloff's max slope is ~1.54, so 0.45 keeps the inverse mapping monotonic. */
        const val MAX_TRANSLATE_RATIO = 0.45f
        const val MAX_SCALE = 0.35f
        private const val MIN_DISPLACEMENT = 1e-5f
        private const val MIN_STRENGTH = 1e-4f
    }
}

/** Derives reshape warps (face slim, jaw, chin, cheekbones, forehead, nose, eye size/shape) from face geometry. */
object WarpDerivation {

    /** Unipolar intensity 0..100 → 0..1. */
    fun unipolar(state: BeautyState, f: BeautyFeature): Float = (state.intensity(f) / 100f).coerceIn(0f, 1f)

    /** Bipolar intensity 0..100 (50 neutral) → -1..1. */
    fun bipolar(state: BeautyState, f: BeautyFeature): Float = ((state.intensity(f) - 50) / 50f).coerceIn(-1f, 1f)

    /** True when any reshape feature is away from neutral. */
    fun hasReshape(state: BeautyState): Boolean = ReshapeFeatures.any { f ->
        state.intensity(f) != if (f.bipolar) 50 else 0
    }

    val ReshapeFeatures = listOf(
        BeautyFeature.FACE_SLIM, BeautyFeature.JAW, BeautyFeature.CHIN, BeautyFeature.CHEEKBONE,
        BeautyFeature.FOREHEAD, BeautyFeature.NOSE, BeautyFeature.EYE_SIZE, BeautyFeature.EYE_SHAPE,
    )

    /**
     * Fills [out] with warps for [state] on face [g], scaled by [presence] (0..1 tracking confidence).
     * [scratch] must hold at least 2 floats.
     */
    fun derive(g: FaceGeometry, state: BeautyState, presence: Float, out: WarpSet, scratch: FloatArray = FloatArray(2)) {
        out.clear()
        if (presence <= 0f) return
        val w = g.faceWidth
        val h = g.chinLevel - g.eyeLevel

        // FACE_SLIM: pull cheeks and jaw toward the vertical midline.
        val slim = unipolar(state, BeautyFeature.FACE_SLIM) * presence
        if (slim > 0f) {
            for (side in 0..1) {
                val inward = if (side == 0) 1f else -1f
                for ((fraction, weight) in SlimSamples) {
                    g.ovalSidePoint(side, fraction, scratch)
                    val m = inward * slim * 0.05f * w * weight
                    out.addTranslate(scratch[0], scratch[1], 0.32f * w, g.exX * m, g.exY * m)
                }
            }
        }

        // JAW (bipolar): widen (> 50) or narrow (< 50) the lower oval.
        val jaw = bipolar(state, BeautyFeature.JAW) * presence
        if (jaw != 0f) {
            for (side in 0..1) {
                val outward = if (side == 0) -1f else 1f
                g.ovalSidePoint(side, 0.85f, scratch)
                val m = outward * jaw * 0.035f * w
                out.addTranslate(scratch[0], scratch[1], 0.24f * w, g.exX * m, g.exY * m)
            }
        }

        // CHIN (bipolar): > 50 lengthens (moves chin down), < 50 shortens.
        val chin = bipolar(state, BeautyFeature.CHIN) * presence
        if (chin != 0f) {
            val lm = g.landmarks
            val cx = lm.x(Contour.FACE, g.chinIdx)
            val cy = lm.y(Contour.FACE, g.chinIdx)
            val m = chin * 0.06f * h
            // Center slightly inside the face so the neck line is not dragged.
            out.addTranslate(cx - g.eyX * 0.05f * h, cy - g.eyY * 0.05f * h, 0.22f * w, g.eyX * m, g.eyY * m)
        }

        // CHEEKBONE: narrow the cheekbones just below the eye line.
        val cheek = unipolar(state, BeautyFeature.CHEEKBONE) * presence
        if (cheek > 0f) {
            for (side in 0..1) {
                val inward = if (side == 0) 1f else -1f
                g.ovalSidePoint(side, 0.18f, scratch)
                val m = inward * cheek * 0.035f * w
                out.addTranslate(scratch[0], scratch[1], 0.22f * w, g.exX * m, g.exY * m)
            }
        }

        // FOREHEAD (bipolar): > 50 raises the hairline (taller forehead), < 50 lowers it.
        val forehead = bipolar(state, BeautyFeature.FOREHEAD) * presence
        if (forehead != 0f) {
            val lm = g.landmarks
            val tx = lm.x(Contour.FACE, g.ovalTopIdx)
            val ty = lm.y(Contour.FACE, g.ovalTopIdx)
            val fh = g.chinLevel - g.topLevel
            val m = -forehead * 0.04f * fh
            out.addTranslate(tx + g.eyX * 0.06f * fh, ty + g.eyY * 0.06f * fh, 0.36f * w, g.eyX * m, g.eyY * m)
        }

        // NOSE: pull the nose wings toward the nose center.
        val nose = unipolar(state, BeautyFeature.NOSE) * presence
        if (nose > 0f) {
            for (side in 0..1) {
                val vx = g.noseBottomX - g.noseWingX[side]
                val vy = g.noseBottomY - g.noseWingY[side]
                val len = sqrt(vx * vx + vy * vy).coerceAtLeast(1e-5f)
                val m = nose * 0.14f * g.noseWidth
                out.addTranslate(g.noseWingX[side], g.noseWingY[side], 0.6f * g.noseWidth, vx / len * m, vy / len * m)
            }
        }

        // EYE_SIZE: radial magnification around each eye.
        val eyeSize = unipolar(state, BeautyFeature.EYE_SIZE) * presence
        if (eyeSize > 0f) {
            for (side in 0..1) {
                out.addScale(g.eyeCenterX[side], g.eyeCenterY[side], g.eyeWidth[side] * 1.15f, eyeSize * 0.22f)
            }
        }

        // EYE_SHAPE (bipolar): > 50 lifts the outer corners (almond), < 50 opens the eye vertically (rounder).
        val eyeShape = bipolar(state, BeautyFeature.EYE_SHAPE) * presence
        if (eyeShape != 0f) {
            for (side in 0..1) {
                val c = g.eyeContour[side]
                val lm = g.landmarks
                if (eyeShape > 0f) {
                    val ox = lm.x(c, g.outerCornerIdx[side]); val oy = lm.y(c, g.outerCornerIdx[side])
                    val m = -eyeShape * 0.12f * g.eyeWidth[side]
                    out.addTranslate(ox, oy, 0.6f * g.eyeWidth[side], g.eyX * m, g.eyY * m)
                } else {
                    out.addAxisScale(g.eyeCenterX[side], g.eyeCenterY[side], g.eyeWidth[side] * 0.9f, g.eyX, g.eyY, -eyeShape * 0.25f)
                }
            }
        }
    }

    /** (fraction between eye line and chin, weight) samples for face slimming. */
    private val SlimSamples = listOf(0.45f to 0.8f, 0.75f to 1f)
}
