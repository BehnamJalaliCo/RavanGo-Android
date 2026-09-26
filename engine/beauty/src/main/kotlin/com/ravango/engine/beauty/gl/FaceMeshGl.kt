package com.ravango.engine.beauty.gl

import android.opengl.GLES20
import com.ravango.engine.beauty.mesh.FaceTopology
import com.ravango.engine.beauty.makeup.MakeupAtlas
import com.ravango.engine.render.GlProgram
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * GPU buffers for tracked face meshes: a static UV buffer and static index buffers (surface, mouth opening, eye
 * openings, warp surface incl. padded ring), plus per face slot a dynamic position VBO (tracked mesh) and a
 * dynamic warp VBO (deformed mesh). Positions are streamed each frame with `glBufferSubData` from a preallocated
 * direct buffer (no allocation). Everything is unbound after each draw so client-side-array passes stay valid.
 */
internal class FaceMeshGl(private val topology: FaceTopology, slots: Int) {

    private val vertexCount = topology.totalVertexCount
    private val uvVbo = IntArray(1)
    private val posVbo = IntArray(slots)
    private val warpVbo = IntArray(slots)
    private val ibo = IntArray(4)
    private val iboCount = IntArray(4)
    private val staging: FloatBuffer = ByteBuffer.allocateDirect(vertexCount * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    fun create() {
        GLES20.glGenBuffers(1, uvVbo, 0)
        GLES20.glGenBuffers(posVbo.size, posVbo, 0)
        GLES20.glGenBuffers(warpVbo.size, warpVbo, 0)
        GLES20.glGenBuffers(4, ibo, 0)

        // UVs: mesh vertices use the canonical UVs; ring vertices get UV 0 (never drawn with makeup).
        val uv = FloatArray(vertexCount * 2)
        System.arraycopy(topology.model.uvs, 0, uv, 0, topology.meshVertexCount * 2)
        val uvBuf = ByteBuffer.allocateDirect(uv.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(uv); position(0) }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, uvVbo[0])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, uv.size * 4, uvBuf, GLES20.GL_STATIC_DRAW)
        for (id in posVbo) allocDynamic(id)
        for (id in warpVbo) allocDynamic(id)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        upload(SURFACE, topology.surfaceTriangles)
        upload(MOUTH, topology.mouthHoleTriangles)
        upload(EYES, topology.eyeHoleTriangles)
        upload(WARP, topology.warpTriangles)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    private fun allocDynamic(id: Int) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, id)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertexCount * 3 * 4, null, GLES20.GL_DYNAMIC_DRAW)
    }

    private fun upload(slot: Int, indices: IntArray) {
        val buf: ShortBuffer = ByteBuffer.allocateDirect(indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        for (i in indices) buf.put(i.toShort())
        buf.position(0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo[slot])
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, indices.size * 2, buf, GLES20.GL_STATIC_DRAW)
        iboCount[slot] = indices.size
    }

    /** Streams (x, y, z) × vertexCount of the tracked mesh for face [slot]. */
    fun uploadMesh(slot: Int, positions: FloatArray) = stream(posVbo[slot], positions)

    /** Streams the deformed mesh for face [slot]. */
    fun uploadWarp(slot: Int, positions: FloatArray) = stream(warpVbo[slot], positions)

    private fun stream(id: Int, positions: FloatArray) {
        staging.clear()
        staging.put(positions, 0, vertexCount * 3)
        staging.position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, id)
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, vertexCount * 3 * 4, staging)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    /** Draws a triangle set of the tracked mesh of [slot] with [program] (attributes `aPosition`, `aUv`). */
    fun drawMesh(program: GlProgram, slot: Int, set: Int) {
        val pos = program.attribute("aPosition")
        val uv = program.attribute("aUv")
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, posVbo[slot])
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 3, GLES20.GL_FLOAT, false, 12, 0)
        if (uv >= 0) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, uvVbo[0])
            GLES20.glEnableVertexAttribArray(uv)
            GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 8, 0)
        }
        drawElements(set)
        GLES20.glDisableVertexAttribArray(pos)
        if (uv >= 0) GLES20.glDisableVertexAttribArray(uv)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    /** Draws the warp surface of [slot]: deformed positions (`aPosition`) sampling the tracked ones (`aSource`). */
    fun drawWarp(program: GlProgram, slot: Int) {
        val pos = program.attribute("aPosition")
        val src = program.attribute("aSource")
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, warpVbo[slot])
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 3, GLES20.GL_FLOAT, false, 12, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, posVbo[slot])
        GLES20.glEnableVertexAttribArray(src)
        GLES20.glVertexAttribPointer(src, 3, GLES20.GL_FLOAT, false, 12, 0)
        drawElements(WARP)
        GLES20.glDisableVertexAttribArray(pos)
        GLES20.glDisableVertexAttribArray(src)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun drawElements(set: Int) {
        if (iboCount[set] == 0) return
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo[set])
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, iboCount[set], GLES20.GL_UNSIGNED_SHORT, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    fun release() {
        if (uvVbo[0] != 0) GLES20.glDeleteBuffers(1, uvVbo, 0)
        if (posVbo.isNotEmpty() && posVbo[0] != 0) GLES20.glDeleteBuffers(posVbo.size, posVbo, 0)
        if (warpVbo.isNotEmpty() && warpVbo[0] != 0) GLES20.glDeleteBuffers(warpVbo.size, warpVbo, 0)
        if (ibo[0] != 0) GLES20.glDeleteBuffers(4, ibo, 0)
        uvVbo.fill(0); posVbo.fill(0); warpVbo.fill(0); ibo.fill(0)
    }

    companion object {
        const val SURFACE = 0
        const val MOUTH = 1
        const val EYES = 2
        const val WARP = 3
    }
}

/** The three UV-space makeup textures on the GPU (A with mipmaps: lashes and brow hairs stay clean when small). */
internal class MakeupTexturesGl {
    val ids = IntArray(3)
    val ready: Boolean get() = ids[0] != 0

    fun upload(atlas: MakeupAtlas.Textures) {
        release()
        GLES20.glGenTextures(3, ids, 0)
        load(ids[0], atlas.a, atlas.sizeA, mipmap = true)
        load(ids[1], atlas.b, atlas.sizeB, mipmap = false)
        load(ids[2], atlas.c, atlas.sizeC, mipmap = false)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    private fun load(id: Int, rgba: ByteArray, size: Int, mipmap: Boolean) {
        val buf = ByteBuffer.allocateDirect(rgba.size).order(ByteOrder.nativeOrder()).apply { put(rgba); position(0) }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, size, size, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, if (mipmap) GLES20.GL_LINEAR_MIPMAP_LINEAR else GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        if (mipmap) GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
    }

    fun release() {
        if (ids[0] != 0) GLES20.glDeleteTextures(3, ids, 0)
        ids.fill(0)
    }
}

/**
 * A depth renderbuffer shared by the full-resolution output framebuffers, so the face mesh is depth-tested
 * (a turned head's far cheek never paints over the nose). Attachment is re-done when a framebuffer is recreated.
 */
internal class DepthBufferGl {
    private val rb = IntArray(1)
    private var width = 0
    private var height = 0
    private val attachedTo = IntArray(4)
    private var attachedCount = 0
    var broken = false; private set

    /** Ensures [framebufferId] (currently bound or not) has the shared depth buffer. Returns false if unsupported. */
    fun attach(framebufferId: Int, w: Int, h: Int): Boolean {
        if (broken) return false
        if (rb[0] == 0 || w != width || h != height) {
            if (rb[0] != 0) GLES20.glDeleteRenderbuffers(1, rb, 0)
            GLES20.glGenRenderbuffers(1, rb, 0)
            GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, rb[0])
            GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16, w, h)
            GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, 0)
            width = w; height = h
            attachedCount = 0
        }
        for (k in 0 until attachedCount) if (attachedTo[k] == framebufferId) return true
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebufferId)
        GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT, GLES20.GL_RENDERBUFFER, rb[0])
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT, GLES20.GL_RENDERBUFFER, 0)
            broken = true
            return false
        }
        if (attachedCount == attachedTo.size) attachedCount = 0
        attachedTo[attachedCount++] = framebufferId
        return true
    }

    fun release() {
        if (rb[0] != 0) GLES20.glDeleteRenderbuffers(1, rb, 0)
        rb[0] = 0
        width = 0; height = 0
        attachedCount = 0
        broken = false
    }
}
