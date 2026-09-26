package com.ravango.engine.beauty.geometry

import com.ravango.engine.beauty.tracking.Contour
import com.ravango.engine.beauty.tracking.FaceLandmarks
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Growable-once triangle list of (x, y, alpha) vertices in frame space. Reused every frame. */
class TriangleBuffer(capacityVertices: Int = 1536) {
    val data = FloatArray(capacityVertices * 3)
    var vertexCount = 0
        private set
    private val capacity = capacityVertices

    fun clear() { vertexCount = 0 }

    fun isEmpty() = vertexCount == 0

    fun vertex(x: Float, y: Float, a: Float) {
        if (vertexCount >= capacity) return
        val o = vertexCount * 3
        data[o] = x; data[o + 1] = y; data[o + 2] = a
        vertexCount++
    }

    fun triangle(x0: Float, y0: Float, a0: Float, x1: Float, y1: Float, a1: Float, x2: Float, y2: Float, a2: Float) {
        if (vertexCount + 3 > capacity) return
        vertex(x0, y0, a0); vertex(x1, y1, a1); vertex(x2, y2, a2)
    }
}

/**
 * Builds triangle geometry for every landmark-driven mask: the face oval and exclusion regions used by the skin
 * mask, the under-eye crescent, the inner mouth (teeth) and each makeup layer. Soft edges come from per-vertex
 * alpha gradients plus a small blur on the GPU.
 */
class MaskGeometry {
    private val triangulator = Triangulator(64)
    private val poly = FloatArray(64 * 2)
    private val tri = IntArray(64 * 3)
    private val tmp = FloatArray(2)
    private val tmp2 = FloatArray(2)

    // ---------------------------------------------------------------- face masks

    fun faceOval(g: FaceGeometry, out: TriangleBuffer) {
        val lm = g.landmarks
        var n = 0
        for (i in 0 until lm.size(Contour.FACE)) n = push(n, lm.x(Contour.FACE, i), lm.y(Contour.FACE, i))
        fillPolygon(n, 1f, out)
    }

    /** Eyes (expanded for lashes), brows and lips: regions that must never be smoothed or treated as skin. */
    fun exclusions(g: FaceGeometry, out: TriangleBuffer) {
        val lm = g.landmarks
        for (side in 0..1) {
            contourPolygonScaled(lm, g.eyeContour[side], 1.45f, out)
            browPolygon(g, side, 1.2f, out)
        }
        outerLips(g, out)
    }

    /** Soft crescent under each eye for dark-circle correction (alpha 0 at the corners and the lower edge). */
    fun underEye(g: FaceGeometry, out: TriangleBuffer) {
        val lm = g.landmarks
        for (side in 0..1) {
            val c = g.eyeContour[side]
            val ew = g.eyeWidth[side]
            val lid = g.lowerLid[side]
            val count = g.lowerLidCount[side]
            if (count < 2) continue
            var px0 = 0f; var py0 = 0f; var px1 = 0f; var py1 = 0f; var pa0 = 0f
            for (k in 0 until count) {
                val t = k / (count - 1f)
                val bump = sin(PI.toFloat() * t)
                val x = lm.x(c, lid[k]); val y = lm.y(c, lid[k])
                // Inner edge sits just below the lashes; outer edge follows the orbital bone.
                val inner = 0.07f * ew
                val outer = inner + (0.18f + 0.30f * bump) * ew
                val ix = x + g.eyX * inner; val iy = y + g.eyY * inner
                val ox = x + g.eyX * outer; val oy = y + g.eyY * outer
                val a = sqrt(bump)
                if (k > 0) {
                    out.triangle(px0, py0, pa0, ix, iy, a, px1, py1, 0f)
                    out.triangle(px1, py1, 0f, ix, iy, a, ox, oy, 0f)
                }
                px0 = ix; py0 = iy; px1 = ox; py1 = oy; pa0 = a
            }
        }
    }

    /** Polygon between the inner lip lines: where teeth are visible. */
    fun innerMouth(g: FaceGeometry, out: TriangleBuffer) {
        val lm = g.landmarks
        var n = pushLine(0, g, lm, Contour.UPPER_LIP_BOTTOM, forward = true)
        n = pushLine(n, g, lm, Contour.LOWER_LIP_TOP, forward = false)
        fillPolygon(n, 1f, out)
    }

    // ---------------------------------------------------------------- makeup

    /** Upper and lower lip bodies (the ring around the mouth opening). */
    fun lips(g: FaceGeometry, out: TriangleBuffer) {
        val lm = g.landmarks
        var n = pushLine(0, g, lm, Contour.UPPER_LIP_TOP, forward = true)
        n = pushLine(n, g, lm, Contour.UPPER_LIP_BOTTOM, forward = false)
        fillPolygon(n, 1f, out)

        n = push(0, g.mouthCornerX[0], g.mouthCornerY[0])
        n = pushLine(n, g, lm, Contour.LOWER_LIP_TOP, forward = true)
        n = push(n, g.mouthCornerX[1], g.mouthCornerY[1])
        n = pushLine(n, g, lm, Contour.LOWER_LIP_BOTTOM, forward = false)
        fillPolygon(n, 1f, out)
    }

    fun brows(g: FaceGeometry, out: TriangleBuffer) {
        for (side in 0..1) browPolygon(g, side, 1.05f, out)
    }

    /** Liner along the upper lid, thickening toward the outer corner, with a small wing. */
    fun eyeliner(g: FaceGeometry, out: TriangleBuffer) {
        val lm = g.landmarks
        for (side in 0..1) {
            val c = g.eyeContour[side]
            val ew = g.eyeWidth[side]
            val lid = g.upperLid[side]
            val count = g.upperLidCount[side]
            if (count < 2) continue
            var bx0 = 0f; var by0 = 0f; var tx0 = 0f; var ty0 = 0f
            for (k in 0 until count) {
                val t = k / (count - 1f)
                val x = lm.x(c, lid[k]); val y = lm.y(c, lid[k])
                val th = (0.02f + 0.06f * t) * ew
                val bx = x + g.eyX * 0.01f * ew; val by = y + g.eyY * 0.01f * ew
                val tx = x - g.eyX * th; val ty = y - g.eyY * th
                if (k > 0) quad(bx0, by0, 1f, tx0, ty0, 1f, bx, by, 1f, tx, ty, 1f, out)
                bx0 = bx; by0 = by; tx0 = tx; ty0 = ty
            }
            // Wing: from the outer corner outward and up.
            val outward = if (side == 0) -1f else 1f
            val ox = lm.x(c, g.outerCornerIdx[side]); val oy = lm.y(c, g.outerCornerIdx[side])
            val wx = ox + g.exX * outward * 0.3f * ew - g.eyX * 0.14f * ew
            val wy = oy + g.exY * outward * 0.3f * ew - g.eyY * 0.14f * ew
            out.triangle(bx0, by0, 1f, tx0, ty0, 1f, wx, wy, 0.9f)
        }
    }

    /** Lash band above the upper lid: dense at the lid, fading upward; strongest toward the outer corner. */
    fun eyelashes(g: FaceGeometry, out: TriangleBuffer) {
        val lm = g.landmarks
        for (side in 0..1) {
            val c = g.eyeContour[side]
            val ew = g.eyeWidth[side]
            val lid = g.upperLid[side]
            val count = g.upperLidCount[side]
            if (count < 2) continue
            val outward = if (side == 0) -1f else 1f
            var bx0 = 0f; var by0 = 0f; var tx0 = 0f; var ty0 = 0f; var a0 = 0f
            for (k in 0 until count) {
                val t = k / (count - 1f)
                val x = lm.x(c, lid[k]); val y = lm.y(c, lid[k])
                val len = (0.08f + 0.08f * t) * ew
                // Lashes flare outward near the outer corner.
                val flare = t * t * 0.08f * ew * outward
                val tx = x - g.eyX * len + g.exX * flare
                val ty = y - g.eyY * len + g.exY * flare
                val a = (0.35f + 0.65f * t).coerceAtMost(1f)
                if (k > 0) quad(bx0, by0, a0, tx0, ty0, 0f, x, y, a, tx, ty, 0f, out)
                bx0 = x; by0 = y; tx0 = tx; ty0 = ty; a0 = a
            }
        }
    }

    /** Gradient from the upper lid toward the brow. */
    fun eyeshadow(g: FaceGeometry, out: TriangleBuffer) {
        val lm = g.landmarks
        for (side in 0..1) {
            val c = g.eyeContour[side]
            val ew = g.eyeWidth[side]
            val lid = g.upperLid[side]
            val count = g.upperLidCount[side]
            if (count < 2) continue
            val gap = max(g.level(g.eyeCenterX[side], g.eyeCenterY[side]) - g.level(g.browCenterX[side], g.browCenterY[side]), 0.3f * ew)
            val outward = if (side == 0) -1f else 1f
            var bx0 = 0f; var by0 = 0f; var tx0 = 0f; var ty0 = 0f
            for (k in 0 until count) {
                val t = k / (count - 1f)
                val x = lm.x(c, lid[k]); val y = lm.y(c, lid[k])
                val hgt = gap * (0.45f + 0.3f * sin(PI.toFloat() * (0.3f + 0.7f * t)))
                val ext = t * t * 0.12f * ew * outward
                val tx = x - g.eyX * hgt + g.exX * ext
                val ty = y - g.eyY * hgt + g.exY * ext
                if (k > 0) quad(bx0, by0, 1f, tx0, ty0, 0f, x, y, 1f, tx, ty, 0f, out)
                bx0 = x; by0 = y; tx0 = tx; ty0 = ty
            }
        }
    }

    fun blush(g: FaceGeometry, out: TriangleBuffer) {
        val lm = g.landmarks
        val s = g.interocular
        for (side in 0..1) {
            val c = g.cheekContour[side]
            val outward = if (side == 0) -1f else 1f
            val cx = lm.x(c, 0) + g.exX * outward * 0.12f * s - g.eyX * 0.05f * s
            val cy = lm.y(c, 0) + g.exY * outward * 0.12f * s - g.eyY * 0.05f * s
            // Tilt slightly up toward the temple.
            val ax = g.exX * outward - g.eyX * 0.35f
            val ay = g.exY * outward - g.eyY * 0.35f
            ellipse(cx, cy, ax, ay, 0.42f * s, 0.28f * s, 1f, 0f, out)
        }
    }

    fun contour(g: FaceGeometry, out: TriangleBuffer) {
        val s = g.interocular
        for (side in 0..1) {
            // Cheek hollow: from the ear-side oval toward the mouth corner, under the cheekbone.
            g.ovalSidePoint(side, 0.42f, tmp)
            val dx = g.mouthCornerX[side] - tmp[0]; val dy = g.mouthCornerY[side] - tmp[1]
            val cx = tmp[0] + dx * 0.3f; val cy = tmp[1] + dy * 0.3f
            ellipse(cx, cy, dx, dy, 0.36f * s, 0.09f * s, 0.9f, 0f, out)

            // Jawline band inside the oval edge.
            val inward = if (side == 0) 1f else -1f
            var ex0 = 0f; var ey0 = 0f; var ix0 = 0f; var iy0 = 0f
            for (k in 0..5) {
                val f = 0.5f + 0.09f * k
                g.ovalSidePoint(side, f, tmp2)
                val ix = tmp2[0] + g.exX * inward * 0.13f * s - g.eyX * 0.05f * s
                val iy = tmp2[1] + g.exY * inward * 0.13f * s - g.eyY * 0.05f * s
                val edge = 0.75f * (1f - k / 6f)
                if (k > 0) quad(ex0, ey0, edge + 0.1f, ix0, iy0, 0f, tmp2[0], tmp2[1], edge, ix, iy, 0f, out)
                ex0 = tmp2[0]; ey0 = tmp2[1]; ix0 = ix; iy0 = iy
            }
        }
        // Nose sides: thin strips along the bridge.
        val bx = g.noseTipX - g.noseTopX; val by = g.noseTipY - g.noseTopY
        val blen = sqrt(bx * bx + by * by)
        if (blen > 1e-5f) {
            for (side in 0..1) {
                val o = (if (side == 0) -1f else 1f) * 0.42f * g.noseWidth
                val cx = g.noseTopX + bx * 0.55f + g.exX * o
                val cy = g.noseTopY + by * 0.55f + g.exY * o
                ellipse(cx, cy, bx, by, 0.5f * blen, 0.07f * s, 0.7f, 0f, out)
            }
        }
    }

    fun highlight(g: FaceGeometry, out: TriangleBuffer) {
        val lm = g.landmarks
        val s = g.interocular
        for (side in 0..1) {
            // Cheekbone tops: between the cheek center and the outer eye corner.
            val c = g.eyeContour[side]
            val ox = lm.x(c, g.outerCornerIdx[side]); val oy = lm.y(c, g.outerCornerIdx[side])
            val chx = lm.x(g.cheekContour[side], 0); val chy = lm.y(g.cheekContour[side], 0)
            val cx = chx + (ox - chx) * 0.55f; val cy = chy + (oy - chy) * 0.55f
            val outward = if (side == 0) -1f else 1f
            ellipse(cx, cy, g.exX * outward - g.eyX * 0.5f, g.exY * outward - g.eyY * 0.5f, 0.26f * s, 0.08f * s, 1f, 0f, out)
        }
        // Nose bridge.
        val bx = g.noseTipX - g.noseTopX; val by = g.noseTipY - g.noseTopY
        val blen = sqrt(bx * bx + by * by)
        if (blen > 1e-5f) ellipse(g.noseTopX + bx * 0.45f, g.noseTopY + by * 0.45f, bx, by, 0.5f * blen, 0.05f * s, 0.8f, 0f, out)
        // Cupid's bow: just above the center of the upper lip.
        val n = lm.size(Contour.UPPER_LIP_TOP)
        val mx = lm.x(Contour.UPPER_LIP_TOP, n / 2); val my = lm.y(Contour.UPPER_LIP_TOP, n / 2)
        ellipse(mx - g.eyX * 0.06f * s, my - g.eyY * 0.06f * s, g.exX, g.exY, 0.1f * s, 0.035f * s, 0.8f, 0f, out)
    }

    // ---------------------------------------------------------------- helpers

    private fun push(n: Int, x: Float, y: Float): Int {
        if (n >= 64) return n
        // Drop near-duplicate consecutive points (they create degenerate ears).
        if (n > 0) {
            val dx = poly[(n - 1) * 2] - x; val dy = poly[(n - 1) * 2 + 1] - y
            if (dx * dx + dy * dy < 1e-10f) return n
        }
        poly[n * 2] = x; poly[n * 2 + 1] = y
        return n + 1
    }

    /** Pushes a lip line ordered image-left → image-right ([forward]) or reversed. */
    private fun pushLine(n0: Int, g: FaceGeometry, lm: FaceLandmarks, contour: Int, forward: Boolean): Int {
        val size = lm.size(contour)
        val firstLeft = g.lateral(lm.x(contour, 0), lm.y(contour, 0)) <= g.lateral(lm.x(contour, size - 1), lm.y(contour, size - 1))
        val ascending = firstLeft == forward
        var n = n0
        for (k in 0 until size) {
            val i = if (ascending) k else size - 1 - k
            n = push(n, lm.x(contour, i), lm.y(contour, i))
        }
        return n
    }

    private fun contourPolygonScaled(lm: FaceLandmarks, contour: Int, scale: Float, out: TriangleBuffer) {
        val cx = FaceGeometry.centroidX(lm, contour); val cy = FaceGeometry.centroidY(lm, contour)
        var n = 0
        for (i in 0 until lm.size(contour)) {
            n = push(n, cx + (lm.x(contour, i) - cx) * scale, cy + (lm.y(contour, i) - cy) * scale)
        }
        fillPolygon(n, 1f, out)
    }

    private fun browPolygon(g: FaceGeometry, side: Int, scale: Float, out: TriangleBuffer) {
        val lm = g.landmarks
        val top = g.browTopContour[side]; val bottom = g.browBottomContour[side]
        var n = pushLine(0, g, lm, top, forward = true)
        n = pushLine(n, g, lm, bottom, forward = false)
        if (scale != 1f) {
            var cx = 0f; var cy = 0f
            for (i in 0 until n) { cx += poly[i * 2]; cy += poly[i * 2 + 1] }
            cx /= n; cy /= n
            for (i in 0 until n) {
                poly[i * 2] = cx + (poly[i * 2] - cx) * scale
                poly[i * 2 + 1] = cy + (poly[i * 2 + 1] - cy) * scale
            }
        }
        fillPolygon(n, 1f, out)
    }

    private fun outerLips(g: FaceGeometry, out: TriangleBuffer) {
        val lm = g.landmarks
        var n = pushLine(0, g, lm, Contour.UPPER_LIP_TOP, forward = true)
        n = pushLine(n, g, lm, Contour.LOWER_LIP_BOTTOM, forward = false)
        fillPolygon(n, 1f, out)
    }

    private fun fillPolygon(n: Int, alpha: Float, out: TriangleBuffer) {
        val count = triangulator.triangulate(poly, n, tri)
        for (t in 0 until count) {
            val a = tri[t * 3]; val b = tri[t * 3 + 1]; val c = tri[t * 3 + 2]
            out.triangle(
                poly[a * 2], poly[a * 2 + 1], alpha,
                poly[b * 2], poly[b * 2 + 1], alpha,
                poly[c * 2], poly[c * 2 + 1], alpha,
            )
        }
    }

    private fun quad(
        x0: Float, y0: Float, a0: Float, x1: Float, y1: Float, a1: Float,
        x2: Float, y2: Float, a2: Float, x3: Float, y3: Float, a3: Float,
        out: TriangleBuffer,
    ) {
        out.triangle(x0, y0, a0, x1, y1, a1, x2, y2, a2)
        out.triangle(x1, y1, a1, x3, y3, a3, x2, y2, a2)
    }

    /** Ellipse fan: [centerAlpha] at the center fading to [rimAlpha]; major axis along (ax, ay). */
    private fun ellipse(cx: Float, cy: Float, ax: Float, ay: Float, rx: Float, ry: Float, centerAlpha: Float, rimAlpha: Float, out: TriangleBuffer) {
        val len = sqrt(ax * ax + ay * ay)
        if (len < 1e-6f) return
        val ux = ax / len; val uy = ay / len
        val vx = -uy; val vy = ux
        var px = cx + ux * rx; var py = cy + uy * rx
        for (k in 1..SEGMENTS) {
            val ang = 2f * PI.toFloat() * k / SEGMENTS
            val ca = cos(ang); val sa = sin(ang)
            val qx = cx + ux * rx * ca + vx * ry * sa
            val qy = cy + uy * rx * ca + vy * ry * sa
            out.triangle(cx, cy, centerAlpha, px, py, rimAlpha, qx, qy, rimAlpha)
            px = qx; py = qy
        }
    }

    companion object {
        private const val SEGMENTS = 20
    }
}
