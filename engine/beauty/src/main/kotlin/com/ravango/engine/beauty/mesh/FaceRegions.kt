package com.ravango.engine.beauty.mesh

/**
 * Landmark indices of the MediaPipe face mesh (478 points: 468 mesh vertices + 10 iris points).
 *
 * "Right"/"left" follow MediaPipe's naming: the subject's right side, which has negative x in the canonical model
 * and u < 0.5 in UV space. Loops are ordered around the contour and closed implicitly.
 */
object FaceLandmarkIndex {
    const val LANDMARK_COUNT = 478
    const val MESH_VERTEX_COUNT = 468

    const val NOSE_TIP = 1
    const val FOREHEAD_TOP = 10
    const val CHIN_BOTTOM = 152
    const val RIGHT_EYE_OUTER = 33
    const val RIGHT_EYE_INNER = 133
    const val LEFT_EYE_OUTER = 263
    const val LEFT_EYE_INNER = 362

    const val RIGHT_IRIS_CENTER = 468
    val RIGHT_IRIS_RING = intArrayOf(469, 470, 471, 472)
    const val LEFT_IRIS_CENTER = 473
    val LEFT_IRIS_RING = intArrayOf(474, 475, 476, 477)

    val LIPS_OUTER = intArrayOf(61, 185, 40, 39, 37, 0, 267, 269, 270, 409, 291, 375, 321, 405, 314, 17, 84, 181, 91, 146)
    val LIPS_INNER = intArrayOf(78, 191, 80, 81, 82, 13, 312, 311, 310, 415, 308, 324, 318, 402, 317, 14, 87, 178, 88, 95)
    /** Upper lip, outer contour from the right mouth corner to the left one. */
    val UPPER_LIP_OUTER = intArrayOf(61, 185, 40, 39, 37, 0, 267, 269, 270, 409, 291)

    val RIGHT_EYE = intArrayOf(33, 246, 161, 160, 159, 158, 157, 173, 133, 155, 154, 153, 145, 144, 163, 7)
    val LEFT_EYE = intArrayOf(263, 466, 388, 387, 386, 385, 384, 398, 362, 382, 381, 380, 374, 373, 390, 249)
    /** Upper lid contours from the inner corner to the outer corner. */
    val RIGHT_EYE_UPPER = intArrayOf(133, 173, 157, 158, 159, 160, 161, 246, 33)
    val LEFT_EYE_UPPER = intArrayOf(362, 398, 384, 385, 386, 387, 388, 466, 263)
    /** Lower lid contours from the inner corner to the outer corner. */
    val RIGHT_EYE_LOWER = intArrayOf(133, 155, 154, 153, 145, 144, 163, 7, 33)
    val LEFT_EYE_LOWER = intArrayOf(362, 382, 381, 380, 374, 373, 390, 249, 263)

    /** Brow contours from the head (near the nose) to the tail. */
    val RIGHT_BROW_UPPER = intArrayOf(107, 66, 105, 63, 70)
    val RIGHT_BROW_LOWER = intArrayOf(55, 65, 52, 53, 46)
    val LEFT_BROW_UPPER = intArrayOf(336, 296, 334, 293, 300)
    val LEFT_BROW_LOWER = intArrayOf(285, 295, 282, 283, 276)

    val FACE_OVAL = intArrayOf(
        10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288, 397, 365, 379, 378, 400, 377,
        152, 148, 176, 149, 150, 136, 172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109,
    )
}

/**
 * Triangle sets derived from the canonical triangulation, plus a padded ring of extra vertices around the face
 * oval used by the reshape warp so deformations fade into the background without a seam.
 *
 * Vertex layout of every per-face buffer: `0 until 468` mesh vertices, then [FACE_OVAL].size inner-ring vertices,
 * then the same number of outer-ring vertices.
 */
class FaceTopology(val model: FaceMeshModel) {

    val meshVertexCount: Int = model.vertexCount
    val ringSize: Int = FaceLandmarkIndex.FACE_OVAL.size
    val innerRingStart: Int = meshVertexCount
    val outerRingStart: Int = meshVertexCount + ringSize
    val totalVertexCount: Int = meshVertexCount + 2 * ringSize

    /** Triangles fully inside the mouth opening (teeth / tongue) — never painted, used for teeth whitening. */
    val mouthHoleTriangles: IntArray
    /** Triangles fully inside either eye opening — never painted, used for sclera/iris effects. */
    val eyeHoleTriangles: IntArray
    /** Every mesh triangle that is skin or lips (no eye or mouth openings): the "face mask" surface. */
    val surfaceTriangles: IntArray
    /** Every mesh triangle plus the padded ring: the warp surface. */
    val warpTriangles: IntArray
    /** +1 when triangles are counter-clockwise in UV space (u right, v up), −1 otherwise. */
    val uvWinding: Int

    init {
        require(meshVertexCount == FaceLandmarkIndex.MESH_VERTEX_COUNT) { "Expected 468 vertices, got $meshVertexCount" }
        val mouth = FaceLandmarkIndex.LIPS_INNER.toHashSet()
        val right = FaceLandmarkIndex.RIGHT_EYE.toHashSet()
        val left = FaceLandmarkIndex.LEFT_EYE.toHashSet()
        val t = model.triangles
        val mouthList = ArrayList<Int>()
        val eyeList = ArrayList<Int>()
        val surfaceList = ArrayList<Int>()
        var positive = 0
        for (i in 0 until model.triangleCount) {
            val a = t[i * 3]; val b = t[i * 3 + 1]; val c = t[i * 3 + 2]
            val target = when {
                a in mouth && b in mouth && c in mouth -> mouthList
                (a in right && b in right && c in right) || (a in left && b in left && c in left) -> eyeList
                else -> surfaceList
            }
            target += a; target += b; target += c
            if (signedArea(model.uvs, a, b, c) > 0f) positive++
        }
        uvWinding = if (positive * 2 >= model.triangleCount) 1 else -1
        mouthHoleTriangles = mouthList.toIntArray()
        eyeHoleTriangles = eyeList.toIntArray()
        surfaceTriangles = surfaceList.toIntArray()
        warpTriangles = t + ringTriangles()
    }

    /**
     * Quads (oval → inner ring → outer ring), wound like the mesh. Orientation is decided on a frontal layout built
     * from UVs (the ring extruded radially), so it matches the mesh however the OBJ happens to be wound.
     */
    private fun ringTriangles(): IntArray {
        val oval = FaceLandmarkIndex.FACE_OVAL
        val n = oval.size
        val layout = FloatArray(totalVertexCount * 2)
        System.arraycopy(model.uvs, 0, layout, 0, meshVertexCount * 2)
        var cu = 0f; var cv = 0f
        for (i in oval) { cu += model.u(i); cv += model.v(i) }
        cu /= n; cv /= n
        for (k in 0 until n) {
            val du = model.u(oval[k]) - cu
            val dv = model.v(oval[k]) - cv
            layout[(innerRingStart + k) * 2] = cu + du * INNER_RING_SCALE
            layout[(innerRingStart + k) * 2 + 1] = cv + dv * INNER_RING_SCALE
            layout[(outerRingStart + k) * 2] = cu + du * OUTER_RING_SCALE
            layout[(outerRingStart + k) * 2 + 1] = cv + dv * OUTER_RING_SCALE
        }
        val out = IntArray(n * 4 * 3)
        var o = 0
        fun tri(a: Int, b: Int, c: Int) {
            val s = signedArea(layout, a, b, c)
            if ((s > 0f) == (uvWinding > 0)) { out[o++] = a; out[o++] = b; out[o++] = c } else { out[o++] = a; out[o++] = c; out[o++] = b }
        }
        for (k in 0 until n) {
            val j = (k + 1) % n
            val a = oval[k]; val b = oval[j]
            val ia = innerRingStart + k; val ib = innerRingStart + j
            val oa = outerRingStart + k; val ob = outerRingStart + j
            tri(a, b, ib); tri(a, ib, ia)
            tri(ia, ib, ob); tri(ia, ob, oa)
        }
        return out
    }

    companion object {
        /** Ring radii relative to the oval, measured from the oval centroid. */
        const val INNER_RING_SCALE = 1.28f
        const val OUTER_RING_SCALE = 1.7f
        /** Fraction of an oval vertex's warp displacement carried by its inner-ring vertex (outer ring: 0). */
        const val INNER_RING_FOLLOW = 0.35f

        fun signedArea(xy: FloatArray, a: Int, b: Int, c: Int): Float {
            val ax = xy[a * 2]; val ay = xy[a * 2 + 1]
            val bx = xy[b * 2]; val by = xy[b * 2 + 1]
            val cx = xy[c * 2]; val cy = xy[c * 2 + 1]
            return 0.5f * ((bx - ax) * (cy - ay) - (cx - ax) * (by - ay))
        }
    }
}
