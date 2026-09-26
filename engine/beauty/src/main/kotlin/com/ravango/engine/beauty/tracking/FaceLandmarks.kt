package com.ravango.engine.beauty.tracking

/**
 * Layout of the ML Kit face contour points in a flat `FloatArray` of (x, y) pairs.
 *
 * Coordinates live in **frame space**: isotropic, normalized by frame height, origin top-left, y down:
 * `x ∈ [0, aspect]`, `y ∈ [0, 1]`, where `aspect = width / height`. Converting to GL texture coordinates is
 * `u = x / aspect`, `v = 1 - y`. Working in an isotropic space keeps circles circular for warps and masks.
 *
 * The point counts are the ones documented for ML Kit face contours; a detection whose contours do not match
 * is rejected rather than guessed.
 */
object Contour {
    // Ordered exactly as ML Kit FaceContour type ids 1..15.
    const val FACE = 0
    const val LEFT_EYEBROW_TOP = 1
    const val LEFT_EYEBROW_BOTTOM = 2
    const val RIGHT_EYEBROW_TOP = 3
    const val RIGHT_EYEBROW_BOTTOM = 4
    const val LEFT_EYE = 5
    const val RIGHT_EYE = 6
    const val UPPER_LIP_TOP = 7
    const val UPPER_LIP_BOTTOM = 8
    const val LOWER_LIP_TOP = 9
    const val LOWER_LIP_BOTTOM = 10
    const val NOSE_BRIDGE = 11
    const val NOSE_BOTTOM = 12
    const val LEFT_CHEEK = 13
    const val RIGHT_CHEEK = 14

    const val COUNT = 15

    /** Number of points per contour (ML Kit spec). */
    val SIZES = intArrayOf(36, 5, 5, 5, 5, 16, 16, 11, 9, 9, 9, 2, 3, 1, 1)

    /** Offset (in points) of each contour inside the flat array. */
    val OFFSETS: IntArray = IntArray(COUNT).also { o ->
        var acc = 0
        for (i in 0 until COUNT) { o[i] = acc; acc += SIZES[i] }
    }

    val TOTAL_POINTS: Int = SIZES.sum()
}

/** A mutable set of face contour points in frame space. Reused across frames (no per-frame allocation). */
class FaceLandmarks {
    val points = FloatArray(Contour.TOTAL_POINTS * 2)

    fun x(contour: Int, index: Int): Float = points[(Contour.OFFSETS[contour] + index) * 2]
    fun y(contour: Int, index: Int): Float = points[(Contour.OFFSETS[contour] + index) * 2 + 1]
    fun size(contour: Int): Int = Contour.SIZES[contour]

    fun set(contour: Int, index: Int, x: Float, y: Float) {
        val i = (Contour.OFFSETS[contour] + index) * 2
        points[i] = x
        points[i + 1] = y
    }

    fun copyFrom(other: FaceLandmarks) {
        System.arraycopy(other.points, 0, points, 0, points.size)
    }
}
