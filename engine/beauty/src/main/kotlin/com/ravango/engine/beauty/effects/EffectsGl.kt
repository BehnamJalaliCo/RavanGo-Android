package com.ravango.engine.beauty.effects

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import com.ravango.core.common.log.RgLog
import com.ravango.engine.beauty.mesh.FaceAssets
import com.ravango.engine.render.GlProgram
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Process-wide CPU caches for effect assets that are expensive to compute: filter LUTs (≈15 ms each) and the face
 * paint texture. Work runs on one low-priority background thread; the GL thread only polls and never waits.
 */
internal object EffectsAssets {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "rg-effects-assets").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }
    private val luts = ConcurrentHashMap<LiveFilter, ByteArray>()
    private val pending = ConcurrentHashMap.newKeySet<LiveFilter>()
    @Volatile var facePaint: ByteArray? = null; private set
    @Volatile private var paintRequested = false

    /** The LUT of [filter] when ready; otherwise schedules it and returns null. */
    fun lut(filter: LiveFilter): ByteArray? {
        if (filter == LiveFilter.NONE) return null
        luts[filter]?.let { return it }
        request(filter)
        return null
    }

    fun request(filter: LiveFilter) {
        if (filter == LiveFilter.NONE || luts.containsKey(filter) || !pending.add(filter)) return
        executor.execute {
            try {
                luts[filter] = LutGenerator.generate(filter)
            } catch (t: Throwable) {
                RgLog.e(TAG, "LUT generation failed for $filter", t)
            } finally {
                pending.remove(filter)
            }
        }
    }

    /** Face paint texture (needs the canonical mesh, loaded by FaceAssets). */
    fun paint(): ByteArray? {
        facePaint?.let { return it }
        val model = FaceAssets.loaded?.model ?: return null
        if (!paintRequested) {
            paintRequested = true
            executor.execute {
                try {
                    facePaint = FacePaint.generate(model)
                } catch (t: Throwable) {
                    RgLog.e(TAG, "Face paint generation failed", t)
                }
            }
        }
        return null
    }

    private const val TAG = "EffectsAssets"
}

/** GL textures of filter LUTs, uploaded when their bytes are ready; a small LRU keeps neighbours for swipes. */
internal class LutTexturesGl {
    private val filters = arrayOfNulls<LiveFilter>(SLOTS)
    private val ids = IntArray(SLOTS)
    private val stamps = LongArray(SLOTS)
    private var clock = 0L
    private var staging: ByteBuffer? = null

    /** Texture id of [filter]'s LUT, or 0 while it is being generated (the caller then skips the filter). */
    fun texture(filter: LiveFilter): Int {
        if (filter == LiveFilter.NONE) return 0
        for (i in 0 until SLOTS) if (filters[i] == filter) { stamps[i] = ++clock; return ids[i] }
        val bytes = EffectsAssets.lut(filter) ?: return 0
        var slot = 0
        for (i in 1 until SLOTS) if (stamps[i] < stamps[slot]) slot = i
        if (ids[slot] == 0) {
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            ids[slot] = t[0]
        }
        val buf = staging ?: ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).also { staging = it }
        buf.clear(); buf.put(bytes); buf.position(0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[slot])
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, LutGenerator.SIZE, LutGenerator.SIZE, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        linearClamp()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        filters[slot] = filter
        stamps[slot] = ++clock
        return ids[slot]
    }

    fun release() {
        for (i in 0 until SLOTS) if (ids[i] != 0) GLES20.glDeleteTextures(1, ids, i)
        ids.fill(0); filters.fill(null); stamps.fill(0)
        staging = null
    }

    private companion object { const val SLOTS = 4 }
}

/** A single-channel (luminance) texture updated from a byte buffer (segmentation mask). */
internal class MaskTextureGl {
    var id = 0; private set
    var width = 0; private set
    var height = 0; private set
    val ready: Boolean get() = id != 0 && width > 0

    fun upload(data: ByteBuffer, w: Int, h: Int) {
        if (id == 0) {
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            id = t[0]
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        if (w != width || h != height) {
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, w, h, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, data)
            linearClamp()
            width = w; height = h
        } else {
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, data)
        }
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    fun invalidate() { width = 0; height = 0 }

    fun release() {
        if (id != 0) GLES20.glDeleteTextures(1, intArrayOf(id), 0)
        id = 0; width = 0; height = 0
    }
}

/** RGBA texture from an android Bitmap (premultiplied, as GLUtils uploads it), optionally mipmapped. */
internal class BitmapTextureGl(private val mipmap: Boolean) {
    var id = 0; private set
    var width = 0; private set
    var height = 0; private set
    private var source: Any? = null

    /** Uploads [bitmap] when it differs from the last one uploaded. */
    fun ensure(bitmap: Bitmap?): Boolean {
        if (bitmap == null || bitmap.isRecycled) return id != 0 && source != null
        if (bitmap === source && id != 0) return true
        if (id == 0) {
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            id = t[0]
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, if (mipmap) GLES20.GL_LINEAR_MIPMAP_LINEAR else GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        if (mipmap) GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        source = bitmap
        width = bitmap.width
        height = bitmap.height
        return true
    }

    /** Uploads raw RGBA bytes (square [size]) once. */
    fun ensureRgba(bytes: ByteArray?, size: Int): Boolean {
        if (bytes == null) return id != 0
        if (bytes === source && id != 0) return true
        if (id == 0) {
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            id = t[0]
        }
        val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); position(0) }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, size, size, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, if (mipmap) GLES20.GL_LINEAR_MIPMAP_LINEAR else GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        if (mipmap) GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        source = bytes
        width = size; height = size
        return true
    }

    fun release() {
        if (id != 0) GLES20.glDeleteTextures(1, intArrayOf(id), 0)
        id = 0; source = null; width = 0; height = 0
    }
}

/** Draws a [SpriteBatch] with client-side arrays from a preallocated direct buffer. */
internal class SpriteDrawer(capacityFloats: Int) {
    private val buffer: FloatBuffer = ByteBuffer.allocateDirect(capacityFloats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    fun draw(program: GlProgram, batch: SpriteBatch, atlas: Int) {
        val n = batch.vertexCount
        if (n == 0) return
        buffer.clear()
        buffer.put(batch.data, 0, n * SpriteBatch.FLOATS_PER_VERTEX)
        program.use()
        program.bindTexture("uAtlas", 0, atlas)
        val stride = SpriteBatch.FLOATS_PER_VERTEX * 4
        val pos = program.attribute("aPosition")
        val tex = program.attribute("aTexCoord")
        val col = program.attribute("aColor")
        buffer.position(0)
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, stride, buffer)
        buffer.position(2)
        GLES20.glEnableVertexAttribArray(tex)
        GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, stride, buffer)
        buffer.position(4)
        GLES20.glEnableVertexAttribArray(col)
        GLES20.glVertexAttribPointer(col, 4, GLES20.GL_FLOAT, false, stride, buffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, n)
        GLES20.glDisableVertexAttribArray(pos)
        GLES20.glDisableVertexAttribArray(tex)
        GLES20.glDisableVertexAttribArray(col)
        buffer.position(0)
    }
}

private fun linearClamp() {
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
}
