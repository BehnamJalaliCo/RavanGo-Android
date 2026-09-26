package com.ravango.engine.camera.gl

import android.opengl.Matrix
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The SurfaceTexture matrix of a Camera2 preview stream = (vertical flip) × (orientation Q) [× crop].
 * After cancellation, `st × C` must behave like an untransformed buffer (a plain vertical flip).
 */
class SurfaceTextureOrientationTest {

    private val flip = Affine.FLIP_Y

    /** Orientation transforms in GL texture space, about the centre. */
    private val orientations = listOf(
        Affine.IDENTITY,
        Affine.inverseRotation(90),
        Affine.inverseRotation(180),
        Affine.inverseRotation(270),
        Affine.MIRROR_X,
        Affine.MIRROR_X then Affine.inverseRotation(90),
        Affine.MIRROR_X then Affine.inverseRotation(270),
    )

    private fun Affine.toGl(): FloatArray = FloatArray(16).also { toGlMatrix(it) }

    private fun apply(m: FloatArray, x: Float, y: Float): Pair<Float, Float> =
        (m[0] * x + m[4] * y + m[12]) to (m[1] * x + m[5] * y + m[13])

    private fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        // Plain column-major multiply (android.opengl.Matrix is stubbed in JVM tests).
        val r = FloatArray(16)
        for (col in 0 until 4) for (row in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += a[k * 4 + row] * b[col * 4 + k]
            r[col * 4 + row] = s
        }
        return r
    }

    @Test
    fun cancelsEveryOrthogonalOrientation() {
        for (q in orientations) {
            val st = (q then flip).toGl() // texcoord → Q → flip (matrix = F × Q)
            val c = FloatArray(16)
            assertThat(FrameGeometry.cancelSurfaceTextureOrientation(st, c)).isTrue()
            val combined = multiply(st, c)
            for ((x, y) in listOf(0f to 0f, 1f to 0f, 0f to 1f, 0.25f to 0.75f)) {
                val (ex, ey) = apply(flip.toGl(), x, y)
                val (ax, ay) = apply(combined, x, y)
                assertThat(ax).isWithin(1e-5f).of(ex)
                assertThat(ay).isWithin(1e-5f).of(ey)
            }
        }
    }

    @Test
    fun plainFlipNeedsNoCompensation() {
        val c = FloatArray(16)
        FrameGeometry.cancelSurfaceTextureOrientation(flip.toGl(), c)
        assertThat(c.toList()).isEqualTo(Affine.IDENTITY.toGl().toList())
    }

    @Test
    fun keepsCropScale() {
        // Rotated by 90° with a 2% crop on each axis (typical of padded camera buffers).
        val crop = Affine(a = 0.98f, d = 0.98f, tx = 0.01f, ty = 0.01f)
        val st = (Affine.inverseRotation(90) then flip then crop).toGl()
        val c = FloatArray(16)
        assertThat(FrameGeometry.cancelSurfaceTextureOrientation(st, c)).isTrue()
        val (x0, y0) = apply(multiply(st, c), 0f, 0f)
        assertThat(x0).isWithin(1e-4f).of(0.01f)
        assertThat(y0).isWithin(1e-4f).of(0.99f)
    }

    @Suppress("unused")
    private val keepImport = Matrix::class
}
