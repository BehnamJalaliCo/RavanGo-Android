package com.ravango.engine.beauty.effects

import com.ravango.engine.beauty.makeup.Geometry
import com.ravango.engine.beauty.makeup.UvCanvas
import com.ravango.engine.beauty.mesh.FaceLandmarkIndex as L
import com.ravango.engine.beauty.mesh.FaceMeshModel
import kotlin.math.exp
import kotlin.random.Random

/**
 * "Freckles & Blush" face paint, authored in the canonical face-mesh UV space (like a Lens Studio face mask) and
 * rendered on the tracked mesh, so it sticks to the skin through every expression.
 *
 * Channels: R freckles (seeded soft dots across the nose bridge and upper cheeks) · G blush (soft ellipses on the
 * apples of the cheeks) · B shimmer (a few glints on the cheekbones). Pure Kotlin and deterministic.
 */
object FacePaint {

    const val SIZE = 512

    fun generate(model: FaceMeshModel, size: Int = SIZE): ByteArray {
        val rgba = ByteArray(size * size * 4)
        val cv = UvCanvas(size)
        freckles(model, cv); cv.writeChannel(rgba, 0); cv.clear()
        blush(cv); cv.writeChannel(rgba, 1); cv.clear()
        shimmer(cv); cv.writeChannel(rgba, 2)
        for (i in 0 until size * size) rgba[i * 4 + 3] = -1
        return rgba
    }

    private fun uv(model: FaceMeshModel, i: Int) = model.u(i) to model.v(i)

    fun freckles(model: FaceMeshModel, cv: UvCanvas) {
        val rnd = Random(0xF4EC)
        val (bu, bv) = uv(model, 195) // lower nose bridge
        val (ru, rv) = uv(model, 50) // right cheek
        val (lu, lv) = uv(model, 280) // left cheek
        val rightEye = pts(model, L.RIGHT_EYE)
        val leftEye = pts(model, L.LEFT_EYE)
        var planted = 0
        var attempts = 0
        while (planted < 120 && attempts < 4000) {
            attempts++
            // A band from cheek to cheek through the nose bridge, densest in the middle of each cheek.
            val t = rnd.nextFloat() * 2f - 1f
            val cu: Float; val cvv: Float
            if (t < 0f) {
                val k = -t; cu = bu + (ru - bu) * k * 1.15f; cvv = bv + (rv - bv) * k + 0.01f
            } else {
                val k = t; cu = bu + (lu - bu) * k * 1.15f; cvv = bv + (lv - bv) * k + 0.01f
            }
            val u = cu + gauss(rnd) * 0.012f
            val v = cvv + gauss(rnd) * 0.03f
            if (Geometry.inside(rightEye, u, v) || Geometry.inside(leftEye, u, v)) continue
            val density = exp(-((kotlin.math.abs(t) - 0.55f) * (kotlin.math.abs(t) - 0.55f)) / 0.18f)
            if (rnd.nextFloat() > 0.35f + 0.65f * density) continue
            val r = 0.0022f + rnd.nextFloat() * 0.0034f
            cv.gaussian(u, v, r, r * (0.8f + rnd.nextFloat() * 0.4f), rnd.nextFloat() * 3f, 0.45f + rnd.nextFloat() * 0.55f)
            planted++
        }
    }

    fun blush(cv: UvCanvas) {
        cv.gaussian(0.25f, 0.468f, 0.1f, 0.066f, 0.35f, 1f)
        cv.gaussian(0.75f, 0.468f, 0.1f, 0.066f, -0.35f, 1f)
    }

    fun shimmer(cv: UvCanvas) {
        val rnd = Random(0x5A1)
        for (side in 0..1) {
            repeat(9) {
                val u = 0.2f + rnd.nextFloat() * 0.1f
                val v = 0.51f + rnd.nextFloat() * 0.05f
                cv.gaussian(if (side == 0) u else 1f - u, v, 0.004f, 0.004f, 0f, 0.6f + rnd.nextFloat() * 0.4f)
            }
        }
    }

    private fun pts(model: FaceMeshModel, indices: IntArray): FloatArray {
        val out = FloatArray(indices.size * 2)
        for (k in indices.indices) { out[k * 2] = model.u(indices[k]); out[k * 2 + 1] = model.v(indices[k]) }
        return out
    }

    private fun gauss(rnd: Random): Float {
        // Irwin–Hall approximation of a unit normal.
        var s = 0f
        repeat(4) { s += rnd.nextFloat() }
        return (s - 2f) * 1.73f
    }
}
