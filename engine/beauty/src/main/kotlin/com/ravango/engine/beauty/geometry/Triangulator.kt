package com.ravango.engine.beauty.geometry

/**
 * Ear-clipping triangulation of simple polygons (either winding), allocation-free after construction.
 * Face contours are small (≤ 40 points), so the O(n³) worst case is irrelevant.
 *
 * Degenerate input (collinear or duplicate points, slight self-intersections from noisy landmarks) never loops
 * forever: when no valid ear exists the most convex remaining vertex is clipped.
 */
class Triangulator(private val maxPoints: Int = 64) {
    private val remaining = IntArray(maxPoints)

    /**
     * Triangulates polygon [xy] (pairs) with [n] points. Writes vertex index triples into [outIndices] and returns
     * the number of triangles (n - 2 for n ≥ 3, else 0).
     */
    fun triangulate(xy: FloatArray, n: Int, outIndices: IntArray): Int {
        if (n < 3) return 0
        require(n <= maxPoints) { "Polygon too large: $n" }
        val ccw = signedArea(xy, n) > 0f
        for (i in 0 until n) remaining[i] = i
        var count = n
        var tris = 0
        var guard = 0
        var i = 0
        while (count > 3 && guard < n * n) {
            guard++
            var clipped = false
            for (step in 0 until count) {
                val ip = (i + count - 1) % count
                val inx = (i + 1) % count
                if (isEar(xy, ip, i, inx, count, ccw)) {
                    emit(outIndices, tris++, remaining[ip], remaining[i], remaining[inx])
                    removeAt(i, count)
                    count--
                    if (i >= count) i = 0
                    clipped = true
                    break
                }
                i = (i + 1) % count
            }
            if (!clipped) {
                // Fallback for degenerate polygons: clip the vertex with the largest convex turn.
                var best = 0
                var bestCross = -Float.MAX_VALUE
                for (k in 0 until count) {
                    val c = cross(xy, remaining[(k + count - 1) % count], remaining[k], remaining[(k + 1) % count]) * (if (ccw) 1f else -1f)
                    if (c > bestCross) { bestCross = c; best = k }
                }
                emit(outIndices, tris++, remaining[(best + count - 1) % count], remaining[best], remaining[(best + 1) % count])
                removeAt(best, count)
                count--
                i = 0
            }
        }
        if (count == 3) emit(outIndices, tris++, remaining[0], remaining[1], remaining[2])
        return tris
    }

    private fun isEar(xy: FloatArray, ip: Int, i: Int, inx: Int, count: Int, ccw: Boolean): Boolean {
        val a = remaining[ip]; val b = remaining[i]; val c = remaining[inx]
        val turn = cross(xy, a, b, c) * (if (ccw) 1f else -1f)
        if (turn <= EPS) return false
        val ax = xy[a * 2]; val ay = xy[a * 2 + 1]
        val bx = xy[b * 2]; val by = xy[b * 2 + 1]
        val cx = xy[c * 2]; val cy = xy[c * 2 + 1]
        for (k in 0 until count) {
            val p = remaining[k]
            if (p == a || p == b || p == c) continue
            if (pointInTriangle(xy[p * 2], xy[p * 2 + 1], ax, ay, bx, by, cx, cy)) return false
        }
        return true
    }

    private fun removeAt(index: Int, count: Int) {
        for (k in index until count - 1) remaining[k] = remaining[k + 1]
    }

    private fun emit(out: IntArray, tri: Int, a: Int, b: Int, c: Int) {
        out[tri * 3] = a; out[tri * 3 + 1] = b; out[tri * 3 + 2] = c
    }

    companion object {
        private const val EPS = 1e-12f

        fun signedArea(xy: FloatArray, n: Int): Float {
            var s = 0f
            for (i in 0 until n) {
                val j = (i + 1) % n
                s += xy[i * 2] * xy[j * 2 + 1] - xy[j * 2] * xy[i * 2 + 1]
            }
            return s * 0.5f
        }

        private fun cross(xy: FloatArray, a: Int, b: Int, c: Int): Float {
            val abx = xy[b * 2] - xy[a * 2]; val aby = xy[b * 2 + 1] - xy[a * 2 + 1]
            val bcx = xy[c * 2] - xy[b * 2]; val bcy = xy[c * 2 + 1] - xy[b * 2 + 1]
            return abx * bcy - aby * bcx
        }

        private fun pointInTriangle(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Boolean {
            val d1 = (px - bx) * (ay - by) - (ax - bx) * (py - by)
            val d2 = (px - cx) * (by - cy) - (bx - cx) * (py - cy)
            val d3 = (px - ax) * (cy - ay) - (cx - ax) * (py - ay)
            val hasNeg = d1 < 0f || d2 < 0f || d3 < 0f
            val hasPos = d1 > 0f || d2 > 0f || d3 > 0f
            return !(hasNeg && hasPos)
        }
    }
}
