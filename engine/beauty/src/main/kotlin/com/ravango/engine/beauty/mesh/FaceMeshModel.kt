package com.ravango.engine.beauty.mesh

/**
 * The canonical face model (MediaPipe `canonical_face_model.obj`, Apache-2.0): 468 vertices in landmark order with
 * their metric rest positions (centimetres, x → subject's left, y up, z towards the camera), a UV coordinate per
 * vertex and the triangulation shared by every tracked face.
 *
 * The UV layout is a frontal unwrap of the face (u grows towards the subject's left, v grows upwards). Makeup
 * textures are authored in this space, exactly like a Lens Studio "Face Mask": whatever is painted at a UV
 * position sticks to the same spot of the skin however the face moves.
 */
class FaceMeshModel(
    /** x, y, z per vertex. */
    val positions: FloatArray,
    /** u, v per vertex. */
    val uvs: FloatArray,
    /** Vertex indices, three per triangle, consistently wound. */
    val triangles: IntArray,
) {
    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = triangles.size / 3

    fun u(i: Int): Float = uvs[i * 2]
    fun v(i: Int): Float = uvs[i * 2 + 1]
    fun x(i: Int): Float = positions[i * 3]
    fun y(i: Int): Float = positions[i * 3 + 1]
    fun z(i: Int): Float = positions[i * 3 + 2]

    init {
        require(positions.size % 3 == 0 && uvs.size / 2 == positions.size / 3) { "positions/uvs mismatch" }
        require(triangles.size % 3 == 0) { "triangle list is not a multiple of 3" }
    }
}

/**
 * Minimal Wavefront OBJ reader for the canonical face model: `v x y z`, `vt u v` and `f` records whose corners
 * are `v`, `v/vt`, `v/vt/vn` or `v//vn`. Polygons with more than three corners are fan-triangulated.
 *
 * OBJ stores texture coordinates independently of positions; the face model maps each vertex to exactly one UV,
 * which is resolved here into a per-vertex UV array (a vertex referenced with two different UVs is rejected, since
 * the renderer shares one UV per landmark).
 */
object ObjParser {

    fun parse(text: String): FaceMeshModel = parse(text.lineSequence())

    fun parse(lines: Sequence<String>): FaceMeshModel {
        val positions = FloatList()
        val texCoords = FloatList()
        val corners = IntList() // (vertex, uv) pairs, already triangulated
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line[0] == '#') continue
            val parts = line.split(WHITESPACE)
            when (parts[0]) {
                "v" -> {
                    require(parts.size >= 4) { "Malformed vertex: $line" }
                    positions.add(parts[1].toFloat()); positions.add(parts[2].toFloat()); positions.add(parts[3].toFloat())
                }
                "vt" -> {
                    require(parts.size >= 3) { "Malformed texture coordinate: $line" }
                    texCoords.add(parts[1].toFloat()); texCoords.add(parts[2].toFloat())
                }
                "f" -> {
                    require(parts.size >= 4) { "Face with fewer than 3 corners: $line" }
                    val n = parts.size - 1
                    val v = IntArray(n)
                    val t = IntArray(n)
                    for (k in 0 until n) {
                        val fields = parts[k + 1].split('/')
                        v[k] = resolve(fields[0].toInt(), positions.size / 3)
                        t[k] = if (fields.size > 1 && fields[1].isNotEmpty()) resolve(fields[1].toInt(), texCoords.size / 2) else -1
                    }
                    for (k in 1 until n - 1) {
                        corners.add(v[0]); corners.add(t[0])
                        corners.add(v[k]); corners.add(t[k])
                        corners.add(v[k + 1]); corners.add(t[k + 1])
                    }
                }
                else -> Unit // vn, o, g, s, mtllib… are irrelevant here.
            }
        }
        val vertexCount = positions.size / 3
        require(vertexCount > 0) { "OBJ has no vertices" }
        val uvs = FloatArray(vertexCount * 2)
        val uvOf = IntArray(vertexCount) { -1 }
        val triangles = IntArray(corners.size / 2)
        for (c in 0 until corners.size / 2) {
            val vi = corners[c * 2]
            val ti = corners[c * 2 + 1]
            triangles[c] = vi
            if (ti < 0) continue
            val prev = uvOf[vi]
            if (prev < 0) {
                uvOf[vi] = ti
                uvs[vi * 2] = texCoords[ti * 2]
                uvs[vi * 2 + 1] = texCoords[ti * 2 + 1]
            } else if (prev != ti) {
                val du = texCoords[prev * 2] - texCoords[ti * 2]
                val dv = texCoords[prev * 2 + 1] - texCoords[ti * 2 + 1]
                require(du * du + dv * dv < 1e-10f) { "Vertex $vi has more than one UV" }
            }
        }
        return FaceMeshModel(positions.toArray(), uvs, triangles)
    }

    /** OBJ indices are 1-based; negative indices count back from the end. */
    private fun resolve(index: Int, count: Int): Int {
        val i = if (index < 0) count + index else index - 1
        require(i in 0 until count) { "Index $index out of range ($count)" }
        return i
    }

    private val WHITESPACE = Regex("\\s+")

    private class FloatList {
        private var data = FloatArray(1024)
        var size = 0; private set
        fun add(v: Float) { if (size == data.size) data = data.copyOf(size * 2); data[size++] = v }
        operator fun get(i: Int) = data[i]
        fun toArray() = data.copyOf(size)
    }

    private class IntList {
        private var data = IntArray(4096)
        var size = 0; private set
        fun add(v: Int) { if (size == data.size) data = data.copyOf(size * 2); data[size++] = v }
        operator fun get(i: Int) = data[i]
    }
}
