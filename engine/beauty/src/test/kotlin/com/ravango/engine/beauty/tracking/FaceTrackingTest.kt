package com.ravango.engine.beauty.tracking

import com.google.common.truth.Truth.assertThat
import com.ravango.engine.beauty.TestMesh
import com.ravango.engine.beauty.mesh.FaceLandmarkIndex
import org.junit.Test
import java.util.Random
import kotlin.math.sqrt

class FaceTrackingTest {

    private val n = FaceLandmarkIndex.LANDMARK_COUNT

    @Test
    fun stillFaceJitterIsRemovedWithoutExtrapolation() {
        val s = MeshStabilizer(n)
        val base = TestMesh.landmarks(scale = 0.02f)
        val rnd = Random(3)
        val noisy = FloatArray(base.size)
        val out = FloatArray(base.size)
        var inErr = 0.0
        var outErr = 0.0
        for (f in 0 until 120) {
            for (i in base.indices) noisy[i] = base[i] + (rnd.nextGaussian() * 0.0015).toFloat()
            val t = f / 30.0
            s.update(noisy, t, FaceTracks.interocular(base))
            // Render slightly later than the measurement: a still face must not be extrapolated.
            s.predict(t + 0.03, out)
            if (f > 30) for (i in base.indices) {
                inErr += (noisy[i] - base[i]).let { it * it }
                outErr += (out[i] - base[i]).let { it * it }
            }
        }
        assertThat(sqrt(outErr)).isLessThan(sqrt(inErr) * 0.5)
    }

    @Test
    fun movingFaceIsFollowedAndPredicted() {
        val s = MeshStabilizer(n, predictionGain = 1f)
        val out = FloatArray(n * 3)
        val speed = 0.4f // frame units per second ≈ 3 face sizes per second
        var lastT = 0.0
        for (f in 0 until 45) {
            lastT = f / 30.0
            s.update(TestMesh.landmarks(scale = 0.02f, cx = 0.2f + speed * lastT.toFloat()), lastT, 0.18f)
        }
        s.predict(lastT, out)
        val truth = 0.2f + speed * lastT.toFloat()
        val noseX = out[FaceLandmarkIndex.NOSE_TIP * 3] - TestMesh.model.x(1) * 0.02f
        // Little lag at constant speed…
        assertThat(noseX).isWithin(0.02f).of(truth)
        // …and prediction moves the mesh forward in time.
        s.predict(lastT + 0.05, out)
        val ahead = out[FaceLandmarkIndex.NOSE_TIP * 3] - TestMesh.model.x(1) * 0.02f
        assertThat(ahead - noseX).isWithin(0.006f).of(speed * 0.05f)
        // The horizon is capped.
        s.predict(lastT + 1.0, out)
        assertThat(out[FaceLandmarkIndex.NOSE_TIP * 3] - TestMesh.model.x(1) * 0.02f - noseX).isLessThan(speed * 0.09f)
    }

    @Test
    fun fastLocalMotionIsNotHeldBackByAStillHead() {
        // Head still, mouth opening quickly: the lower lip must follow almost immediately.
        val s = MeshStabilizer(n)
        val base = TestMesh.landmarks(scale = 0.02f)
        for (f in 0 until 30) s.update(base, f / 30.0, 0.18f)
        val open = base.copyOf()
        val lip = 14 * 3 + 1
        var t = 1.0
        for (f in 0 until 4) {
            t += 1 / 30.0
            open[lip] = base[lip] + 0.01f * (f + 1)
            s.update(open, t, 0.18f)
        }
        val out = FloatArray(n * 3)
        s.predict(t, out)
        assertThat(out[lip] - base[lip]).isGreaterThan(0.04f * 0.6f)
    }

    @Test
    fun jumpToAnotherFaceResets() {
        val s = MeshStabilizer(n)
        val out = FloatArray(n * 3)
        for (f in 0 until 10) s.update(TestMesh.landmarks(cx = 0.2f), f / 30.0, 0.18f)
        val far = TestMesh.landmarks(cx = 0.9f)
        s.update(far, 10 / 30.0, 0.18f)
        s.predict(10 / 30.0, out)
        assertThat(out[3]).isWithin(1e-5f).of(far[3])
    }

    @Test
    fun twoFacesKeepTheirSlotsAndFade() {
        val tracks = FaceTracks()
        val det = DetectionFrame()
        val left = TestMesh.landmarks(cx = 0.2f, scale = 0.012f)
        val right = TestMesh.landmarks(cx = 0.45f, scale = 0.012f)
        fun detect(t: Double, vararg faces: FloatArray) {
            det.faceCount = faces.size
            for ((k, f) in faces.withIndex()) System.arraycopy(f, 0, det.landmarks[k], 0, f.size)
            tracks.onDetection(det, t)
            tracks.update(t, 1 / 30f)
        }
        detect(0.0, left, right)
        val slotOfLeft = tracks.faces.indexOfFirst { it.active && it.stabilizer.value[3] < 0.3f }
        // Detector order swaps: slots must not.
        for (f in 1 until 10) detect(f / 30.0, right, left)
        assertThat(tracks.faces[slotOfLeft].stabilizer.value[3]).isLessThan(0.3f)
        assertThat(tracks.visibleCount).isEqualTo(2)
        // One face leaves: held briefly, then faded out within hold + fade time.
        var t = 10 / 30.0
        while (t < 10 / 30.0 + FaceTracks.HOLD_SEC - 0.05) { t += 1 / 30.0; detect(t, right) }
        assertThat(tracks.faces[slotOfLeft].presence).isEqualTo(1f)
        repeat(12) { t += 1 / 30.0; detect(t, right) }
        assertThat(tracks.faces[slotOfLeft].active).isFalse()
        assertThat(tracks.visibleCount).isEqualTo(1)
    }

    @Test
    fun presenceFadesInOverAbout150ms() {
        val tracks = FaceTracks()
        val det = DetectionFrame().apply { faceCount = 1 }
        System.arraycopy(TestMesh.landmarks(), 0, det.landmarks[0], 0, n * 3)
        tracks.onDetection(det, 0.0)
        tracks.update(0.0, 1 / 30f)
        assertThat(tracks.faces.first { it.active }.presence).isLessThan(0.5f)
        repeat(4) { tracks.update((it + 1) / 30.0, 1 / 30f) }
        assertThat(tracks.faces.first { it.active }.presence).isEqualTo(1f)
    }
}
