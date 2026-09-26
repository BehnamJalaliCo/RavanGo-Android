package com.ravango.engine.beauty.geometry

import com.google.common.truth.Truth.assertThat
import com.ravango.engine.beauty.TestFaces
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class TriangulatorTest {

    private val triangulator = Triangulator()
    private val indices = IntArray(64 * 3)

    private fun areaOfTriangles(xy: FloatArray, count: Int): Float {
        var sum = 0f
        for (t in 0 until count) {
            val a = indices[t * 3]; val b = indices[t * 3 + 1]; val c = indices[t * 3 + 2]
            val area = ((xy[b * 2] - xy[a * 2]) * (xy[c * 2 + 1] - xy[a * 2 + 1]) -
                (xy[c * 2] - xy[a * 2]) * (xy[b * 2 + 1] - xy[a * 2 + 1])) * 0.5f
            sum += abs(area)
        }
        return sum
    }

    private fun check(xy: FloatArray) {
        val n = xy.size / 2
        val count = triangulator.triangulate(xy, n, indices)
        assertThat(count).isEqualTo(n - 2)
        assertThat(areaOfTriangles(xy, count)).isWithin(1e-4f).of(abs(Triangulator.signedArea(xy, n)))
        for (i in 0 until count * 3) assertThat(indices[i]).isIn(0 until n)
    }

    private fun reversed(xy: FloatArray): FloatArray {
        val n = xy.size / 2
        return FloatArray(xy.size) { i -> xy[(n - 1 - i / 2) * 2 + i % 2] }
    }

    @Test
    fun square() = check(floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f))

    @Test
    fun concaveLShapeBothWindings() {
        val l = floatArrayOf(0f, 0f, 2f, 0f, 2f, 1f, 1f, 1f, 1f, 2f, 0f, 2f)
        check(l)
        check(reversed(l))
    }

    @Test
    fun starPolygon() {
        val n = 10
        val xy = FloatArray(n * 2)
        for (i in 0 until n) {
            val r = if (i % 2 == 0) 1f else 0.4f
            val a = 2 * PI * i / n
            xy[i * 2] = (r * cos(a)).toFloat()
            xy[i * 2 + 1] = (r * sin(a)).toFloat()
        }
        check(xy)
        check(reversed(xy))
    }

    @Test
    fun degenerateCollinearPointsTerminate() {
        val xy = floatArrayOf(0f, 0f, 0.5f, 0f, 1f, 0f, 1f, 1f, 1f, 1f, 0f, 1f)
        val count = triangulator.triangulate(xy, 6, indices)
        assertThat(count).isEqualTo(4)
        assertThat(areaOfTriangles(xy, count)).isWithin(1e-4f).of(1f)
    }

    @Test
    fun tooFewPoints() {
        assertThat(triangulator.triangulate(floatArrayOf(0f, 0f, 1f, 1f), 2, indices)).isEqualTo(0)
    }

    @Test
    fun faceMasksProduceTriangles() {
        val g = FaceGeometry().apply { update(TestFaces.frontal()) }
        val masks = MaskGeometry()
        val buffers = List(12) { TriangleBuffer() }
        masks.faceOval(g, buffers[0])
        masks.exclusions(g, buffers[1])
        masks.underEye(g, buffers[2])
        masks.innerMouth(g, buffers[3])
        masks.lips(g, buffers[4])
        masks.brows(g, buffers[5])
        masks.eyeliner(g, buffers[6])
        masks.eyelashes(g, buffers[7])
        masks.eyeshadow(g, buffers[8])
        masks.blush(g, buffers[9])
        masks.contour(g, buffers[10])
        masks.highlight(g, buffers[11])
        buffers.forEach { b ->
            assertThat(b.vertexCount).isGreaterThan(0)
            assertThat(b.vertexCount % 3).isEqualTo(0)
        }
        // Face oval: 36 points → 34 triangles covering the ellipse area (π·0.2·0.28 ≈ 0.176).
        assertThat(buffers[0].vertexCount).isEqualTo(34 * 3)
        var area = 0f
        val d = buffers[0].data
        for (t in 0 until 34) {
            val o = t * 9
            area += abs(((d[o + 3] - d[o]) * (d[o + 7] - d[o + 1]) - (d[o + 6] - d[o]) * (d[o + 4] - d[o + 1])) * 0.5f)
        }
        assertThat(area).isWithin(0.005f).of((PI * 0.2 * 0.28).toFloat())
    }
}
