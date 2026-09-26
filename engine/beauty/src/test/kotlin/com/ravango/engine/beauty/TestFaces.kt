package com.ravango.engine.beauty

import com.ravango.engine.beauty.tracking.Contour
import com.ravango.engine.beauty.tracking.FaceLandmarks
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Synthetic, anatomically plausible face contours in frame space (x ∈ [0, aspect], y ∈ [0, 1], y down). */
object TestFaces {

    fun frontal(cx: Float = 0.5f, cy: Float = 0.5f, scale: Float = 1f): FaceLandmarks {
        val lm = FaceLandmarks()
        fun p(c: Int, i: Int, x: Float, y: Float) = lm.set(c, i, cx + (x - 0.5f) * scale, cy + (y - 0.5f) * scale)

        // Oval: starts at the top, clockwise on screen (towards +x first), like ML Kit.
        for (i in 0 until 36) {
            val a = -PI / 2 + 2 * PI * i / 36
            p(Contour.FACE, i, 0.5f + 0.2f * cos(a).toFloat(), 0.5f + 0.28f * sin(a).toFloat())
        }
        eye(lm, Contour.LEFT_EYE, 0.42f, 0.45f, cx, cy, scale)
        eye(lm, Contour.RIGHT_EYE, 0.58f, 0.45f, cx, cy, scale)
        for (i in 0 until 5) {
            val t = i / 4f
            p(Contour.LEFT_EYEBROW_TOP, i, 0.37f + 0.1f * t, 0.395f - 0.012f * sin(PI * t).toFloat())
            p(Contour.LEFT_EYEBROW_BOTTOM, i, 0.37f + 0.1f * t, 0.405f - 0.01f * sin(PI * t).toFloat())
            p(Contour.RIGHT_EYEBROW_TOP, i, 0.53f + 0.1f * t, 0.395f - 0.012f * sin(PI * t).toFloat())
            p(Contour.RIGHT_EYEBROW_BOTTOM, i, 0.53f + 0.1f * t, 0.405f - 0.01f * sin(PI * t).toFloat())
        }
        for (i in 0 until 11) {
            val t = i / 10f
            p(Contour.UPPER_LIP_TOP, i, 0.45f + 0.1f * t, 0.63f - 0.012f * sin(PI * t).toFloat())
        }
        for (i in 0 until 9) {
            val t = (i + 1) / 10f
            p(Contour.UPPER_LIP_BOTTOM, i, 0.45f + 0.1f * t, 0.635f)
            p(Contour.LOWER_LIP_TOP, i, 0.45f + 0.1f * t, 0.64f)
            p(Contour.LOWER_LIP_BOTTOM, i, 0.45f + 0.1f * t, 0.645f + 0.015f * sin(PI * t).toFloat())
        }
        p(Contour.NOSE_BRIDGE, 0, 0.5f, 0.45f)
        p(Contour.NOSE_BRIDGE, 1, 0.5f, 0.56f)
        p(Contour.NOSE_BOTTOM, 0, 0.475f, 0.575f)
        p(Contour.NOSE_BOTTOM, 1, 0.5f, 0.585f)
        p(Contour.NOSE_BOTTOM, 2, 0.525f, 0.575f)
        p(Contour.LEFT_CHEEK, 0, 0.4f, 0.55f)
        p(Contour.RIGHT_CHEEK, 0, 0.6f, 0.55f)
        return lm
    }

    /** Horizontal mirror of [lm] inside a frame of width [aspect] (like the front camera). */
    fun mirrored(lm: FaceLandmarks, aspect: Float): FaceLandmarks {
        val out = FaceLandmarks()
        for (i in lm.points.indices step 2) {
            out.points[i] = aspect - lm.points[i]
            out.points[i + 1] = lm.points[i + 1]
        }
        return out
    }

    private fun eye(lm: FaceLandmarks, c: Int, ex: Float, ey: Float, cx: Float, cy: Float, scale: Float) {
        for (i in 0 until 16) {
            val a = PI + 2 * PI * i / 16
            val x = ex + 0.03f * cos(a).toFloat()
            val y = ey + 0.012f * sin(a).toFloat()
            lm.set(c, i, cx + (x - 0.5f) * scale, cy + (y - 0.5f) * scale)
        }
    }
}
