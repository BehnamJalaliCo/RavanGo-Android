package com.ravango.engine.beauty.geometry

import com.google.common.truth.Truth.assertThat
import com.ravango.core.model.BeautyFeature
import com.ravango.core.model.BeautyState
import com.ravango.engine.beauty.TestFaces
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class WarpDerivationTest {

    private fun geometry(lm: com.ravango.engine.beauty.tracking.FaceLandmarks = TestFaces.frontal()) = FaceGeometry().apply { update(lm) }

    private fun state(vararg values: Pair<BeautyFeature, Int>) = BeautyState(beauty = mapOf(*values))

    private fun derive(g: FaceGeometry, s: BeautyState, presence: Float = 1f) = WarpSet().also { WarpDerivation.derive(g, s, presence, it) }

    @Test
    fun geometryIsDerivedFromContours() {
        val g = geometry()
        assertThat(g.interocular).isWithin(1e-4f).of(0.16f)
        assertThat(g.exX).isWithin(1e-4f).of(1f)
        assertThat(g.eyY).isWithin(1e-4f).of(1f)
        assertThat(g.faceWidth).isWithin(1e-3f).of(0.4f)
        // Upper lid lies above the eye center (smaller y), lower lid below.
        val lm = g.landmarks
        val c = g.eyeContour[0]
        val mid = g.upperLid[0][g.upperLidCount[0] / 2]
        assertThat(lm.y(c, mid)).isLessThan(g.eyeCenterY[0])
        val midLow = g.lowerLid[0][g.lowerLidCount[0] / 2]
        assertThat(lm.y(c, midLow)).isGreaterThan(g.eyeCenterY[0])
        // Outer corner of the image-left eye is its leftmost point.
        assertThat(lm.x(c, g.outerCornerIdx[0])).isLessThan(lm.x(c, g.innerCornerIdx[0]))
    }

    @Test
    fun ovalSidePointInterpolatesOnRequestedSide() {
        val g = geometry()
        val out = FloatArray(2)
        g.ovalSidePoint(0, 0.5f, out)
        assertThat(out[0]).isLessThan(0.5f)
        assertThat(out[1]).isWithin(0.01f).of(0.45f + (0.78f - 0.45f) * 0.5f)
        g.ovalSidePoint(1, 0.5f, out)
        assertThat(out[0]).isGreaterThan(0.5f)
    }

    @Test
    fun neutralStateProducesNoWarps() {
        val w = derive(geometry(), BeautyState())
        assertThat(w.count).isEqualTo(0)
        assertThat(WarpDerivation.hasReshape(BeautyState())).isFalse()
    }

    @Test
    fun faceSlimPullsBothSidesTowardTheMidline() {
        val w = derive(geometry(), state(BeautyFeature.FACE_SLIM to 100))
        assertThat(w.count).isEqualTo(4)
        for (i in 0 until w.count) {
            assertThat(w.type(i)).isEqualTo(WarpSet.TYPE_TRANSLATE)
            if (w.centerX(i) < 0.5f) assertThat(w.vx(i)).isGreaterThan(0f) else assertThat(w.vx(i)).isLessThan(0f)
            val len = sqrt(w.vx(i) * w.vx(i) + w.vy(i) * w.vy(i))
            assertThat(len).isAtMost(w.radius(i) * WarpSet.MAX_TRANSLATE_RATIO + 1e-6f)
        }
    }

    @Test
    fun bipolarChinMovesDownAboveNeutralAndUpBelow() {
        val g = geometry()
        val longer = derive(g, state(BeautyFeature.CHIN to 100))
        val shorter = derive(g, state(BeautyFeature.CHIN to 0))
        assertThat(longer.count).isEqualTo(1)
        assertThat(longer.vy(0)).isGreaterThan(0f)
        assertThat(shorter.vy(0)).isLessThan(0f)
        assertThat(derive(g, state(BeautyFeature.CHIN to 50)).count).isEqualTo(0)
    }

    @Test
    fun eyeSizeMagnifiesAroundEachEye() {
        val g = geometry()
        val w = derive(g, state(BeautyFeature.EYE_SIZE to 100))
        assertThat(w.count).isEqualTo(2)
        for (i in 0..1) {
            assertThat(w.type(i)).isEqualTo(WarpSet.TYPE_SCALE)
            assertThat(w.strength(i)).isGreaterThan(0f)
            assertThat(w.centerX(i)).isWithin(1e-4f).of(g.eyeCenterX[i])
        }
        // Magnification: a point near the eye samples from closer to the center.
        val src = FloatArray(2)
        w.sourceOf(g.eyeCenterX[0] + 0.02f, g.eyeCenterY[0], src)
        assertThat(src[0] - g.eyeCenterX[0]).isLessThan(0.02f)
    }

    @Test
    fun presenceScalesDisplacement() {
        val g = geometry()
        val full = derive(g, state(BeautyFeature.FACE_SLIM to 60), 1f)
        val half = derive(g, state(BeautyFeature.FACE_SLIM to 60), 0.5f)
        assertThat(half.vx(0)).isWithin(1e-6f).of(full.vx(0) * 0.5f)
        assertThat(derive(g, state(BeautyFeature.FACE_SLIM to 60), 0f).count).isEqualTo(0)
    }

    @Test
    fun mirroredFaceGivesMirroredWarps() {
        val aspect = 0.5625f * 1.8f
        val lm = TestFaces.frontal()
        val s = state(BeautyFeature.FACE_SLIM to 80, BeautyFeature.EYE_SHAPE to 90, BeautyFeature.NOSE to 70)
        val a = derive(geometry(lm), s)
        val b = derive(geometry(TestFaces.mirrored(lm, aspect)), s)
        assertThat(b.count).isEqualTo(a.count)
        // Every warp has a mirrored counterpart.
        for (i in 0 until a.count) {
            val match = (0 until b.count).any { j ->
                abs(b.centerX(j) - (aspect - a.centerX(i))) < 1e-4f && abs(b.centerY(j) - a.centerY(i)) < 1e-4f &&
                    abs(b.vx(j) + a.vx(i)) < 1e-5f && abs(b.vy(j) - a.vy(i)) < 1e-5f
            }
            assertThat(match).isTrue()
        }
    }

    @Test
    fun allFeaturesMaxedStayWithinUniformBudgetAndDoNotFold() {
        val all = BeautyState(beauty = WarpDerivation.ReshapeFeatures.associateWith { 100 })
        val g = geometry()
        val w = derive(g, all)
        assertThat(w.count).isAtMost(WarpSet.MAX_WARPS)
        assertThat(w.count).isGreaterThan(10)
        // The mapping stays monotonic along horizontal scanlines through the face (no tearing/folding).
        val src = FloatArray(2)
        var y = 0.3f
        while (y < 0.8f) {
            var prev = -1f
            var x = 0.2f
            while (x < 0.8f) {
                w.sourceOf(x, y, src)
                assertThat(src[0]).isGreaterThan(prev)
                prev = src[0]
                x += 0.002f
            }
            y += 0.05f
        }
    }

    @Test
    fun translateIsClampedToRadiusRatio() {
        val w = WarpSet()
        w.addTranslate(0.5f, 0.5f, 0.1f, 1f, 0f)
        assertThat(w.vx(0)).isWithin(1e-6f).of(0.1f * WarpSet.MAX_TRANSLATE_RATIO)
        w.addScale(0.5f, 0.5f, 0.1f, 5f)
        assertThat(w.strength(1)).isEqualTo(WarpSet.MAX_SCALE)
    }
}
