package com.ravango.engine.beauty.mesh

import com.ravango.core.model.BeautyFeature
import com.ravango.core.model.BeautyState
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Reshape amounts in −1..1 (bipolar features, 0 = neutral) or 0..1 (unipolar), derived from [BeautyState].
 * Mutable and reused so the GL thread never allocates.
 */
class ReshapeParams {
    var slim = 0f
    var jaw = 0f
    var chin = 0f
    var cheekbone = 0f
    var forehead = 0f
    var nose = 0f
    var eyeSize = 0f
    var eyeShape = 0f

    val isNeutral: Boolean
        get() = slim == 0f && jaw == 0f && chin == 0f && cheekbone == 0f && forehead == 0f && nose == 0f &&
            eyeSize == 0f && eyeShape == 0f

    fun set(state: BeautyState) {
        fun uni(f: BeautyFeature) = state.intensity(f).coerceIn(0, 100) / 100f
        fun bi(f: BeautyFeature) = (state.intensity(f).coerceIn(0, 100) - 50) / 50f
        slim = uni(BeautyFeature.FACE_SLIM)
        jaw = bi(BeautyFeature.JAW)
        chin = bi(BeautyFeature.CHIN)
        cheekbone = uni(BeautyFeature.CHEEKBONE)
        forehead = bi(BeautyFeature.FOREHEAD)
        nose = uni(BeautyFeature.NOSE)
        eyeSize = uni(BeautyFeature.EYE_SIZE)
        eyeShape = bi(BeautyFeature.EYE_SHAPE)
    }

    fun sameAs(o: ReshapeParams): Boolean =
        slim == o.slim && jaw == o.jaw && chin == o.chin && cheekbone == o.cheekbone && forehead == o.forehead &&
            nose == o.nose && eyeSize == o.eyeSize && eyeShape == o.eyeShape

    fun copyFrom(o: ReshapeParams) {
        slim = o.slim; jaw = o.jaw; chin = o.chin; cheekbone = o.cheekbone
        forehead = o.forehead; nose = o.nose; eyeSize = o.eyeSize; eyeShape = o.eyeShape
    }
}

/**
 * Face Liquify / Face Stretch as a displacement field over the canonical face model.
 *
 * Every feature is a smooth region weight (products of smoothsteps over canonical coordinates, in cm) times a
 * direction, so the field is authored once in a head-fixed frame and later projected onto the tracked face by
 * [PoseFit]: slimming stays "inwards along the face" however the head is turned or tilted. Amplitudes are small
 * fractions of local distances, keeping the field's Jacobian positive (no folds) at full intensity; [MeshWarp]
 * still guards against folds in image space.
 */
class ReshapeField(private val model: FaceMeshModel) {

    /**
     * Writes a canonical (dx, dy) displacement per mesh vertex into [out] (size ≥ 2 × vertexCount).
     * Returns true when any vertex moves.
     */
    fun compute(p: ReshapeParams, out: FloatArray): Boolean {
        var any = false
        for (i in 0 until model.vertexCount) {
            val x = model.x(i)
            val y = model.y(i)
            val ax = abs(x)
            val sx = sign(x)
            var dx = 0f
            var dy = 0f

            if (p.slim != 0f) {
                // Lower face (below the cheekbones, down to the jaw) moves towards the midline, most at the sides.
                val w = smoothstep(3.0f, -1.5f, y) * smoothstep(1.2f, 6.2f, ax) * (1f - 0.35f * smoothstep(-7.5f, -9.4f, y))
                dx -= x * 0.095f * p.slim * w
            }
            if (p.jaw != 0f) {
                // Jaw angles widen/narrow; the chin tip is left alone.
                val w = bump(y, -5.8f, 3.4f) * smoothstep(2.2f, 5.6f, ax)
                dx += x * 0.075f * p.jaw * w
            }
            if (p.chin != 0f) {
                // Chin region moves down (longer) or up (shorter); fades out towards the jaw angles and the lips.
                val w = smoothstep(-5.4f, -8.6f, y) * (1f - smoothstep(2.2f, 5.2f, ax))
                dy -= 0.75f * p.chin * w
            }
            if (p.cheekbone != 0f) {
                val w = bump(y, 0.6f, 2.6f) * smoothstep(4.4f, 7.2f, ax)
                dx -= x * 0.06f * p.cheekbone * w
            }
            if (p.forehead != 0f) {
                // Hairline up (taller) or down (shorter), proportionally to the height above the brows.
                val w = smoothstep(4.6f, 8.3f, y)
                dy += 0.7f * p.forehead * w
            }
            if (p.nose != 0f) {
                // Narrow the nose wings and bridge.
                val w = (1f - smoothstep(1.0f, 2.7f, ax)) * smoothstep(3.6f, 1.2f, y) * (1f - smoothstep(-1.9f, -3.1f, y))
                dx -= x * 0.24f * p.nose * w
            }
            if (p.eyeSize != 0f || p.eyeShape != 0f) {
                val cx = sx * EYE_CENTER_X
                val ox = x - cx
                val oy = y - EYE_CENTER_Y
                val r = sqrt(ox * ox + oy * oy * 1.6f)
                val w = 1f - smoothstep(1.3f, 3.1f, r)
                if (w > 0f) {
                    if (p.eyeSize != 0f) {
                        dx += ox * 0.15f * p.eyeSize * w
                        dy += oy * 0.15f * p.eyeSize * w
                    }
                    if (p.eyeShape > 0f) {
                        // Lifted: raise the outer corner (cat-eye), keep the inner corner.
                        val outer = smoothstep(-0.4f, 1.4f, ox * sx)
                        dy += 0.28f * p.eyeShape * w * outer
                    } else if (p.eyeShape < 0f) {
                        // Rounder: open the lids vertically around the eye centre.
                        dy += oy * 0.28f * -p.eyeShape * w
                    }
                }
            }
            out[i * 2] = dx
            out[i * 2 + 1] = dy
            if (dx != 0f || dy != 0f) any = true
        }
        return any
    }

    companion object {
        /** Canonical eye centre (cm); the right eye is at −x. */
        const val EYE_CENTER_X = 3.15f
        const val EYE_CENTER_Y = 2.65f

        fun smoothstep(e0: Float, e1: Float, x: Float): Float {
            val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        /** Smooth bump: 1 at [center], 0 beyond ±[halfWidth]. */
        fun bump(x: Float, center: Float, halfWidth: Float): Float {
            val t = (1f - abs(x - center) / halfWidth).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}

/**
 * Per-frame reshape geometry for one face: projects the canonical displacement field onto the tracked mesh,
 * extends it to the padded ring, attenuates it for strongly turned heads and clamps it so no triangle folds.
 */
class MeshWarp(private val topology: FaceTopology) {

    private val n = topology.totalVertexCount
    private val disp = FloatArray(n * 2)

    /** Scale applied by the last [apply] after fold protection (1 = unclamped). */
    var lastClamp = 1f; private set

    /**
     * @param source per-vertex (x, y, z) of the undeformed mesh + ring in frame space.
     * @param canonical canonical (dx, dy) per mesh vertex from [ReshapeField.compute].
     * @param weight overall amount (face presence), 0..1.
     * @param out deformed (x, y, z) per vertex; z is copied from [source].
     */
    fun apply(source: FloatArray, canonical: FloatArray, pose: PoseFit, weight: Float, out: FloatArray) {
        val m = topology.meshVertexCount
        // Beyond ~55° of turn the far side is foreshortened to a sliver: fade the effect out instead of folding it.
        val k = weight * (1f - ReshapeField.smoothstep(0.62f, 0.9f, pose.turn))
        for (i in 0 until m) pose.projectDelta(canonical[i * 2] * k, canonical[i * 2 + 1] * k, 0f, disp, i * 2)
        val oval = FaceLandmarkIndex.FACE_OVAL
        for (r in oval.indices) {
            val src = oval[r] * 2
            val inner = (topology.innerRingStart + r) * 2
            val outer = (topology.outerRingStart + r) * 2
            disp[inner] = disp[src] * FaceTopology.INNER_RING_FOLLOW
            disp[inner + 1] = disp[src + 1] * FaceTopology.INNER_RING_FOLLOW
            disp[outer] = 0f
            disp[outer + 1] = 0f
        }
        val scale = foldSafeScale(source, disp, topology.warpTriangles, MIN_AREA_RATIO)
        lastClamp = scale
        for (i in 0 until n) {
            out[i * 3] = source[i * 3] + disp[i * 2] * scale
            out[i * 3 + 1] = source[i * 3 + 1] + disp[i * 2 + 1] * scale
            out[i * 3 + 2] = source[i * 3 + 2]
        }
    }

    companion object {
        /** A triangle may shrink to this fraction of its area, never flip. */
        const val MIN_AREA_RATIO = 0.3f

        /**
         * Largest s ∈ [0, 1] such that displacing every vertex by s·disp keeps each front-facing triangle at
         * ≥ [minRatio] of its area with unchanged orientation. Triangles already back-facing or degenerate (the far
         * side of a turned head) are ignored. Bisection, 5 steps.
         */
        fun foldSafeScale(source: FloatArray, disp: FloatArray, triangles: IntArray, minRatio: Float): Float {
            // Majority orientation of the undeformed mesh = front-facing.
            var pos = 0
            var total = 0f
            val count = triangles.size / 3
            for (t in 0 until count) {
                val a = area(source, disp, triangles, t, 0f)
                if (a > 0f) pos++
                total += abs(a)
            }
            if (count == 0 || total <= 0f) return 1f
            val front = if (pos * 2 >= count) 1f else -1f
            // Slivers (closed eyelids, a closed mouth, the far side of a turned face) carry no visible image.
            val minArea = total / count * 0.05f
            if (worstRatio(source, disp, triangles, front, 1f, minArea) >= minRatio) return 1f
            var lo = 0f
            var hi = 1f
            for (step in 0 until 5) {
                val mid = (lo + hi) * 0.5f
                if (worstRatio(source, disp, triangles, front, mid, minArea) >= minRatio) lo = mid else hi = mid
            }
            return lo
        }

        private fun worstRatio(source: FloatArray, disp: FloatArray, triangles: IntArray, front: Float, s: Float, minArea: Float): Float {
            var worst = Float.MAX_VALUE
            for (t in 0 until triangles.size / 3) {
                val a0 = area(source, disp, triangles, t, 0f) * front
                if (a0 <= minArea) continue
                val a1 = area(source, disp, triangles, t, s) * front
                val r = a1 / a0
                if (r < worst) worst = r
            }
            return worst
        }

        private fun area(p: FloatArray, d: FloatArray, tri: IntArray, t: Int, s: Float): Float {
            val a = tri[t * 3]; val b = tri[t * 3 + 1]; val c = tri[t * 3 + 2]
            val ax = p[a * 3] + d[a * 2] * s; val ay = p[a * 3 + 1] + d[a * 2 + 1] * s
            val bx = p[b * 3] + d[b * 2] * s; val by = p[b * 3 + 1] + d[b * 2 + 1] * s
            val cx = p[c * 3] + d[c * 2] * s; val cy = p[c * 3 + 1] + d[c * 2 + 1] * s
            return 0.5f * ((bx - ax) * (cy - ay) - (cx - ax) * (by - ay))
        }
    }
}
