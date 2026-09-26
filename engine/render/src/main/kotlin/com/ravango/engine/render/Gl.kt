package com.ravango.engine.render

import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.ravango.core.common.log.RgLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

object GlUtil {
    val IDENTITY: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    fun checkGl(op: String) {
        val err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR) RgLog.e("GL", "$op: glError 0x${Integer.toHexString(err)}")
    }

    fun floatBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }

    fun createOesTexture(): Int {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return tex[0]
    }

    fun createTexture2D(width: Int, height: Int, linear: Boolean = true): Int {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        val filter = if (linear) GLES20.GL_LINEAR else GLES20.GL_NEAREST
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return tex[0]
    }

    fun deleteTexture(id: Int) {
        if (id != 0) GLES20.glDeleteTextures(1, intArrayOf(id), 0)
    }
}

/** Compiled + linked shader program with cached uniform/attribute locations. */
class GlProgram(vertexSource: String, fragmentSource: String) {
    val id: Int
    private val locations = HashMap<String, Int>()

    init {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, vs)
        GLES20.glAttachShader(id, fs)
        GLES20.glLinkProgram(id)
        val status = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(id)
            GLES20.glDeleteProgram(id)
            throw IllegalStateException("Program link failed: $log")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
    }

    fun use() = GLES20.glUseProgram(id)

    fun uniform(name: String): Int = locations.getOrPut(name) { GLES20.glGetUniformLocation(id, name) }
    fun attribute(name: String): Int = locations.getOrPut("a:$name") { GLES20.glGetAttribLocation(id, name) }

    fun setFloat(name: String, v: Float) { val l = uniform(name); if (l >= 0) GLES20.glUniform1f(l, v) }
    fun setInt(name: String, v: Int) { val l = uniform(name); if (l >= 0) GLES20.glUniform1i(l, v) }
    fun setVec2(name: String, x: Float, y: Float) { val l = uniform(name); if (l >= 0) GLES20.glUniform2f(l, x, y) }
    fun setVec3(name: String, x: Float, y: Float, z: Float) { val l = uniform(name); if (l >= 0) GLES20.glUniform3f(l, x, y, z) }
    fun setVec4(name: String, x: Float, y: Float, z: Float, w: Float) { val l = uniform(name); if (l >= 0) GLES20.glUniform4f(l, x, y, z, w) }
    fun setMat4(name: String, m: FloatArray) { val l = uniform(name); if (l >= 0) GLES20.glUniformMatrix4fv(l, 1, false, m, 0) }
    fun setVec2Array(name: String, values: FloatArray, count: Int) { val l = uniform(name); if (l >= 0 && count > 0) GLES20.glUniform2fv(l, count, values, 0) }
    fun setVec4Array(name: String, values: FloatArray, count: Int) { val l = uniform(name); if (l >= 0 && count > 0) GLES20.glUniform4fv(l, count, values, 0) }
    fun setFloatArray(name: String, values: FloatArray, count: Int) { val l = uniform(name); if (l >= 0 && count > 0) GLES20.glUniform1fv(l, count, values, 0) }

    fun bindTexture(name: String, unit: Int, textureId: Int, target: Int = GLES20.GL_TEXTURE_2D) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(target, textureId)
        setInt(name, unit)
    }

    fun release() = GLES20.glDeleteProgram(id)

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("Shader compile failed: $log\n$source")
        }
        return shader
    }
}

/** Framebuffer object backed by an RGBA texture. */
class GlFramebuffer(width: Int, height: Int, linear: Boolean = true) {
    var width: Int = width; private set
    var height: Int = height; private set
    var textureId: Int = 0; private set
    var framebufferId: Int = 0; private set
    private val linear = linear

    init { allocate() }

    private fun allocate() {
        textureId = GlUtil.createTexture2D(width, height, linear)
        val fb = IntArray(1)
        GLES20.glGenFramebuffers(1, fb, 0)
        framebufferId = fb[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebufferId)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, textureId, 0)
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        check(status == GLES20.GL_FRAMEBUFFER_COMPLETE) { "Framebuffer incomplete: 0x${Integer.toHexString(status)}" }
    }

    /** Reallocates if the size changed. */
    fun ensureSize(w: Int, h: Int) {
        if (w == width && h == height) return
        release()
        width = w; height = h
        allocate()
    }

    fun bind() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebufferId)
        GLES20.glViewport(0, 0, width, height)
    }

    fun release() {
        if (framebufferId != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(framebufferId), 0)
        GlUtil.deleteTexture(textureId)
        framebufferId = 0; textureId = 0
    }

    companion object {
        fun unbind() = GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }
}

/** A full-screen quad with position + texcoord attributes. */
object FullScreenQuad {
    private val positions = GlUtil.floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val texCoords = GlUtil.floatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

    /** Draws using attributes `aPosition` (vec2) and `aTexCoord` (vec2). */
    fun draw(program: GlProgram) {
        val pos = program.attribute("aPosition")
        val tex = program.attribute("aTexCoord")
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, positions)
        if (tex >= 0) {
            GLES20.glEnableVertexAttribArray(tex)
            GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(pos)
        if (tex >= 0) GLES20.glDisableVertexAttribArray(tex)
    }
}

/** Common shader sources. */
object Shaders {
    /** Vertex shader with a texture transform (`uTexMatrix`) and a position transform (`uMvpMatrix`). */
    const val VERTEX_TRANSFORM = """
        attribute vec4 aPosition;
        attribute vec4 aTexCoord;
        uniform mat4 uMvpMatrix;
        uniform mat4 uTexMatrix;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = uMvpMatrix * aPosition;
            vTexCoord = (uTexMatrix * aTexCoord).xy;
        }
    """

    const val VERTEX_SIMPLE = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
        }
    """

    const val FRAGMENT_OES = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTexCoord;
        uniform samplerExternalOES uTexture;
        void main() { gl_FragColor = texture2D(uTexture, vTexCoord); }
    """

    const val FRAGMENT_2D = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        void main() { gl_FragColor = texture2D(uTexture, vTexCoord); }
    """
}
