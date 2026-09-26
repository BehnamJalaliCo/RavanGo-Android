package com.ravango.engine.beauty.effects

import com.google.common.truth.Truth.assertThat
import com.ravango.engine.beauty.TestMesh
import com.ravango.engine.beauty.makeup.MakeupAtlas
import com.ravango.engine.beauty.mesh.FaceLandmarkIndex
import com.ravango.engine.beauty.mesh.FaceTopology
import com.ravango.engine.beauty.mesh.MeshWarp
import com.ravango.engine.beauty.mesh.PoseFit
import org.junit.Test
import kotlin.math.abs

class LutGeneratorTest {

    @Test
    fun noneIsIdentityThroughTheLattice() {
        val lut = LutGenerator.generate(LiveFilter.NONE)
        assertThat(lut.size).isEqualTo(LutGenerator.SIZE * LutGenerator.SIZE * 4)
        val out = FloatArray(3)
        for ((r, g, b) in listOf(Triple(0.1f, 0.5f, 0.9f), Triple(0.73f, 0.21f, 0.4f), Triple(1f, 1f, 1f), Triple(0f, 0f, 0f))) {
            LutGenerator.sample(lut, r, g, b, out)
            assertThat(out[0]).isWithin(1.5f / 255f).of(r)
            assertThat(out[1]).isWithin(1.5f / 255f).of(g)
            assertThat(out[2]).isWithin(1.5f / 255f).of(b)
        }
    }

    @Test
    fun trilinearLookupMatchesTheGradingFunction() {
        val out = FloatArray(3)
        val expected = FloatArray(3)
        for (filter in LiveFilter.entries) {
            val lut = LutGenerator.generate(filter)
            for ((r, g, b) in listOf(Triple(0.33f, 0.52f, 0.71f), Triple(0.9f, 0.62f, 0.5f), Triple(0.05f, 0.2f, 0.1f))) {
                LutGenerator.sample(lut, r, g, b, out)
                LutGenerator.grade(filter, r, g, b, expected)
                for (c in 0 until 3) assertThat(out[c]).isWithin(0.03f).of(expected[c])
            }
        }
    }

    @Test
    fun monochromeFiltersAreGrey() {
        val out = FloatArray(3)
        for (filter in listOf(LiveFilter.MONO, LiveFilter.NOIR)) {
            LutGenerator.grade(filter, 0.8f, 0.3f, 0.2f, out)
            assertThat(abs(out[0] - out[1])).isLessThan(0.02f)
            assertThat(abs(out[1] - out[2])).isLessThan(0.03f)
        }
    }

    @Test
    fun filtersHaveDistinctCharacter() {
        val warm = FloatArray(3); val cool = FloatArray(3)
        LutGenerator.grade(LiveFilter.WARM, 0.5f, 0.5f, 0.5f, warm)
        LutGenerator.grade(LiveFilter.COOL, 0.5f, 0.5f, 0.5f, cool)
        assertThat(warm[0]).isGreaterThan(warm[2])
        assertThat(cool[2]).isGreaterThan(cool[0])
        val fade = FloatArray(3)
        LutGenerator.grade(LiveFilter.FADE, 0f, 0f, 0f, fade)
        assertThat(fade[0]).isGreaterThan(0.08f) // lifted blacks
        // Every filter keeps values in range and every preview swatch is opaque.
        val out = FloatArray(3)
        for (f in LiveFilter.entries) {
            LutGenerator.grade(f, 1f, 1f, 1f, out)
            out.forEach { assertThat(it).isIn(com.google.common.collect.Range.closed(0f, 1f)) }
            LutGenerator.previewSwatch(f).forEach { assertThat(it ushr 24).isEqualTo(0xFFL) }
        }
    }

    @Test
    fun atLeastTwelveFiltersAndAFreeBaseSet() {
        val real = LiveFilter.entries.filter { it != LiveFilter.NONE }
        assertThat(real.size).isAtLeast(12)
        assertThat(real.count { !it.pro }).isAtLeast(6)
        assertThat(LiveFilter.fromId("teal_orange")).isEqualTo(LiveFilter.TEAL_ORANGE)
        assertThat(LiveFilter.fromId("nope")).isEqualTo(LiveFilter.NONE)
    }
}

class LensWarpFieldTest {

    private val model = TestMesh.model
    private val field = LensWarpField(model)

    private fun disp(lens: Lens, t: Float = 0f) = FloatArray(model.vertexCount * 2).also { field.compute(lens, t, it) }

    @Test
    fun nonWarpLensesMoveNothing() {
        val out = FloatArray(model.vertexCount * 2) { 1f }
        assertThat(field.compute(Lens.SUNGLASSES, 0f, out)).isFalse()
        assertThat(out.all { it == 0f }).isTrue()
    }

    @Test
    fun bigEyesGrowAroundTheEyes() {
        val d = disp(Lens.BIG_EYES)
        assertThat(d[33 * 2]).isLessThan(-0.3f) // right outer corner moves further out
        assertThat(d[159 * 2 + 1]).isGreaterThan(0.1f) // upper lid up
        assertThat(abs(d[152 * 2]) + abs(d[152 * 2 + 1])).isEqualTo(0f) // chin untouched
        assertThat(d[263 * 2]).isWithin(1e-4f).of(-d[33 * 2]) // symmetric
    }

    @Test
    fun tinyFaceShrinksTowardsTheCentre() {
        val d = disp(Lens.TINY_FACE)
        assertThat(d[152 * 2 + 1]).isGreaterThan(2f) // chin moves up
        assertThat(d[10 * 2 + 1]).isLessThan(-2f) // forehead moves down
        assertThat(d[234 * 2]).isGreaterThan(2f) // right cheek edge moves in
    }

    @Test
    fun swirlIsAnimated() {
        assertThat(field.isAnimated(Lens.FACE_SWIRL)).isTrue()
        assertThat(field.isAnimated(Lens.BIG_EYES)).isFalse()
        val a = disp(Lens.FACE_SWIRL, 0f)
        val b = disp(Lens.FACE_SWIRL, 1.2f)
        assertThat(a[61 * 2]).isNotEqualTo(b[61 * 2])
    }

    @Test
    fun warpLensesStayFoldFreeOnAFrontalFace() {
        val topo = TestMesh.topology
        val lm = TestMesh.landmarks()
        val pose = PoseFit(model).also { assertThat(it.fit(lm)).isTrue() }
        val src = source(topo, lm)
        for (lens in listOf(Lens.BIG_EYES, Lens.PUFFY_CHEEKS, Lens.TINY_FACE, Lens.ALIEN, Lens.FACE_SWIRL)) {
            val out = FloatArray(topo.totalVertexCount * 3)
            val warp = MeshWarp(topo)
            warp.apply(src, disp(lens, 0.7f), pose, 1f, out)
            // The fold guard may scale a lens down, but it must keep a clearly visible effect.
            com.google.common.truth.Truth.assertWithMessage(lens.name).that(warp.lastClamp).isGreaterThan(0.5f)
        }
    }

    private fun source(topo: FaceTopology, lm: FloatArray): FloatArray {
        val pos = FloatArray(topo.totalVertexCount * 3)
        System.arraycopy(lm, 0, pos, 0, topo.meshVertexCount * 3)
        val oval = FaceLandmarkIndex.FACE_OVAL
        var cx = 0f; var cy = 0f
        for (i in oval) { cx += lm[i * 3]; cy += lm[i * 3 + 1] }
        cx /= oval.size; cy /= oval.size
        for (k in oval.indices) {
            val s = oval[k] * 3
            val inner = (topo.innerRingStart + k) * 3
            val outer = (topo.outerRingStart + k) * 3
            pos[inner] = cx + (lm[s] - cx) * FaceTopology.INNER_RING_SCALE
            pos[inner + 1] = cy + (lm[s + 1] - cy) * FaceTopology.INNER_RING_SCALE
            pos[outer] = cx + (lm[s] - cx) * FaceTopology.OUTER_RING_SCALE
            pos[outer + 1] = cy + (lm[s + 1] - cy) * FaceTopology.OUTER_RING_SCALE
        }
        return pos
    }
}

class LensSceneTest {

    private val model = TestMesh.model

    private fun pose(mirror: Boolean = false): PoseFit = PoseFit(model).also { it.fit(TestMesh.landmarks(scale = 0.03f, cx = 0.28f, cy = 0.5f, mirror = mirror)) }

    @Test
    fun sunglassesSitOverTheEyes() {
        val scene = LensScene()
        val p = pose()
        val lm = TestMesh.landmarks(scale = 0.03f, cx = 0.28f, cy = 0.5f)
        scene.begin()
        scene.addFace(Lens.SUNGLASSES, 0, p.affine, 0.5625f, lm, 0f, 1f, 0f, 0.033f, 100)
        assertThat(scene.batch.quads).isEqualTo(1)
        // Centre of the quad (NDC) ≈ midpoint of the eyes.
        val d = scene.batch.data
        var sx = 0f; var sy = 0f
        for (v in 0 until 6) { sx += d[v * SpriteBatch.FLOATS_PER_VERTEX]; sy += d[v * SpriteBatch.FLOATS_PER_VERTEX + 1] }
        sx /= 6; sy /= 6
        val ex = (lm[33 * 3] + lm[263 * 3]) / 2; val ey = (lm[33 * 3 + 1] + lm[263 * 3 + 1]) / 2
        assertThat(sx).isWithin(0.05f).of(LensScene.ndcX(ex, 0.5625f))
        assertThat(sy).isWithin(0.05f).of(LensScene.ndcY(ey))
    }

    @Test
    fun catLensDrawsTwoEarsAndAFace() {
        val scene = LensScene()
        scene.begin()
        scene.addFace(Lens.CAT, 0, pose().affine, 0.5625f, TestMesh.landmarks(), 0f, 1f, 0f, 0.033f, 100)
        assertThat(scene.batch.quads).isEqualTo(3)
        // Absent faces draw nothing.
        scene.begin()
        scene.addFace(Lens.CAT, 0, pose().affine, 0.5625f, TestMesh.landmarks(), 0f, 0f, 0f, 0.033f, 100)
        assertThat(scene.batch.quads).isEqualTo(0)
    }

    @Test
    fun rainbowTriggersOnlyWhenTheMouthOpensWithHysteresis() {
        val scene = LensScene()
        val p = pose()
        val lm = TestMesh.landmarks()
        fun frame(jaw: Float) {
            scene.begin()
            scene.addFace(Lens.RAINBOW, 0, p.affine, 0.5625f, lm, jaw, 1f, 0f, 1f / 30f, 100)
            scene.finish(1f / 30f)
        }
        repeat(10) { frame(0.1f) }
        assertThat(scene.triggered).isFalse()
        assertThat(scene.batch.quads).isEqualTo(0)
        repeat(20) { frame(0.8f) }
        assertThat(scene.triggered).isTrue()
        assertThat(scene.batch.quads).isGreaterThan(5)
        assertThat(scene.hasParticles).isTrue()
        frame(0.26f) // between the thresholds: stays open
        assertThat(scene.triggered).isTrue()
        repeat(60) { frame(0.05f) }
        assertThat(scene.triggered).isFalse()
    }

    @Test
    fun particleBudgetIsRespected() {
        val scene = LensScene()
        val p = pose()
        val lm = TestMesh.landmarks()
        repeat(200) {
            scene.begin()
            scene.addFace(Lens.RAINBOW, 0, p.affine, 0.5625f, lm, 1f, 1f, 0f, 1f / 30f, 12)
            scene.finish(1f / 30f)
        }
        // Ribbon (≤ 10 quads) + at most 12 particles.
        assertThat(scene.batch.quads).isAtMost(10 + 12)
    }

    @Test
    fun spriteBatchNeverOverflows() {
        val batch = SpriteBatch(2)
        repeat(5) { batch.add(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f, SpriteAtlasLayout.STAR, 1f, 1f, 1f, 1f) }
        assertThat(batch.quads).isEqualTo(2)
        assertThat(batch.vertexCount).isEqualTo(12)
    }
}

class FacePaintAndMaskTest {

    @Test
    fun frecklesLandOnTheNoseAndCheeksNotTheEyes() {
        val model = TestMesh.model
        val rgba = FacePaint.generate(model, 256)
        var inside = 0f
        for (i in 0 until 256 * 256) inside += (rgba[i * 4].toInt() and 0xFF)
        assertThat(inside).isGreaterThan(0f)
        // No freckles on the eyeballs.
        val eyeU = (model.u(33) + model.u(133)) / 2f
        val eyeV = (model.v(159) + model.v(145)) / 2f
        val eye = MakeupAtlas.sample(rgba, 256, 0, eyeU, eyeV)
        assertThat(eye).isLessThan(0.2f)
        // Blush on the cheek apples, none on the forehead.
        assertThat(MakeupAtlas.sample(rgba, 256, 1, 0.25f, 0.468f)).isGreaterThan(0.9f)
        assertThat(MakeupAtlas.sample(rgba, 256, 1, 0.5f, 0.85f)).isLessThan(0.05f)
    }

    @Test
    fun maskSmootherDownscalesAndAdapts() {
        val smoother = MaskSmoother()
        val out = ByteArray(4)
        val ones = FloatArray(16) { 1f }
        val zeros = FloatArray(16)
        assertThat(smoother.update(ones, 4, 4, 2, out)).isEqualTo(4)
        assertThat(out[0].toInt() and 0xFF).isEqualTo(255)
        // A tiny flicker is damped…
        val flicker = FloatArray(16) { 0.94f }
        smoother.update(flicker, 4, 4, 2, out)
        assertThat((out[0].toInt() and 0xFF) / 255f).isGreaterThan(0.95f)
        // …while a real change passes through almost at once.
        smoother.update(zeros, 4, 4, 2, out)
        assertThat((out[0].toInt() and 0xFF) / 255f).isLessThan(0.05f)
        smoother.reset()
        smoother.update(ones, 4, 4, 2, out)
        assertThat(out[3].toInt() and 0xFF).isEqualTo(255)
    }

    @Test
    fun splitAxisFollowsTheScreenRotation() {
        val a = FloatArray(3)
        EffectsShaders.splitAxis(0, a)
        assertThat(a.toList()).containsExactly(1f, 0f, 0f).inOrder()
        EffectsShaders.splitAxis(180, a)
        // Screen x of the frame's right edge (tc.x = 1) is 0 when the frame is upside down.
        assertThat(a[0] * 1f + a[1] * 0.5f + a[2]).isEqualTo(0f)
        EffectsShaders.splitAxis(90, a)
        assertThat(a[1]).isEqualTo(1f)
        val taps = EffectsShaders.bokehTaps(16)
        for (i in 0 until 16) assertThat(taps[i * 2] * taps[i * 2] + taps[i * 2 + 1] * taps[i * 2 + 1]).isAtMost(1.0001f)
    }

    @Test
    fun effectsStateNeutrality() {
        assertThat(EffectsState().isNeutral).isTrue()
        assertThat(EffectsState(filter = LiveFilter.WARM, filterIntensity = 0).isNeutral).isTrue()
        assertThat(EffectsState(lens = Lens.CAT).isNeutral).isFalse()
        assertThat(EffectsState(background = BackgroundEffect.Blur()).isNeutral).isFalse()
        assertThat(Lens.fromId("cat")).isEqualTo(Lens.CAT)
        assertThat(Lens.entries.count { !it.pro }).isAtLeast(6)
    }
}
