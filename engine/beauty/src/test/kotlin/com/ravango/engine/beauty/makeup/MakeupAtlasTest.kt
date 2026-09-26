package com.ravango.engine.beauty.makeup

import com.google.common.truth.Truth.assertThat
import com.ravango.engine.beauty.TestMesh
import org.junit.Test
import java.io.File
import kotlin.math.abs

class MakeupAtlasTest {

    private val model = TestMesh.model
    private val atlas by lazy { MakeupAtlas.generate(model) }

    private fun a(ch: Int, u: Float, v: Float) = MakeupAtlas.sample(atlas.a, atlas.sizeA, ch, u, v)
    private fun b(ch: Int, u: Float, v: Float) = MakeupAtlas.sample(atlas.b, atlas.sizeB, ch, u, v)
    private fun c(ch: Int, u: Float, v: Float) = MakeupAtlas.sample(atlas.c, atlas.sizeC, ch, u, v)
    private fun mid(i: Int, j: Int) = floatArrayOf((model.u(i) + model.u(j)) / 2, (model.v(i) + model.v(j)) / 2)

    @Test
    fun lipsCoverTheLipsOnly() {
        val upper = mid(0, 13) // between the cupid's bow and the inner upper lip
        val lower = mid(17, 14)
        assertThat(a(R, upper[0], upper[1])).isGreaterThan(0.9f)
        assertThat(a(R, lower[0], lower[1])).isGreaterThan(0.9f)
        assertThat(a(R, model.u(1), model.v(1))).isEqualTo(0f) // nose tip
        assertThat(a(R, model.u(152), model.v(152) + 0.03f)).isEqualTo(0f) // chin
        // Gloss zone sits on the lower lip.
        assertThat(c(B, lower[0], lower[1])).isGreaterThan(0.5f)
        assertThat(c(B, model.u(1), model.v(1))).isEqualTo(0f)
    }

    @Test
    fun browsLinerLashesAndShadowSitAroundTheEyes() {
        val brow = mid(105, 52)
        assertThat(a(G, brow[0], brow[1])).isGreaterThan(0.45f)
        assertThat(a(G, 0.5f, 0.8f)).isEqualTo(0f) // forehead centre
        // Liner and lashes along the upper lid, nothing on the cheek.
        assertThat(a(B, model.u(159), model.v(159) + 0.002f)).isGreaterThan(0.5f)
        assertThat(a(B, model.u(50), model.v(50))).isEqualTo(0f)
        var lashes = 0f
        for (k in 0..20) lashes += a(A, model.u(159) + (k - 10) * 0.004f, model.v(159) + 0.008f)
        assertThat(lashes).isGreaterThan(1f)
        assertThat(a(A, model.u(50), model.v(50))).isEqualTo(0f)
        // Eyeshadow: strongest at the lash line, fading towards the crease/brow.
        val nearLid = b(R, model.u(159), model.v(159) + 0.012f)
        val nearBrow = b(R, model.u(52), model.v(52) - 0.006f)
        assertThat(nearLid).isGreaterThan(0.6f)
        assertThat(nearBrow).isLessThan(nearLid * 0.5f)
    }

    @Test
    fun cheekLayersAndSymmetry() {
        // Blush peaks on the cheek apple, not on the nose.
        assertThat(b(G, 0.252f, 0.472f)).isGreaterThan(0.9f)
        assertThat(b(G, model.u(1), model.v(1))).isLessThan(0.05f)
        // Highlight on the nose bridge, contour beside the nose — never both at full strength.
        assertThat(b(A, 0.5f, 0.58f)).isGreaterThan(0.5f)
        assertThat(b(B, 0.5f, 0.58f)).isLessThan(0.3f)
        // Mirror symmetry of every paired layer.
        var worst = 0f
        for (ch in 0..3) for (j in 1 until 20) for (i in 1 until 10) {
            val u = i / 20f; val v = j / 20f
            worst = maxOf(worst, abs(b(ch, u, v) - b(ch, 1f - u, v)))
        }
        assertThat(worst).isLessThan(0.08f)
    }

    @Test
    fun skinAndUnderEyeRegions() {
        assertThat(c(R, 0.25f, 0.45f)).isGreaterThan(0.95f) // cheek
        assertThat(c(R, model.u(159) / 2 + model.u(145) / 2, (model.v(159) + model.v(145)) / 2)).isEqualTo(0f) // eye
        val lip = mid(0, 13)
        assertThat(c(R, lip[0], lip[1])).isEqualTo(0f)
        assertThat(c(R, 0.02f, 0.95f)).isEqualTo(0f) // outside the face
        // Under-eye band below the lower lid only.
        assertThat(c(G, model.u(145), model.v(145) - 0.018f)).isGreaterThan(0.3f)
        assertThat(c(G, model.u(159), model.v(159) + 0.02f)).isEqualTo(0f)
    }

    @Test
    fun geometryHelpers() {
        val square = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        assertThat(Geometry.inside(square, 0.5f, 0.5f)).isTrue()
        assertThat(Geometry.signedDistance(square, 0.5f, 0.5f)).isWithin(1e-6f).of(0.5f)
        assertThat(Geometry.signedDistance(square, 1.5f, 0.5f)).isWithin(1e-6f).of(-0.5f)
        val line = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f)
        val out = FloatArray(2)
        Geometry.distanceToPolyline(line, Geometry.cumulativeLengths(line), 1.2f, 0.5f, out)
        assertThat(out[0]).isWithin(1e-6f).of(0.2f)
        assertThat(out[1]).isWithin(1e-6f).of(0.75f)
        val smooth = Geometry.smoothOpen(line, 4)
        assertThat(smooth.size).isEqualTo((2 * 4 + 1) * 2)
        assertThat(smooth.last()).isEqualTo(1f)
    }

    /** Writes every channel as a PGM (face upright) to build/makeup-atlas when BEAUTY_DUMP_ATLAS=1 (visual review). */
    @Test
    fun dumpForReview() {
        if (System.getenv("BEAUTY_DUMP_ATLAS") != "1") return
        val dir = File("build/makeup-atlas").apply { mkdirs() }
        val textures = listOf(Triple("A", atlas.a, atlas.sizeA), Triple("B", atlas.b, atlas.sizeB), Triple("C", atlas.c, atlas.sizeC))
        for ((name, rgba, size) in textures) {
            for (ch in 0..3) {
                val pixels = ByteArray(size * size)
                for (j in 0 until size) for (i in 0 until size) {
                    pixels[(size - 1 - j) * size + i] = rgba[(j * size + i) * 4 + ch]
                }
                File(dir, "${name}_$ch.pgm").outputStream().use { out ->
                    out.write("P5\n$size $size\n255\n".toByteArray())
                    out.write(pixels)
                }
            }
        }
    }

    private companion object {
        const val R = 0
        const val G = 1
        const val B = 2
        const val A = 3
    }
}
