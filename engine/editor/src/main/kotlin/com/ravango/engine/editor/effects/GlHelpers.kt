package com.ravango.engine.editor.effects

import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil

/*
 * Shared GL plumbing for the editor's custom Media3 effects.
 *
 * All shaders are GLSL ES 1.00 so they run in both the GLES2 and GLES3 contexts Media3 creates. Texture coordinates
 * follow the Media3 convention: (0,0) is the bottom-left of the upright frame.
 */
internal object GlSl {
    const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vTexCoord;
void main() {
  gl_Position = aFramePosition;
  vTexCoord = aFramePosition.xy * 0.5 + 0.5;
}
"""

    /** Hash-based noise used for film grain (no texture needed). */
    const val NOISE = """
float rgHash(vec2 p) {
  p = fract(p * vec2(123.34, 456.21));
  p += dot(p, p + 45.32);
  return fract(p.x * p.y);
}
"""

    fun program(fragment: String): GlProgram = try {
        GlProgram(VERTEX, fragment)
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }

    /** Binds the full-screen quad for [program] and draws it. */
    fun drawQuad(program: GlProgram) {
        program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        program.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }
}

/** An offscreen RGBA texture + framebuffer used for multi-pass effects. */
internal class RenderTarget(val width: Int, val height: Int) {
    val texId: Int = GlUtil.createTexture(width, height, false)
    val fboId: Int = GlUtil.createFboForTexture(texId)

    fun focus() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glViewport(0, 0, width, height)
    }

    fun release() {
        runCatching { GlUtil.deleteFbo(fboId) }
        runCatching { GlUtil.deleteTexture(texId) }
    }
}

/** Captures and restores the framebuffer/viewport the Media3 base program focused before calling drawFrame. */
internal class FramebufferState {
    private val fbo = IntArray(1)
    private val viewport = IntArray(4)

    fun save() {
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, fbo, 0)
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0)
    }

    fun restore() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
    }
}

/** Color helpers for passing ARGB longs to shaders. */
internal fun Long.argbToFloats(): FloatArray {
    val v = this and 0xFFFFFFFFL
    return floatArrayOf(((v shr 16) and 0xFF) / 255f, ((v shr 8) and 0xFF) / 255f, (v and 0xFF) / 255f, ((v shr 24) and 0xFF) / 255f)
}
