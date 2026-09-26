package com.ravango.engine.render

import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.ravango.core.common.diagnostics.Diagnostics
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

/**
 * Compiled + linked shader program with cached uniform/attribute locations.
 *
 * A compile or link failure throws [GlProgramException] (callers on the GL thread catch it and degrade the effect to
 * pass-through) and is recorded in the on-device diagnostics with the program [name], the driver's info log and the
 * GL renderer, so a report from a real device says exactly which shader broke on which GPU.
 */
class GlProgram(vertexSource: String, fragmentSource: String, val name: String = "program") {
    val id: Int
    private val locations = HashMap<String, Int>()

    init {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fs = try {
            compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        } catch (t: Throwable) {
            GLES20.glDeleteShader(vs)
            throw t
        }
        id = GLES20.glCreateProgram()
        if (id == 0) {
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            fail("glCreateProgram returned 0 (glError 0x${Integer.toHexString(GLES20.glGetError())})", null)
        }
        GLES20.glAttachShader(id, vs)
        GLES20.glAttachShader(id, fs)
        GLES20.glBindAttribLocation(id, 0, "aPosition")
        GLES20.glLinkProgram(id)
        val status = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(id)
            GLES20.glDeleteProgram(id)
            fail("link failed: $log", null)
        }
    }

    private fun fail(what: String, source: String?): Nothing {
        val message = "Shader program '$name' $what"
        Diagnostics.record("GL", message + " [" + GlDiagnostics.rendererSummary() + "]" + (source?.let { "\n" + numbered(it) } ?: ""))
        throw GlProgramException(message)
    }

    private fun numbered(source: String): String =
        source.trimIndent().lines().mapIndexed { i, l -> "${i + 1}: $l" }.joinToString("\n").take(4_000)

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
        val kind = if (type == GLES20.GL_VERTEX_SHADER) "vertex" else "fragment"
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) fail("$kind glCreateShader returned 0 (glError 0x${Integer.toHexString(GLES20.glGetError())})", null)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            fail("$kind compile failed: $log", source)
        }
        return shader
    }

    companion object {
        /** Builds a program, or returns null (already recorded in diagnostics) when this GPU rejects it. */
        fun createOrNull(name: String, vertexSource: String, fragmentSource: String): GlProgram? = try {
            GlProgram(vertexSource, fragmentSource, name)
        } catch (e: GlProgramException) {
            null
        } catch (t: Throwable) {
            Diagnostics.record("GL", "Shader program '$name' could not be built", t)
            null
        }
    }
}

/** A shader program this GPU/driver rejected (details are in the diagnostics log). */
class GlProgramException(message: String) : IllegalStateException(message)

/**
 * Facts about the GL implementation for crash reports, and rate-limited GL error checks for the render loop.
 * Everything here must be called on a thread with a current EGL context.
 */
object GlDiagnostics {
    @Volatile private var renderer: String = "unknown GPU"

    /** Records GL vendor/renderer/version and limits in the diagnostics environment (call once per context). */
    fun captureContextInfo(glVersion: Int) {
        runCatching {
            val vendor = GLES20.glGetString(GLES20.GL_VENDOR).orEmpty()
            val name = GLES20.glGetString(GLES20.GL_RENDERER).orEmpty()
            val version = GLES20.glGetString(GLES20.GL_VERSION).orEmpty()
            val sl = GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION).orEmpty()
            val v = IntArray(1)
            fun int(pname: Int): Int { v[0] = 0; GLES20.glGetIntegerv(pname, v, 0); return v[0] }
            renderer = "$name · $version"
            Diagnostics.setEnv("gl.renderer", "$vendor $name")
            Diagnostics.setEnv("gl.version", "$version (context ES $glVersion) · GLSL $sl")
            Diagnostics.setEnv(
                "gl.limits",
                "maxTexture ${int(GLES20.GL_MAX_TEXTURE_SIZE)} · fragUnits ${int(GLES20.GL_MAX_TEXTURE_IMAGE_UNITS)} · " +
                    "fragUniformVec ${int(GLES20.GL_MAX_FRAGMENT_UNIFORM_VECTORS)} · varyings ${int(GLES20.GL_MAX_VARYING_VECTORS)} · " +
                    "maxRenderbuffer ${int(GLES20.GL_MAX_RENDERBUFFER_SIZE)}",
            )
            while (GLES20.glGetError() != GLES20.GL_NO_ERROR) Unit
        }
    }

    fun rendererSummary(): String = renderer

    /** Drains the GL error queue; records the first error (rate-limited by [Diagnostics]). Returns the error or 0. */
    fun drainErrors(where: String): Int {
        var first = GLES20.GL_NO_ERROR
        var guard = 0
        while (guard++ < 8) {
            val err = GLES20.glGetError()
            if (err == GLES20.GL_NO_ERROR) break
            if (first == GLES20.GL_NO_ERROR) first = err
        }
        if (first != GLES20.GL_NO_ERROR) Diagnostics.record("GL", "glError 0x${Integer.toHexString(first)} after $where [$renderer]")
        return first
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
