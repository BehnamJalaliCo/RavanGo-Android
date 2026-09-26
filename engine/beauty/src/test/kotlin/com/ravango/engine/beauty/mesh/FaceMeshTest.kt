package com.ravango.engine.beauty.mesh

import com.google.common.truth.Truth.assertThat
import com.ravango.engine.beauty.TestMesh
import org.junit.Assert.assertThrows
import org.junit.Test

class FaceMeshTest {

    @Test
    fun parsesCanonicalModel() {
        val m = TestMesh.model
        assertThat(m.vertexCount).isEqualTo(468)
        assertThat(m.triangleCount).isEqualTo(898)
        // Nose tip and chin UVs from the MediaPipe model (v grows upwards).
        assertThat(m.u(1)).isWithin(1e-5f).of(0.500026f)
        assertThat(m.v(1)).isWithin(1e-5f).of(0.452513f)
        assertThat(m.v(152)).isLessThan(0.1f)
        assertThat(m.v(10)).isGreaterThan(0.85f)
        // Symmetric layout: the subject's right eye corner mirrors the left one.
        assertThat(m.u(33)).isWithin(1e-4f).of(1f - m.u(263))
        assertThat(m.v(33)).isWithin(1e-4f).of(m.v(263))
        for (i in m.triangles) assertThat(i).isIn(0 until 468)
        for (k in 0 until m.vertexCount) {
            assertThat(m.u(k)).isIn(com.google.common.collect.Range.closed(0f, 1f))
            assertThat(m.v(k)).isIn(com.google.common.collect.Range.closed(0f, 1f))
        }
    }

    @Test
    fun parsesCornerFormatsAndFans() {
        val obj = """
            # quad with v/vt/vn corners, then a triangle with negative (relative) indices
            v 0 0 0
            v 1 0 0
            v 1 1 0
            v 0 1 0
            vt 0 0
            vt 1 0
            vt 1 1
            vt 0 1
            vn 0 0 1
            f 1/1/1 2/2/1 3/3/1 4/4/1
            f -4/-4 -2/-2 -1/-1
        """.trimIndent()
        val m = ObjParser.parse(obj)
        assertThat(m.vertexCount).isEqualTo(4)
        assertThat(m.triangles.toList()).containsExactly(0, 1, 2, 0, 2, 3, 0, 2, 3).inOrder()
        assertThat(m.u(2)).isEqualTo(1f)
        assertThat(m.v(3)).isEqualTo(1f)
        val noUv = ObjParser.parse("v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1//1 2//1 3//1")
        assertThat(noUv.triangleCount).isEqualTo(1)
    }

    @Test
    fun rejectsConflictingUvsAndBadIndices() {
        assertThrows(IllegalArgumentException::class.java) {
            ObjParser.parse("v 0 0 0\nv 1 0 0\nv 0 1 0\nvt 0 0\nvt 1 1\nf 1/1 2/1 3/1\nf 1/2 2/1 3/1")
        }
        assertThrows(IllegalArgumentException::class.java) { ObjParser.parse("v 0 0 0\nf 1 2 3") }
    }

    @Test
    fun regionTriangleSets() {
        val t = TestMesh.topology
        // A 20-point inner lip loop and two 16-point eye loops, fully triangulated (n − 2 triangles each).
        assertThat(t.mouthHoleTriangles.size / 3).isEqualTo(18)
        assertThat(t.eyeHoleTriangles.size / 3).isEqualTo(28)
        assertThat(t.surfaceTriangles.size / 3).isEqualTo(898 - 18 - 28)
        val mouth = FaceLandmarkIndex.LIPS_INNER.toSet()
        for (i in t.mouthHoleTriangles) assertThat(i).isIn(mouth)
        val eyes = (FaceLandmarkIndex.RIGHT_EYE + FaceLandmarkIndex.LEFT_EYE).toSet()
        for (i in t.eyeHoleTriangles) assertThat(i).isIn(eyes)
        // Lip vertices are part of the painted surface (lipstick), the openings are not.
        val surfaceVertices = t.surfaceTriangles.toSet()
        assertThat(surfaceVertices).containsAtLeastElementsIn(FaceLandmarkIndex.LIPS_OUTER.toList())
        assertThat(surfaceVertices).containsAtLeastElementsIn(FaceLandmarkIndex.LIPS_INNER.toList())
    }

    @Test
    fun landmarkIndexSetsAreConsistent() {
        val L = FaceLandmarkIndex
        assertThat(L.FACE_OVAL.toSet()).hasSize(36)
        assertThat(L.LIPS_OUTER.toSet()).hasSize(20)
        assertThat(L.LIPS_INNER.toSet()).hasSize(20)
        assertThat(L.RIGHT_EYE_UPPER.toSet() + L.RIGHT_EYE_LOWER.toSet()).isEqualTo(L.RIGHT_EYE.toSet())
        assertThat(L.LEFT_EYE_UPPER.toSet() + L.LEFT_EYE_LOWER.toSet()).isEqualTo(L.LEFT_EYE.toSet())
        val m = TestMesh.model
        // Right-side sets are on the subject's right (u < ½); left-side ones mirror them.
        for (i in L.RIGHT_EYE + L.RIGHT_BROW_UPPER + L.RIGHT_BROW_LOWER) assertThat(m.u(i)).isLessThan(0.5f)
        for (i in L.LEFT_EYE + L.LEFT_BROW_UPPER + L.LEFT_BROW_LOWER) assertThat(m.u(i)).isGreaterThan(0.5f)
        for (k in L.RIGHT_EYE_UPPER.indices) {
            assertThat(m.u(L.RIGHT_EYE_UPPER[k])).isWithin(2e-3f).of(1f - m.u(L.LEFT_EYE_UPPER[k]))
        }
        // Upper lids are above lower lids.
        assertThat(m.v(159)).isGreaterThan(m.v(145))
        // Oval loop is closed around the face: every mesh vertex's UV lies inside it (± boundary).
        val oval = FloatArray(L.FACE_OVAL.size * 2) { k -> if (k % 2 == 0) m.u(L.FACE_OVAL[k / 2]) else m.v(L.FACE_OVAL[k / 2]) }
        assertThat(com.ravango.engine.beauty.makeup.Geometry.inside(oval, m.u(1), m.v(1))).isTrue()
    }

    @Test
    fun paddedRingIsWoundLikeTheMesh() {
        val t = TestMesh.topology
        val ringTris = t.warpTriangles.size / 3 - 898
        assertThat(ringTris).isEqualTo(36 * 4)
        assertThat(t.totalVertexCount).isEqualTo(468 + 72)
        // Frontal frame-space layout (y up, like UV) of mesh + ring: all triangles share the mesh orientation.
        val lm = TestMesh.landmarks(scale = 1f, cx = 0f, cy = 0f)
        val xy = FloatArray(t.totalVertexCount * 2)
        for (i in 0 until 468) { xy[i * 2] = lm[i * 3]; xy[i * 2 + 1] = -lm[i * 3 + 1] }
        var cx = 0f; var cy = 0f
        for (i in FaceLandmarkIndex.FACE_OVAL) { cx += xy[i * 2]; cy += xy[i * 2 + 1] }
        cx /= 36; cy /= 36
        for ((k, i) in FaceLandmarkIndex.FACE_OVAL.withIndex()) {
            val dx = xy[i * 2] - cx; val dy = xy[i * 2 + 1] - cy
            xy[(t.innerRingStart + k) * 2] = cx + dx * FaceTopology.INNER_RING_SCALE
            xy[(t.innerRingStart + k) * 2 + 1] = cy + dy * FaceTopology.INNER_RING_SCALE
            xy[(t.outerRingStart + k) * 2] = cx + dx * FaceTopology.OUTER_RING_SCALE
            xy[(t.outerRingStart + k) * 2 + 1] = cy + dy * FaceTopology.OUTER_RING_SCALE
        }
        val w = t.warpTriangles
        for (tri in 898 until w.size / 3) {
            val a = FaceTopology.signedArea(xy, w[tri * 3], w[tri * 3 + 1], w[tri * 3 + 2])
            assertThat(a * t.uvWinding).isGreaterThan(0f)
        }
    }
}
