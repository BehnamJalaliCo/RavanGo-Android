package com.ravango.engine.beauty.makeup

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A single-channel float raster over UV space ([0,1]², v up; texel (i, j) is centred at ((i+½)/size, (j+½)/size)).
 * Shapes are drawn analytically with signed distances, so edges are anti-aliased and feathered by construction.
 * CPU-only and deterministic: the makeup atlas is generated once per process on a background thread.
 */
class UvCanvas(val size: Int) {
    val data = FloatArray(size * size)

    enum class Op { MAX, ADD, ERASE, MULTIPLY }

    fun clear() = data.fill(0f)

    operator fun get(u: Float, v: Float): Float {
        val i = (u * size).toInt().coerceIn(0, size - 1)
        val j = (v * size).toInt().coerceIn(0, size - 1)
        return data[j * size + i]
    }

    /** Sets texel coverage [c] with [op]. */
    private fun put(index: Int, c: Float, op: Op) {
        if (c.isNaN() || (c <= 0f && op != Op.MULTIPLY)) return
        data[index] = when (op) {
            Op.MAX -> max(data[index], c)
            Op.ADD -> min(1f, data[index] + c)
            Op.ERASE -> data[index] * (1f - c.coerceIn(0f, 1f))
            Op.MULTIPLY -> data[index] * c
        }
    }

    /** Visits texels whose centres lie in the box; [block] returns the coverage to apply. */
    inline fun forBox(u0: Float, v0: Float, u1: Float, v1: Float, block: (u: Float, v: Float) -> Float, op: Op) {
        val i0 = max(0, floor(u0 * size).toInt())
        val i1 = min(size - 1, floor(u1 * size).toInt())
        val j0 = max(0, floor(v0 * size).toInt())
        val j1 = min(size - 1, floor(v1 * size).toInt())
        val inv = 1f / size
        for (j in j0..j1) {
            val v = (j + 0.5f) * inv
            for (i in i0..i1) {
                val u = (i + 0.5f) * inv
                apply(j * size + i, block(u, v), op)
            }
        }
    }

    @PublishedApi internal fun apply(index: Int, c: Float, op: Op) = put(index, c, op)

    /**
     * Fills a closed polygon ([poly] = u, v pairs). Coverage is `smoothstep(-feather, +feather, sd + bias)` where
     * sd is the signed distance (positive inside); a positive [bias] grows the shape.
     */
    fun polygon(poly: FloatArray, feather: Float, value: Float = 1f, op: Op = Op.MAX, bias: Float = 0f) {
        val b = bounds(poly, feather + abs(bias))
        forBox(b[0], b[1], b[2], b[3], { u, v ->
            value * smoothstep(-feather, feather, Geometry.signedDistance(poly, u, v) + bias)
        }, op)
    }

    /**
     * Strokes an open polyline whose half-width varies along its normalized length t ∈ [0, 1] ([halfWidth]),
     * with soft edges of [feather] and an intensity profile [profile] along t.
     */
    fun stroke(
        pts: FloatArray,
        feather: Float,
        halfWidth: (Float) -> Float,
        profile: (Float) -> Float = { 1f },
        value: Float = 1f,
        op: Op = Op.MAX,
    ) {
        var maxW = 0f
        for (k in 0..10) maxW = max(maxW, halfWidth(k / 10f))
        val b = bounds(pts, maxW + feather)
        val hit = FloatArray(2)
        val lengths = Geometry.cumulativeLengths(pts)
        forBox(b[0], b[1], b[2], b[3], { u, v ->
            Geometry.distanceToPolyline(pts, lengths, u, v, hit)
            val t = hit[1]
            val w = halfWidth(t)
            value * profile(t) * (1f - smoothstep(w - feather, w + feather, hit[0]))
        }, op)
    }

    /** Gaussian ellipse centred at (cu, cv), radii (ru, rv) = 2σ, major axis at [angle] radians from +u. */
    fun gaussian(cu: Float, cv: Float, ru: Float, rv: Float, angle: Float, value: Float, op: Op = Op.MAX) {
        val c = cos(angle); val s = sin(angle)
        val reach = max(ru, rv) * 1.6f
        forBox(cu - reach, cv - reach, cu + reach, cv + reach, { u, v ->
            val du = u - cu; val dv = v - cv
            val a = (du * c + dv * s) / ru
            val bb = (-du * s + dv * c) / rv
            val r2 = a * a + bb * bb
            if (r2 > 2.56f) 0f else value * exp(-2f * r2) * (1f - smoothstep(1.8f, 2.56f, r2))
        }, op)
    }

    /** Separable box blur (3 passes ≈ Gaussian) with radius in texels. */
    fun blur(radius: Int) {
        if (radius <= 0) return
        val tmp = FloatArray(data.size)
        repeat(3) {
            boxPass(data, tmp, radius, horizontal = true)
            boxPass(tmp, data, radius, horizontal = false)
        }
    }

    private fun boxPass(src: FloatArray, dst: FloatArray, r: Int, horizontal: Boolean) {
        val n = size
        val norm = 1f / (2 * r + 1)
        for (line in 0 until n) {
            var acc = 0f
            for (k in -r..r) acc += sample(src, line, k.coerceIn(0, n - 1), horizontal)
            for (p in 0 until n) {
                dst[if (horizontal) line * n + p else p * n + line] = acc * norm
                acc += sample(src, line, min(n - 1, p + r + 1), horizontal) - sample(src, line, max(0, p - r), horizontal)
            }
        }
    }

    private fun sample(src: FloatArray, line: Int, p: Int, horizontal: Boolean) =
        if (horizontal) src[line * size + p] else src[p * size + line]

    /** Writes the canvas into byte channel [channel] (0..3) of an RGBA image of the same size. */
    fun writeChannel(rgba: ByteArray, channel: Int) {
        for (i in data.indices) {
            rgba[i * 4 + channel] = (data[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
        }
    }

    companion object {
        fun smoothstep(e0: Float, e1: Float, x: Float): Float {
            if (e0 == e1) return if (x < e0) 0f else 1f
            val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        fun bounds(pts: FloatArray, pad: Float): FloatArray {
            var u0 = Float.MAX_VALUE; var v0 = Float.MAX_VALUE; var u1 = -Float.MAX_VALUE; var v1 = -Float.MAX_VALUE
            for (k in 0 until pts.size / 2) {
                u0 = min(u0, pts[k * 2]); u1 = max(u1, pts[k * 2])
                v0 = min(v0, pts[k * 2 + 1]); v1 = max(v1, pts[k * 2 + 1])
            }
            return floatArrayOf(u0 - pad, v0 - pad, u1 + pad, v1 + pad)
        }
    }
}

/** 2D geometry helpers on flat (u, v) arrays. */
object Geometry {

    /** Even-odd point-in-polygon test. */
    fun inside(poly: FloatArray, u: Float, v: Float): Boolean {
        val n = poly.size / 2
        var c = false
        var j = n - 1
        for (i in 0 until n) {
            val ui = poly[i * 2]; val vi = poly[i * 2 + 1]
            val uj = poly[j * 2]; val vj = poly[j * 2 + 1]
            if ((vi > v) != (vj > v) && u < (uj - ui) * (v - vi) / (vj - vi) + ui) c = !c
            j = i
        }
        return c
    }

    /** Signed distance to a closed polygon's boundary: positive inside. */
    fun signedDistance(poly: FloatArray, u: Float, v: Float): Float {
        val n = poly.size / 2
        var best = Float.MAX_VALUE
        var j = n - 1
        for (i in 0 until n) {
            val d = segmentDistance(poly[j * 2], poly[j * 2 + 1], poly[i * 2], poly[i * 2 + 1], u, v, null)
            if (d < best) best = d
            j = i
        }
        return if (inside(poly, u, v)) best else -best
    }

    fun cumulativeLengths(pts: FloatArray): FloatArray {
        val n = pts.size / 2
        val out = FloatArray(n)
        for (k in 1 until n) {
            val du = pts[k * 2] - pts[k * 2 - 2]
            val dv = pts[k * 2 + 1] - pts[k * 2 - 1]
            out[k] = out[k - 1] + sqrt(du * du + dv * dv)
        }
        return out
    }

    /**
     * Distance from (u, v) to an open polyline. Writes (distance, t) into [out], t being the normalized arc length
     * of the closest point.
     */
    fun distanceToPolyline(pts: FloatArray, lengths: FloatArray, u: Float, v: Float, out: FloatArray) {
        val n = pts.size / 2
        val total = max(lengths[n - 1], 1e-9f)
        var best = Float.MAX_VALUE
        var bestT = 0f
        for (k in 0 until n - 1) {
            val au = pts[k * 2]; val av = pts[k * 2 + 1]
            val du = pts[k * 2 + 2] - au; val dv = pts[k * 2 + 3] - av
            val len2 = du * du + dv * dv
            val t = if (len2 <= 1e-12f) 0f else (((u - au) * du + (v - av) * dv) / len2).coerceIn(0f, 1f)
            val pu = au + du * t - u; val pv = av + dv * t - v
            val d = sqrt(pu * pu + pv * pv)
            if (d < best) {
                best = d
                bestT = (lengths[k] + (lengths[k + 1] - lengths[k]) * t) / total
            }
        }
        out[0] = best
        out[1] = bestT
    }

    /** Distance from (u, v) to segment a–b; the clamped projection parameter goes to [param][0] if given. */
    fun segmentDistance(au: Float, av: Float, bu: Float, bv: Float, u: Float, v: Float, param: FloatArray?): Float {
        val du = bu - au; val dv = bv - av
        val len2 = du * du + dv * dv
        val t = if (len2 <= 1e-12f) 0f else (((u - au) * du + (v - av) * dv) / len2).coerceIn(0f, 1f)
        param?.set(0, t)
        val pu = au + du * t - u; val pv = av + dv * t - v
        return sqrt(pu * pu + pv * pv)
    }

    /** Centripetal-free uniform Catmull–Rom resampling of an open polyline ([steps] samples per segment). */
    fun smoothOpen(pts: FloatArray, steps: Int): FloatArray = catmullRom(pts, steps, closed = false)

    /** Uniform Catmull–Rom resampling of a closed loop. */
    fun smoothClosed(pts: FloatArray, steps: Int): FloatArray = catmullRom(pts, steps, closed = true)

    private fun catmullRom(pts: FloatArray, steps: Int, closed: Boolean): FloatArray {
        val n = pts.size / 2
        val segments = if (closed) n else n - 1
        val out = FloatArray((segments * steps + if (closed) 0 else 1) * 2)
        var o = 0
        fun p(i: Int, c: Int): Float {
            val k = if (closed) ((i % n) + n) % n else i.coerceIn(0, n - 1)
            return pts[k * 2 + c]
        }
        for (s in 0 until segments) {
            for (k in 0 until steps) {
                val t = k / steps.toFloat()
                for (c in 0..1) {
                    val p0 = p(s - 1, c); val p1 = p(s, c); val p2 = p(s + 1, c); val p3 = p(s + 2, c)
                    out[o + c] = 0.5f * (2f * p1 + (-p0 + p2) * t + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t * t +
                        (-p0 + 3f * p1 - 3f * p2 + p3) * t * t * t)
                }
                o += 2
            }
        }
        if (!closed) { out[o] = pts[(n - 1) * 2]; out[o + 1] = pts[(n - 1) * 2 + 1] }
        return out
    }
}
