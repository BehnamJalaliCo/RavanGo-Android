package com.ravango.engine.beauty.makeup

import com.google.common.truth.Truth.assertThat
import com.ravango.engine.beauty.TestMesh
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

class LookAtlasTest {

    private val model = TestMesh.model
    private val atlas by lazy { LookAtlas.generate(model) }
    private val base by lazy { MakeupAtlas.generate(model) }

    private fun d(ch: Int, u: Float, v: Float) = MakeupAtlas.sample(atlas.d, atlas.sizeD, ch, u, v)
    private fun e(ch: Int, u: Float, v: Float) = MakeupAtlas.sample(atlas.e, atlas.sizeE, ch, u, v)
    private fun mid(i: Int, j: Int) = floatArrayOf((model.u(i) + model.u(j)) / 2, (model.v(i) + model.v(j)) / 2)

    /** Max of [f] over a box of half-size [r] around (u, v). */
    private fun maxAround(u: Float, v: Float, r: Float, f: (Float, Float) -> Float): Float {
        var m = 0f
        for (j in -10..10) for (i in -10..10) m = max(m, f(u + i * r / 10f, v + j * r / 10f))
        return m
    }

    @Test
    fun classicWingChannelRemovesOnlyTheWing() {
        // On the lid line (centre of the upper lid) the liner stays: the wing-only mask is empty there.
        val lid = floatArrayOf(model.u(159), model.v(159) + 0.002f)
        assertThat(MakeupAtlas.sample(base.a, base.sizeA, 2, lid[0], lid[1])).isGreaterThan(0.5f)
        assertThat(d(R, lid[0], lid[1])).isLessThan(0.05f)
        // Past the outer corner (towards the temple, u < outer corner on the right eye) the wing is covered.
        val outerU = model.u(33); val outerV = model.v(33)
        assertThat(maxAround(outerU - 0.012f, outerV + 0.006f, 0.008f) { u, v -> d(R, u, v) }).isGreaterThan(0.8f)
        assertThat(d(R, model.u(50), model.v(50))).isEqualTo(0f)
    }

    @Test
    fun dramaticLinerReachesFurtherThanTheClassicWing() {
        val outerU = model.u(33); val outerV = model.v(33)
        // Well beyond the classic wing's 0.026 reach, only the cat-eye paints.
        val far = maxAround(outerU - 0.036f, outerV + 0.017f, 0.01f) { u, v -> d(G, u, v) }
        val farClassic = maxAround(outerU - 0.036f, outerV + 0.017f, 0.01f) { u, v -> d(R, u, v) }
        assertThat(far).isGreaterThan(0.8f)
        assertThat(farClassic).isLessThan(0.1f)
        assertThat(d(G, model.u(1), model.v(1))).isEqualTo(0f)
    }

    @Test
    fun volumeLashesAreDenserAndLongerThanNatural() {
        var volume = 0f
        var natural = 0f
        for (k in 0..40) for (h in 1..6) {
            val u = model.u(159) + (k - 20) * 0.002f
            val v = model.v(159) + h * 0.0035f
            volume += d(B, u, v)
            natural += MakeupAtlas.sample(base.a, base.sizeA, 3, u, v)
        }
        assertThat(volume).isGreaterThan(natural * 1.4f)
        // Kohl sits under the lower lid, not on the cheek.
        assertThat(maxAround(model.u(145), model.v(145) - 0.002f, 0.003f) { u, v -> d(A, u, v) }).isGreaterThan(0.6f)
        assertThat(d(A, model.u(50), model.v(50))).isEqualTo(0f)
    }

    @Test
    fun softChannels() {
        // Second shadow tone is stronger in the outer V than at the inner corner.
        val eyeInner = floatArrayOf(model.u(133), model.v(133) + 0.008f)
        val outerV = floatArrayOf(model.u(33) - 0.002f, model.v(33) + 0.012f)
        assertThat(maxAround(outerV[0], outerV[1], 0.006f) { u, v -> e(R, u, v) })
            .isGreaterThan(e(R, eyeInner[0], eyeInner[1]) + 0.3f)
        // Lip centre: high where the lips meet, low at the outer lip line.
        val inner = mid(13, 0)
        assertThat(e(B, (model.u(13) * 3 + model.u(0)) / 4, (model.v(13) * 3 + model.v(0)) / 4)).isGreaterThan(e(B, model.u(0), model.v(0)) + 0.3f)
        assertThat(e(B, inner[0], inner[1] + 0.2f)).isEqualTo(0f)
        // Freckles on the cheeks and nose, none on the lips or forehead.
        var cheek = 0f
        for (j in 0..20) for (i in 0..20) cheek += e(A, 0.25f + i * 0.006f, 0.5f + j * 0.003f)
        assertThat(cheek).isGreaterThan(3f)
        assertThat(e(A, inner[0], inner[1])).isEqualTo(0f)
        assertThat(e(A, 0.5f, 0.8f)).isEqualTo(0f)
    }

    @Test
    fun pairedChannelsAreSymmetric() {
        var worst = 0f
        for (ch in 0..1) for (j in 1 until 40) for (i in 1 until 20) {
            val u = i / 40f; val v = j / 40f
            worst = maxOf(worst, abs(e(ch, u, v) - e(ch, 1f - u, v)))
        }
        assertThat(worst).isLessThan(0.08f)
    }

    @Test
    fun styleDefaultsNeedNoAtlas() {
        assertThat(MakeupStyle.Default.needsLookAtlas).isFalse()
        assertThat(MakeupStyle(wing = 1f).needsLookAtlas).isTrue()
        assertThat(MakeupStyle(freckles = 0.3f).needsLookAtlas).isTrue()
    }

    /**
     * Writes an eye close-up (right eye, face upright) and the look atlas E channels as PPMs to build/look-atlas when
     * BEAUTY_DUMP_ATLAS=1 (visual review): skin-coloured background, classic liner black, cat-eye red, volume lashes
     * blue, kohl green.
     */
    @Test
    fun dumpForReview() {
        if (System.getenv("BEAUTY_DUMP_ATLAS") != "1") return
        val dir = java.io.File("build/look-atlas").apply { mkdirs() }
        val u0 = 0.18f; val u1 = 0.52f; val v0 = 0.5f; val v1 = 0.8f
        val w = 1020; val h = (w * (v1 - v0) / (u1 - u0)).toInt()
        val img = ByteArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val u = u0 + (u1 - u0) * x / w; val v = v1 - (v1 - v0) * y / h
            var r = 0.93f; var g = 0.78f; var b = 0.68f
            fun over(k: Float, cr: Float, cg: Float, cb: Float) { r += (cr - r) * k; g += (cg - g) * k; b += (cb - b) * k }
            over(e(0, u, v), 0.45f, 0.25f, 0.2f)
            over(e(1, u, v) * 0.6f, 1f, 0.95f, 0.8f)
            over(MakeupAtlas.sample(base.a, base.sizeA, 2, u, v), 0f, 0f, 0f)
            over(d(G, u, v), 0.8f, 0f, 0f)
            over(d(A, u, v), 0f, 0.6f, 0f)
            over(d(B, u, v), 0.1f, 0.1f, 0.9f)
            val o = (y * w + x) * 3
            img[o] = (r * 255).toInt().toByte(); img[o + 1] = (g * 255).toInt().toByte(); img[o + 2] = (b * 255).toInt().toByte()
        }
        ppm(java.io.File(dir, "eye.ppm"), w, h, img)
        val s = atlas.sizeE
        val face = ByteArray(s * s * 3)
        for (j in 0 until s) for (i in 0 until s) {
            val o = (j * s + i) * 4
            fun ch(c: Int) = atlas.e[o + c].toInt() and 0xFF
            val p = ((s - 1 - j) * s + i) * 3
            face[p] = ch(0).toByte(); face[p + 1] = maxOf(ch(1), ch(3)).toByte(); face[p + 2] = ch(2).toByte()
        }
        ppm(java.io.File(dir, "e.ppm"), s, s, face)
    }

    private fun ppm(file: java.io.File, w: Int, h: Int, rgb: ByteArray) = file.outputStream().use {
        it.write("P6\n$w $h\n255\n".toByteArray())
        it.write(rgb)
    }

    private companion object {
        const val R = 0
        const val G = 1
        const val B = 2
        const val A = 3
    }
}
