package com.ravango.engine.beauty.mesh

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Weak-perspective head pose: the affine map `image ≈ A · [X, Y, Z, 1]` from canonical model coordinates (cm) to
 * frame space that best fits the tracked landmarks in the least-squares sense.
 *
 * The normal matrix depends only on the canonical model, so it is inverted once; each fit is then two 4×4
 * matrix–vector products over 468 points. The linear part carries scale, rotation (yaw/pitch/roll) and mirroring,
 * so displacements authored in canonical space project correctly onto a turned, tilted or mirrored face.
 */
class PoseFit(private val model: FaceMeshModel) {

    private val inverse = DoubleArray(16)
    private val bx = DoubleArray(4)
    private val by = DoubleArray(4)

    /** Row-major 2×4 affine map from the last successful [fit]. */
    val affine = FloatArray(8)

    /** Frame units per canonical centimetre. */
    var scale = 0f; private set

    /** 0 when the face looks straight at the camera, → 1 as it turns to profile (sine of the turn angle). */
    var turn = 0f; private set

    init {
        val m = DoubleArray(16)
        val n = model.vertexCount
        for (i in 0 until n) {
            val q0 = model.x(i).toDouble(); val q1 = model.y(i).toDouble(); val q2 = model.z(i).toDouble()
            val q = doubleArrayOf(q0, q1, q2, 1.0)
            for (r in 0 until 4) for (c in 0 until 4) m[r * 4 + c] += q[r] * q[c]
        }
        check(invert4(m, inverse)) { "Canonical model is degenerate" }
    }

    /**
     * Fits the pose to [landmarks] (x, y, z triplets in frame space; only x/y are used for the 468 mesh points).
     * Returns false for degenerate input (e.g. all points collapsed).
     */
    fun fit(landmarks: FloatArray): Boolean {
        bx.fill(0.0); by.fill(0.0)
        for (i in 0 until model.vertexCount) {
            val x = landmarks[i * 3].toDouble(); val y = landmarks[i * 3 + 1].toDouble()
            val q0 = model.x(i).toDouble(); val q1 = model.y(i).toDouble(); val q2 = model.z(i).toDouble()
            bx[0] += q0 * x; bx[1] += q1 * x; bx[2] += q2 * x; bx[3] += x
            by[0] += q0 * y; by[1] += q1 * y; by[2] += q2 * y; by[3] += y
        }
        for (r in 0 until 4) {
            var sx = 0.0; var sy = 0.0
            for (c in 0 until 4) { sx += inverse[r * 4 + c] * bx[c]; sy += inverse[r * 4 + c] * by[c] }
            affine[r] = sx.toFloat(); affine[4 + r] = sy.toFloat()
        }
        // Weak perspective: the linear part is s·(first two rows of a rotation), so each row has norm s, and the
        // image of the canonical Z axis (the face normal) has length s·sin(angle to the camera axis).
        val row0 = sqrt(affine[0] * affine[0] + affine[1] * affine[1] + affine[2] * affine[2])
        val row1 = sqrt(affine[4] * affine[4] + affine[5] * affine[5] + affine[6] * affine[6])
        val s = sqrt(row0 * row1)
        if (!(s > 1e-6f)) return false
        val zAxis = sqrt(affine[2] * affine[2] + affine[6] * affine[6])
        scale = s
        turn = (zAxis / s).coerceIn(0f, 1f)
        return true
    }

    /** Projects a canonical displacement (dx, dy, dz) into frame space; writes (x, y) into [out] at [offset]. */
    fun projectDelta(dx: Float, dy: Float, dz: Float, out: FloatArray, offset: Int) {
        out[offset] = affine[0] * dx + affine[1] * dy + affine[2] * dz
        out[offset + 1] = affine[4] * dx + affine[5] * dy + affine[6] * dz
    }

    companion object {
        /** Gauss–Jordan inversion of a row-major 4×4 matrix. */
        internal fun invert4(src: DoubleArray, dst: DoubleArray): Boolean {
            val a = src.copyOf()
            for (i in 0 until 16) dst[i] = if (i % 5 == 0) 1.0 else 0.0
            for (col in 0 until 4) {
                var pivot = col
                for (r in col + 1 until 4) if (abs(a[r * 4 + col]) > abs(a[pivot * 4 + col])) pivot = r
                if (abs(a[pivot * 4 + col]) < 1e-12) return false
                if (pivot != col) for (c in 0 until 4) {
                    var t = a[col * 4 + c]; a[col * 4 + c] = a[pivot * 4 + c]; a[pivot * 4 + c] = t
                    t = dst[col * 4 + c]; dst[col * 4 + c] = dst[pivot * 4 + c]; dst[pivot * 4 + c] = t
                }
                val inv = 1.0 / a[col * 4 + col]
                for (c in 0 until 4) { a[col * 4 + c] *= inv; dst[col * 4 + c] *= inv }
                for (r in 0 until 4) {
                    if (r == col) continue
                    val f = a[r * 4 + col]
                    if (f == 0.0) continue
                    for (c in 0 until 4) { a[r * 4 + c] -= f * a[col * 4 + c]; dst[r * 4 + c] -= f * dst[col * 4 + c] }
                }
            }
            return true
        }
    }
}
