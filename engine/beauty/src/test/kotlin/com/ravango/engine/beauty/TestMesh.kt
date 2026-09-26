package com.ravango.engine.beauty

import com.ravango.engine.beauty.mesh.FaceLandmarkIndex
import com.ravango.engine.beauty.mesh.FaceMeshModel
import com.ravango.engine.beauty.mesh.FaceTopology
import com.ravango.engine.beauty.mesh.ObjParser
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/** The bundled canonical face model (unit tests run with the module directory as working directory). */
object TestMesh {
    val model: FaceMeshModel by lazy { ObjParser.parse(File("src/main/assets/face_mesh/canonical_face_model.obj").readText()) }
    val topology: FaceTopology by lazy { FaceTopology(model) }

    /**
     * Synthetic tracked landmarks (x, y, z per point, 478 points) of the canonical face seen by a weak-perspective
     * camera: rotated by [yaw] (radians, about the vertical axis), scaled by [scale] frame units per cm and centred
     * at ([cx], [cy]) in frame space (y down). [mirror] flips x like a front camera preview.
     */
    fun landmarks(
        scale: Float = 0.03f,
        cx: Float = 0.3f,
        cy: Float = 0.5f,
        yaw: Float = 0f,
        mirror: Boolean = false,
    ): FloatArray {
        val out = FloatArray(FaceLandmarkIndex.LANDMARK_COUNT * 3)
        val c = cos(yaw); val s = sin(yaw)
        for (i in 0 until FaceLandmarkIndex.LANDMARK_COUNT) {
            // Iris points are not in the canonical model: place them at the eye centres.
            val src = when (i) {
                in 468..472 -> 159
                in 473..477 -> 386
                else -> i
            }
            val x = model.x(src); val y = model.y(src); val z = model.z(src)
            val rx = c * x + s * z
            val rz = -s * x + c * z
            val sx = if (mirror) -rx else rx
            out[i * 3] = cx + sx * scale
            out[i * 3 + 1] = cy - y * scale
            out[i * 3 + 2] = -rz * scale
        }
        return out
    }
}
