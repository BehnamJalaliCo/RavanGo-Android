package com.ravango.engine.beauty.geometry

import com.ravango.engine.beauty.tracking.Contour
import com.ravango.engine.beauty.tracking.FaceLandmarks
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Face measurements derived from contour points in frame space (isotropic, y down).
 *
 * Everything is derived geometrically (image-left / image-right, "up" = towards the forehead) instead of trusting
 * ML Kit's left/right labels, so the result is identical for mirrored (front camera) and non-mirrored frames and
 * for rolled heads. Instances are reused: [update] overwrites all fields without allocating.
 */
class FaceGeometry {
    /** Unit axis from the image-left eye to the image-right eye. */
    var exX = 1f; var exY = 0f
    /** Unit axis pointing down the face (towards the chin), perpendicular to [exX]/[exY]. */
    var eyX = 0f; var eyY = 1f

    var centerX = 0f; var centerY = 0f
    /** Distance between eye centers: the base unit for all effect sizes. */
    var interocular = 0f
    var faceWidth = 0f
    var faceHeight = 0f

    /** Eye data per side (0 = image-left, 1 = image-right). */
    val eyeContour = IntArray(2)
    val browTopContour = IntArray(2)
    val browBottomContour = IntArray(2)
    val cheekContour = IntArray(2)
    val eyeCenterX = FloatArray(2); val eyeCenterY = FloatArray(2)
    val eyeWidth = FloatArray(2)
    val innerCornerIdx = IntArray(2)
    val outerCornerIdx = IntArray(2)
    /** Upper eyelid point indices (into the eye contour), ordered inner → outer corner inclusive. */
    val upperLid = Array(2) { IntArray(Contour.SIZES[Contour.LEFT_EYE]) }
    val upperLidCount = IntArray(2)
    val lowerLid = Array(2) { IntArray(Contour.SIZES[Contour.LEFT_EYE]) }
    val lowerLidCount = IntArray(2)
    val browCenterX = FloatArray(2); val browCenterY = FloatArray(2)

    /** Face oval: index of the top-most (forehead) and bottom-most (chin) points along the face axis. */
    var ovalTopIdx = 0
    var chinIdx = 0
    var topLevel = 0f
    var chinLevel = 0f
    var eyeLevel = 0f

    val mouthCornerX = FloatArray(2); val mouthCornerY = FloatArray(2)
    var mouthCenterX = 0f; var mouthCenterY = 0f
    var mouthWidth = 0f

    var noseTopX = 0f; var noseTopY = 0f
    var noseTipX = 0f; var noseTipY = 0f
    val noseWingX = FloatArray(2); val noseWingY = FloatArray(2)
    var noseBottomX = 0f; var noseBottomY = 0f
    var noseWidth = 0f

    lateinit var landmarks: FaceLandmarks
        private set

    /** Level (projection on the face axis) of a point. */
    fun level(x: Float, y: Float): Float = x * eyX + y * eyY

    /** Lateral position (projection on the eye axis) of a point. */
    fun lateral(x: Float, y: Float): Float = x * exX + y * exY

    fun update(lm: FaceLandmarks) {
        landmarks = lm
        // Eyes: centroids, then sort image-left / image-right.
        val c0x = centroidX(lm, Contour.LEFT_EYE); val c0y = centroidY(lm, Contour.LEFT_EYE)
        val c1x = centroidX(lm, Contour.RIGHT_EYE); val c1y = centroidY(lm, Contour.RIGHT_EYE)
        val swap = c0x > c1x
        eyeContour[0] = if (swap) Contour.RIGHT_EYE else Contour.LEFT_EYE
        eyeContour[1] = if (swap) Contour.LEFT_EYE else Contour.RIGHT_EYE
        eyeCenterX[0] = if (swap) c1x else c0x; eyeCenterY[0] = if (swap) c1y else c0y
        eyeCenterX[1] = if (swap) c0x else c1x; eyeCenterY[1] = if (swap) c0y else c1y

        var dx = eyeCenterX[1] - eyeCenterX[0]
        var dy = eyeCenterY[1] - eyeCenterY[0]
        interocular = max(sqrt(dx * dx + dy * dy), 1e-4f)
        dx /= interocular; dy /= interocular
        exX = dx; exY = dy
        eyX = -dy; eyY = dx

        centerX = centroidX(lm, Contour.FACE); centerY = centroidY(lm, Contour.FACE)

        // Face oval extents.
        var minL = Float.MAX_VALUE; var maxL = -Float.MAX_VALUE
        var minA = Float.MAX_VALUE; var maxA = -Float.MAX_VALUE
        for (i in 0 until lm.size(Contour.FACE)) {
            val x = lm.x(Contour.FACE, i); val y = lm.y(Contour.FACE, i)
            val l = level(x, y); val a = lateral(x, y)
            if (l < minL) { minL = l; ovalTopIdx = i }
            if (l > maxL) { maxL = l; chinIdx = i }
            if (a < minA) minA = a
            if (a > maxA) maxA = a
        }
        topLevel = minL; chinLevel = maxL
        faceHeight = max(maxL - minL, 1e-4f)
        faceWidth = max(maxA - minA, 1e-4f)
        eyeLevel = (level(eyeCenterX[0], eyeCenterY[0]) + level(eyeCenterX[1], eyeCenterY[1])) * 0.5f

        for (side in 0..1) deriveEye(lm, side)
        deriveBrows(lm)
        deriveMouth(lm)
        deriveNose(lm)
        // Cheeks: sort by lateral position.
        val lc = lateral(lm.x(Contour.LEFT_CHEEK, 0), lm.y(Contour.LEFT_CHEEK, 0))
        val rc = lateral(lm.x(Contour.RIGHT_CHEEK, 0), lm.y(Contour.RIGHT_CHEEK, 0))
        cheekContour[0] = if (lc <= rc) Contour.LEFT_CHEEK else Contour.RIGHT_CHEEK
        cheekContour[1] = if (lc <= rc) Contour.RIGHT_CHEEK else Contour.LEFT_CHEEK
    }

    private fun deriveEye(lm: FaceLandmarks, side: Int) {
        val c = eyeContour[side]
        val n = lm.size(c)
        var minI = 0; var maxI = 0
        var minA = Float.MAX_VALUE; var maxA = -Float.MAX_VALUE
        for (i in 0 until n) {
            val a = lateral(lm.x(c, i), lm.y(c, i))
            if (a < minA) { minA = a; minI = i }
            if (a > maxA) { maxA = a; maxI = i }
        }
        // Image-left eye: outer corner is the image-left extreme; image-right eye: the image-right extreme.
        val inner = if (side == 0) maxI else minI
        val outer = if (side == 0) minI else maxI
        innerCornerIdx[side] = inner
        outerCornerIdx[side] = outer
        eyeWidth[side] = max(dist(lm.x(c, inner), lm.y(c, inner), lm.x(c, outer), lm.y(c, outer)), 1e-4f)

        // Two arcs from inner to outer: forward (+1) and backward (-1) around the closed contour.
        val fwdLevel = arcMeanLevel(lm, c, inner, outer, +1)
        val bwdLevel = arcMeanLevel(lm, c, inner, outer, -1)
        val upperDir = if (fwdLevel < bwdLevel) +1 else -1
        upperLidCount[side] = fillArc(n, inner, outer, upperDir, upperLid[side])
        lowerLidCount[side] = fillArc(n, inner, outer, -upperDir, lowerLid[side])
    }

    private fun arcMeanLevel(lm: FaceLandmarks, c: Int, from: Int, to: Int, dir: Int): Float {
        val n = lm.size(c)
        var i = Math.floorMod(from + dir, n)
        var sum = 0f; var count = 0
        while (i != to && count < n) {
            sum += level(lm.x(c, i), lm.y(c, i)); count++
            i = Math.floorMod(i + dir, n)
        }
        return if (count == 0) Float.MAX_VALUE else sum / count
    }

    private fun fillArc(n: Int, from: Int, to: Int, dir: Int, out: IntArray): Int {
        var i = from
        var k = 0
        while (k < out.size) {
            out[k++] = i
            if (i == to) break
            i = Math.floorMod(i + dir, n)
        }
        return k
    }

    private fun deriveBrows(lm: FaceLandmarks) {
        // Assign each brow to the nearest eye.
        val b0x = centroidX(lm, Contour.LEFT_EYEBROW_BOTTOM); val b0y = centroidY(lm, Contour.LEFT_EYEBROW_BOTTOM)
        val b1x = centroidX(lm, Contour.RIGHT_EYEBROW_BOTTOM); val b1y = centroidY(lm, Contour.RIGHT_EYEBROW_BOTTOM)
        val leftIsSide0 = lateral(b0x, b0y) <= lateral(b1x, b1y)
        browTopContour[0] = if (leftIsSide0) Contour.LEFT_EYEBROW_TOP else Contour.RIGHT_EYEBROW_TOP
        browBottomContour[0] = if (leftIsSide0) Contour.LEFT_EYEBROW_BOTTOM else Contour.RIGHT_EYEBROW_BOTTOM
        browTopContour[1] = if (leftIsSide0) Contour.RIGHT_EYEBROW_TOP else Contour.LEFT_EYEBROW_TOP
        browBottomContour[1] = if (leftIsSide0) Contour.RIGHT_EYEBROW_BOTTOM else Contour.LEFT_EYEBROW_BOTTOM
        for (side in 0..1) {
            browCenterX[side] = centroidX(lm, browBottomContour[side])
            browCenterY[side] = centroidY(lm, browBottomContour[side])
        }
    }

    private fun deriveMouth(lm: FaceLandmarks) {
        val c = Contour.UPPER_LIP_TOP
        val last = lm.size(c) - 1
        val firstIsLeft = lateral(lm.x(c, 0), lm.y(c, 0)) <= lateral(lm.x(c, last), lm.y(c, last))
        val l = if (firstIsLeft) 0 else last
        val r = if (firstIsLeft) last else 0
        mouthCornerX[0] = lm.x(c, l); mouthCornerY[0] = lm.y(c, l)
        mouthCornerX[1] = lm.x(c, r); mouthCornerY[1] = lm.y(c, r)
        mouthWidth = max(dist(mouthCornerX[0], mouthCornerY[0], mouthCornerX[1], mouthCornerY[1]), 1e-4f)
        mouthCenterX = (centroidX(lm, Contour.UPPER_LIP_BOTTOM) + centroidX(lm, Contour.LOWER_LIP_TOP)) * 0.5f
        mouthCenterY = (centroidY(lm, Contour.UPPER_LIP_BOTTOM) + centroidY(lm, Contour.LOWER_LIP_TOP)) * 0.5f
    }

    private fun deriveNose(lm: FaceLandmarks) {
        noseTopX = lm.x(Contour.NOSE_BRIDGE, 0); noseTopY = lm.y(Contour.NOSE_BRIDGE, 0)
        noseTipX = lm.x(Contour.NOSE_BRIDGE, 1); noseTipY = lm.y(Contour.NOSE_BRIDGE, 1)
        if (level(noseTopX, noseTopY) > level(noseTipX, noseTipY)) {
            val tx = noseTopX; val ty = noseTopY
            noseTopX = noseTipX; noseTopY = noseTipY
            noseTipX = tx; noseTipY = ty
        }
        val c = Contour.NOSE_BOTTOM
        var minI = 0; var maxI = 0
        var minA = Float.MAX_VALUE; var maxA = -Float.MAX_VALUE
        for (i in 0 until lm.size(c)) {
            val a = lateral(lm.x(c, i), lm.y(c, i))
            if (a < minA) { minA = a; minI = i }
            if (a > maxA) { maxA = a; maxI = i }
        }
        noseWingX[0] = lm.x(c, minI); noseWingY[0] = lm.y(c, minI)
        noseWingX[1] = lm.x(c, maxI); noseWingY[1] = lm.y(c, maxI)
        noseBottomX = centroidX(lm, c); noseBottomY = centroidY(lm, c)
        noseWidth = max(dist(noseWingX[0], noseWingY[0], noseWingX[1], noseWingY[1]), interocular * 0.3f)
    }

    /**
     * Point on the face oval on [side] (0 = image-left) at [fraction] of the way from the eye line (0) to the chin
     * level (1). Negative fractions go above the eyes. Interpolates along the contour; writes into [out] (x, y).
     */
    fun ovalSidePoint(side: Int, fraction: Float, out: FloatArray) {
        val lm = landmarks
        val c = Contour.FACE
        val n = lm.size(c)
        val target = eyeLevel + (chinLevel - eyeLevel) * fraction
        // Walk from top to chin along both directions; pick the direction whose points lie on the requested side.
        val midLateral = lateral(centerX, centerY)
        var dir = +1
        run {
            val probe = Math.floorMod(ovalTopIdx + n / 4, n)
            val a = lateral(lm.x(c, probe), lm.y(c, probe))
            val probeSide = if (a < midLateral) 0 else 1
            if (probeSide != side) dir = -1
        }
        var i = ovalTopIdx
        var prevX = lm.x(c, i); var prevY = lm.y(c, i); var prevL = level(prevX, prevY)
        var steps = 0
        while (steps < n) {
            val j = Math.floorMod(i + dir, n)
            val x = lm.x(c, j); val y = lm.y(c, j); val l = level(x, y)
            if ((prevL - target) * (l - target) <= 0f && abs(l - prevL) > 1e-7f) {
                val t = ((target - prevL) / (l - prevL)).coerceIn(0f, 1f)
                out[0] = prevX + (x - prevX) * t
                out[1] = prevY + (y - prevY) * t
                return
            }
            if (j == chinIdx) { out[0] = x; out[1] = y; return }
            prevX = x; prevY = y; prevL = l
            i = j
            steps++
        }
        out[0] = lm.x(c, chinIdx); out[1] = lm.y(c, chinIdx)
    }

    companion object {
        fun centroidX(lm: FaceLandmarks, c: Int): Float {
            var s = 0f
            for (i in 0 until lm.size(c)) s += lm.x(c, i)
            return s / lm.size(c)
        }

        fun centroidY(lm: FaceLandmarks, c: Int): Float {
            var s = 0f
            for (i in 0 until lm.size(c)) s += lm.y(c, i)
            return s / lm.size(c)
        }

        fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
            val dx = bx - ax; val dy = by - ay
            return sqrt(dx * dx + dy * dy)
        }
    }
}
