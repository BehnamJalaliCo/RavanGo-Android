package com.ravango.engine.beauty.makeup

import android.opengl.GLES20
import com.ravango.engine.beauty.mesh.FaceMeshModel
import com.ravango.engine.render.GlProgram
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ADDED — GL side of look styles for the makeup pass (GL thread only): uploads the [LookAtlas] once when it is
 * ready (textures are reused for the whole attachment), caches the [MakeupStyle] as floats when the style object
 * changes, and sets the style uniforms of [MakeupShaders.MAKEUP]. Per-frame work does not allocate.
 */
internal class MakeupStyleGl {

    private val ids = IntArray(2)
    private val ready: Boolean get() = ids[0] != 0
    private var cached: MakeupStyle? = null
    private var needsAtlas = false

    // Cached uniform values (unscaled by presence).
    private var wingCut = 0f
    private var wingLong = 0f
    private var lashVolume = 0f
    private var kohl = 0f
    private var browDef = 0f
    private var lipFinish = 0f
    private var skinFinish = 0f
    private var freckles = 0f
    private val shadow2 = FloatArray(4)
    private val shimmer = FloatArray(4)
    private val lipCenter = FloatArray(4)

    /**
     * Binds the look atlas (units [unitD], [unitE]) for [style], starting its generation from [model] the first time
     * a non-default style is used. Call once per makeup pass, after `use()`.
     */
    fun bind(p: GlProgram, style: MakeupStyle, model: FaceMeshModel?, unitD: Int, unitE: Int) {
        if (style !== cached) sync(style)
        if (!ready && needsAtlas) {
            val atlas = LookAtlas.textures
            if (atlas != null) upload(atlas) else if (model != null) LookAtlas.prepare(model)
        }
        val on = ready && needsAtlas
        p.setFloat("uStyleOn", if (on) 1f else 0f)
        if (on) {
            p.bindTexture("uMakeupD", unitD, ids[0])
            p.bindTexture("uMakeupE", unitE, ids[1])
        }
    }

    /** Per-face style uniforms; amounts fade with the face's [presence] like every makeup layer. */
    fun setFace(p: GlProgram, presence: Float) {
        p.setVec4("uStyleA", wingCut, wingLong, lashVolume, kohl * presence)
        p.setVec4("uStyleB", browDef, lipFinish, skinFinish * presence, freckles * presence)
        p.setVec4("uShadow2", shadow2[0], shadow2[1], shadow2[2], shadow2[3] * presence)
        p.setVec4("uShimmer", shimmer[0], shimmer[1], shimmer[2], shimmer[3] * presence)
        p.setVec4("uLipCenter", lipCenter[0], lipCenter[1], lipCenter[2], lipCenter[3])
    }

    private fun sync(style: MakeupStyle) {
        cached = style
        needsAtlas = style.needsLookAtlas
        val w = style.wing.coerceIn(0f, 1f)
        wingCut = (1f - w * 2f).coerceIn(0f, 1f)
        wingLong = (w * 2f - 1f).coerceIn(0f, 1f)
        lashVolume = style.lashVolume.coerceIn(0f, 1f)
        kohl = style.lowerLiner.coerceIn(0f, 1f)
        browDef = style.browDefinition.coerceIn(0f, 1f)
        lipFinish = style.lipFinish.coerceIn(-1f, 1f)
        skinFinish = style.skinFinish.coerceIn(-1f, 1f)
        freckles = style.freckles.coerceIn(0f, 1f)
        rgba(style.shadowAccent, style.shadowAccentAmount, shadow2)
        rgba(style.shimmerColor, style.shimmer, shimmer)
        rgba(style.lipCenter, style.lipCenterAmount, lipCenter)
    }

    private fun rgba(argb: Long, amount: Float, out: FloatArray) {
        out[0] = ((argb shr 16) and 0xFF) / 255f
        out[1] = ((argb shr 8) and 0xFF) / 255f
        out[2] = (argb and 0xFF) / 255f
        out[3] = amount.coerceIn(0f, 1f)
    }

    private fun upload(atlas: LookAtlas.Textures) {
        release()
        GLES20.glGenTextures(2, ids, 0)
        load(ids[0], atlas.d, atlas.sizeD, mipmap = true)
        load(ids[1], atlas.e, atlas.sizeE, mipmap = false)
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
        if (ids[0] != 0) GLES20.glDeleteTextures(2, ids, 0)
        ids.fill(0)
    }
}
