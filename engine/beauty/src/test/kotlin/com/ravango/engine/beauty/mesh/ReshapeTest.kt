package com.ravango.engine.beauty.mesh

import com.google.common.truth.Truth.assertThat
import com.ravango.core.model.BeautyFeature
import com.ravango.core.model.BeautyState
import com.ravango.engine.beauty.TestMesh
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class ReshapeTest {

    private val model = TestMesh.model
    private val field = ReshapeField(model)

    private fun params(vararg values: Pair<BeautyFeature, Int>) =
        ReshapeParams().apply { set(BeautyState(beauty = mapOf(*values))) }

    private fun disp(p: ReshapeParams) = FloatArray(model.vertexCount * 2).also { field.compute(p, it) }

    @Test
    fun paramsMapBipolarAndUnipolarFeatures() {
        val p = params(BeautyFeature.FACE_SLIM to 100, BeautyFeature.JAW to 25, BeautyFeature.CHIN to 50)
        assertThat(p.slim).isEqualTo(1f)
        assertThat(p.jaw).isEqualTo(-0.5f)
        assertThat(p.chin).isEqualTo(0f)
        assertThat(ReshapeParams().apply { set(BeautyState()) }.isNeutral).isTrue()
    }

    @Test
    fun neutralMovesNothing() {
        val out = FloatArray(model.vertexCount * 2) { 1f }
        assertThat(field.compute(ReshapeParams(), out)).isFalse()
        assertThat(out.all { it == 0f }).isTrue()
    }

    @Test
    fun slimPullsCheeksInwardSymmetrically() {
        val d = disp(params(BeautyFeature.FACE_SLIM to 100))
        // Lower cheeks / jaw (361 right side of the image = subject's left, 132 its mirror).
        assertThat(d[361 * 2]).isLessThan(-0.2f)
        assertThat(d[132 * 2]).isGreaterThan(0.2f)
        assertThat(d[361 * 2]).isWithin(1e-3f).of(-d[132 * 2])
        // Eyes, nose tip and forehead stay put.
        assertThat(abs(d[1 * 2])).isLessThan(1e-4f)
        assertThat(abs(d[10 * 2]) + abs(d[10 * 2 + 1])).isLessThan(1e-4f)
        assertThat(abs(d[159 * 2])).isLessThan(0.02f)
    }

    @Test
    fun bipolarFeaturesChangeDirection() {
        val longer = disp(params(BeautyFeature.CHIN to 100))
        val shorter = disp(params(BeautyFeature.CHIN to 0))
        assertThat(longer[152 * 2 + 1]).isLessThan(-0.5f) // chin moves down (canonical y is up)
        assertThat(shorter[152 * 2 + 1]).isGreaterThan(0.5f)
        val taller = disp(params(BeautyFeature.FOREHEAD to 100))
        assertThat(taller[10 * 2 + 1]).isGreaterThan(0.4f)
        val wider = disp(params(BeautyFeature.JAW to 100))
        assertThat(wider[172 * 2]).isLessThan(-0.1f) // right jaw angle (x < 0) moves further out
    }

    @Test
    fun eyeSizeScalesAroundEyeCentres() {
        val d = disp(params(BeautyFeature.EYE_SIZE to 100))
        // Outer corner of the right eye (x < centre) moves out, inner corner moves in towards the nose… both away
        // from the eye centre.
        assertThat(d[33 * 2]).isLessThan(0f)
        assertThat(d[133 * 2]).isGreaterThan(0f)
        assertThat(d[159 * 2 + 1]).isGreaterThan(0f) // upper lid up
        assertThat(d[145 * 2 + 1]).isLessThan(0f) // lower lid down
        assertThat(abs(d[152 * 2]) + abs(d[152 * 2 + 1])).isEqualTo(0f)
    }

    @Test
    fun maximalReshapeNeverFoldsTheMesh() {
        val all = params(
            BeautyFeature.FACE_SLIM to 100, BeautyFeature.JAW to 0, BeautyFeature.CHIN to 100,
            BeautyFeature.CHEEKBONE to 100, BeautyFeature.FOREHEAD to 0, BeautyFeature.NOSE to 100,
            BeautyFeature.EYE_SIZE to 100, BeautyFeature.EYE_SHAPE to 100,
        )
        val d = disp(all)
        val topo = TestMesh.topology
        val lm = TestMesh.landmarks()
        val pose = PoseFit(model).also { assertThat(it.fit(lm)).isTrue() }
        val src = source(topo, lm)
        val out = FloatArray(topo.totalVertexCount * 3)
        val warp = MeshWarp(topo)
        warp.apply(src, d, pose, 1f, out)
        assertThat(warp.lastClamp).isEqualTo(1f)
        // Every front-facing triangle keeps its orientation.
        val flat = FloatArray(topo.totalVertexCount * 2)
        val flat0 = FloatArray(topo.totalVertexCount * 2)
        for (i in 0 until topo.totalVertexCount) {
            flat[i * 2] = out[i * 3]; flat[i * 2 + 1] = out[i * 3 + 1]
            flat0[i * 2] = src[i * 3]; flat0[i * 2 + 1] = src[i * 3 + 1]
        }
        val t = topo.warpTriangles
        for (k in 0 until t.size / 3) {
            val a0 = FaceTopology.signedArea(flat0, t[k * 3], t[k * 3 + 1], t[k * 3 + 2])
            val a1 = FaceTopology.signedArea(flat, t[k * 3], t[k * 3 + 1], t[k * 3 + 2])
            if (abs(a0) < 1e-7f) continue
            assertThat(a1 / a0).isGreaterThan(0f)
        }
        // The outer ring never moves: the warp blends into the background without a seam.
        for (k in 0 until topo.ringSize) {
            val i = (topo.outerRingStart + k) * 3
            assertThat(out[i]).isEqualTo(src[i])
            assertThat(out[i + 1]).isEqualTo(src[i + 1])
        }
    }

    @Test
    fun foldGuardClampsExcessiveDisplacement() {
        val topo = TestMesh.topology
        val lm = TestMesh.landmarks()
        val src = source(topo, lm)
        // Push the nose tip far across the face: triangles around it would flip.
        val disp = FloatArray(topo.totalVertexCount * 2)
        disp[1 * 2] = 0.2f
        val s = MeshWarp.foldSafeScale(src, disp, topo.warpTriangles, MeshWarp.MIN_AREA_RATIO)
        assertThat(s).isLessThan(0.5f)
        assertThat(s).isAtLeast(0f)
    }

    @Test
    fun displacementFollowsHeadScaleAndMirroring() {
        val d = disp(params(BeautyFeature.FACE_SLIM to 100))
        val topo = TestMesh.topology
        fun cheekShift(lm: FloatArray): Float {
            val pose = PoseFit(model).also { it.fit(lm) }
            val out = FloatArray(topo.totalVertexCount * 3)
            MeshWarp(topo).apply(source(topo, lm), d, pose, 1f, out)
            return out[361 * 3] - lm[361 * 3]
        }
        val near = cheekShift(TestMesh.landmarks(scale = 0.04f))
        val far = cheekShift(TestMesh.landmarks(scale = 0.02f))
        assertThat(near).isWithin(1e-4f).of(far * 2f)
        // Mirrored (front camera): subject's left cheek is on the image left, and still moves inwards.
        val mirrored = cheekShift(TestMesh.landmarks(scale = 0.04f, mirror = true))
        assertThat(mirrored).isWithin(1e-4f).of(-near)
        // Presence weight scales the effect.
        val lm = TestMesh.landmarks()
        val pose = PoseFit(model).also { it.fit(lm) }
        val half = FloatArray(topo.totalVertexCount * 3)
        MeshWarp(topo).apply(source(topo, lm), d, pose, 0.5f, half)
        val full = cheekShift(lm)
        assertThat(half[361 * 3] - lm[361 * 3]).isWithin(1e-5f).of(full * 0.5f)
    }

    @Test
    fun poseFitRecoversScaleAndTurn() {
        val pose = PoseFit(model)
        assertThat(pose.fit(TestMesh.landmarks(scale = 0.03f))).isTrue()
        assertThat(pose.scale).isWithin(1e-4f).of(0.03f)
        assertThat(pose.turn).isLessThan(0.01f)
        val yaw = 0.5f
        pose.fit(TestMesh.landmarks(scale = 0.03f, yaw = yaw))
        assertThat(pose.scale).isWithin(1e-4f).of(0.03f)
        assertThat(pose.turn).isWithin(0.01f).of(sin(yaw))
        // The projected canonical x axis keeps its length along the image x axis under zero yaw.
        pose.fit(TestMesh.landmarks(scale = 0.05f, cx = 0.4f, cy = 0.6f))
        val tmp = FloatArray(2)
        pose.projectDelta(1f, 0f, 0f, tmp, 0)
        assertThat(sqrt(tmp[0] * tmp[0] + tmp[1] * tmp[1])).isWithin(1e-4f).of(0.05f)
        pose.projectDelta(0f, 1f, 0f, tmp, 0)
        assertThat(tmp[1]).isWithin(1e-4f).of(-0.05f) // canonical up = image up (y down)
    }

    @Test
    fun strongTurnFadesTheWarpOut() {
        val d = disp(params(BeautyFeature.FACE_SLIM to 100))
        val topo = TestMesh.topology
        val lm = TestMesh.landmarks(yaw = 1.3f)
        val pose = PoseFit(model).also { it.fit(lm) }
        val out = FloatArray(topo.totalVertexCount * 3)
        MeshWarp(topo).apply(source(topo, lm), d, pose, 1f, out)
        for (i in 0 until topo.totalVertexCount * 3) assertThat(out[i]).isWithin(1e-6f).of(source(topo, lm)[i])
    }

    /** Mesh + padded ring source positions like the processor builds them. */
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
