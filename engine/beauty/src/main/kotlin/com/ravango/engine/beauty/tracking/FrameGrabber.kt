package com.ravango.engine.beauty.tracking

import android.opengl.GLES20
import android.opengl.GLES30
import com.ravango.core.common.log.RgLog
import com.ravango.engine.render.FullScreenQuad
import com.ravango.engine.render.GlFramebuffer
import com.ravango.engine.render.GlProgram
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pulls small RGBA copies of the pipeline frame back to the CPU for face tracking, without an extra camera
 * stream. The frame is rendered downscaled and **y-flipped** into a small FBO so rows come out top-down, i.e. an
 * upright image as the landmarker expects (a mirrored front-camera frame is simply a mirrored face, which the
 * model handles; left/right landmark semantics stay self-consistent).
 *
 * - GLES 3: asynchronous readback through two pixel-pack buffers — the read is issued on one frame and mapped on
 *   a later one, guarded by a fence sync: the buffer is only mapped once the GPU signalled the copy complete
 *   ([pendingReady], polled with a zero timeout), so the GL thread never stalls in `glMapBufferRange`.
 * - GLES 2 (or if mapping fails): synchronous `glReadPixels`, which the caller rate-limits.
 */
internal class FrameGrabber(glVersion: Int) {

    private var usePbo = glVersion >= 3
    private var fbo: GlFramebuffer? = null
    private val pbos = IntArray(2)
    private var writeIndex = 0
    private var pendingIndex = -1
    private var fence = 0L

    var width = 0; private set
    var height = 0; private set
    /** Frame time of the pending (async) readback. */
    var pendingTimestampNs = 0L; private set

    val isAsync: Boolean get() = usePbo
    val hasPending: Boolean get() = pendingIndex >= 0

    /** Chooses the readback size for a frame of [frameW]×[frameH] with target width [targetWidth]. */
    fun configure(frameW: Int, frameH: Int, targetWidth: Int) {
        val w = minOf(targetWidth, frameW).coerceAtLeast(16) and 0x7FFFFFFC
        val h = (w.toLong() * frameH / frameW).toInt().coerceAtLeast(16) and 0x7FFFFFFE
        if (w == width && h == height && fbo != null) return
        width = w; height = h
        fbo?.ensureSize(w, h) ?: run { fbo = GlFramebuffer(w, h) }
        if (usePbo) allocatePbos(w * h * 4)
        pendingIndex = -1
    }

    private fun render(textureId: Int, program: GlProgram): Boolean {
        val target = fbo ?: return false
        target.bind()
        program.use()
        program.bindTexture("uTexture", 0, textureId)
        program.setFloat("uFlipY", 1f)
        program.setVec2("uOffset", 0.25f / width, 0.25f / height)
        FullScreenQuad.draw(program)
        program.setFloat("uFlipY", 0f)
        return true
    }

    /** Async mode: renders [textureId] and issues a PBO read, collected by a later [collect]. */
    fun startAsync(textureId: Int, program: GlProgram, timestampNs: Long) {
        if (!usePbo || !render(textureId, program)) return
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbos[writeIndex])
        GLES30.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, 0)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        deleteFence()
        fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        pendingIndex = writeIndex
        pendingTimestampNs = timestampNs
        writeIndex = 1 - writeIndex
    }

    /** Sync mode: renders [textureId] and reads it into [dest] (capacity ≥ width × height × 4). */
    fun readSync(textureId: Int, program: GlProgram, dest: ByteBuffer): Boolean {
        if (!render(textureId, program)) return false
        dest.position(0)
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, dest)
        dest.position(0)
        return true
    }

    /**
     * Async mode: true when the pending read has completed on the GPU (non-blocking poll of the fence). Without fence
     * support it reports ready, and mapping may then wait for the (one frame old) copy.
     */
    fun pendingReady(): Boolean {
        if (!usePbo || pendingIndex < 0) return false
        if (fence == 0L) return true
        val r = GLES30.glClientWaitSync(fence, GLES30.GL_SYNC_FLUSH_COMMANDS_BIT, 0L)
        return r == GLES30.GL_ALREADY_SIGNALED || r == GLES30.GL_CONDITION_SATISFIED || r == GLES30.GL_WAIT_FAILED
    }

    /** Async mode: maps the pending pixel buffer and copies it into [dest]. Returns true if pixels are ready. */
    fun collect(dest: ByteBuffer): Boolean {
        if (!usePbo || pendingIndex < 0) return false
        val index = pendingIndex
        pendingIndex = -1
        deleteFence()
        val bytes = width * height * 4
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbos[index])
        val mapped = GLES30.glMapBufferRange(GLES30.GL_PIXEL_PACK_BUFFER, 0, bytes, GLES30.GL_MAP_READ_BIT) as? ByteBuffer
        var ok = false
        if (mapped != null) {
            mapped.order(ByteOrder.nativeOrder())
            mapped.position(0)
            mapped.limit(bytes)
            dest.position(0)
            dest.put(mapped)
            dest.position(0)
            GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
            ok = true
        }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        if (!ok) {
            // Some drivers cannot map pack buffers: fall back to synchronous reads for the rest of the session.
            RgLog.w(TAG, "glMapBufferRange failed; falling back to synchronous readback")
            releasePbos()
            usePbo = false
        }
        return ok
    }

    /** Drops a pending async read (e.g. when the tracker could not take it). */
    fun discardPending() {
        pendingIndex = -1
        deleteFence()
    }

    private fun deleteFence() {
        if (fence != 0L) {
            GLES30.glDeleteSync(fence)
            fence = 0L
        }
    }

    fun release() {
        deleteFence()
        releasePbos()
        fbo?.release()
        fbo = null
        width = 0; height = 0
        pendingIndex = -1
    }

    private fun allocatePbos(bytes: Int) {
        if (pbos[0] == 0) GLES30.glGenBuffers(2, pbos, 0)
        for (id in pbos) {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, id)
            GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, bytes, null, GLES30.GL_STREAM_READ)
        }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
    }

    private fun releasePbos() {
        if (pbos[0] != 0) {
            GLES30.glDeleteBuffers(2, pbos, 0)
            pbos[0] = 0; pbos[1] = 0
        }
    }

    companion object {
        private const val TAG = "FrameGrabber"
    }
}
