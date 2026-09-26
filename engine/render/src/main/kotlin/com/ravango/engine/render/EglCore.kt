package com.ravango.engine.render

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface

/**
 * Owns an EGL display + context (GLES 3 with GLES 2 fallback). The config is "recordable" so the same context
 * can render into MediaCodec input surfaces. Not thread-safe: use from a single GL thread.
 */
class EglCore(
    sharedContext: EGLContext = EGL14.EGL_NO_CONTEXT,
    /** Highest GLES version to try (tests pass 2 to exercise the GLES 2 path on a GLES 3 device). */
    maxVersion: Int = 3,
) {

    val display: EGLDisplay
    val context: EGLContext
    val config: EGLConfig
    val glVersion: Int

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

        var chosen: EGLConfig? = null
        var ctx: EGLContext = EGL14.EGL_NO_CONTEXT
        var ver = 0
        for (v in intArrayOf(3, 2).filter { it <= maxVersion }) {
            val cfg = chooseConfig(v) ?: continue
            val attrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, v, EGL14.EGL_NONE)
            val c = EGL14.eglCreateContext(display, cfg, sharedContext, attrs, 0)
            if (c != null && c != EGL14.EGL_NO_CONTEXT && EGL14.eglGetError() == EGL14.EGL_SUCCESS) {
                chosen = cfg; ctx = c; ver = v
                break
            }
        }
        checkNotNull(chosen) { "No suitable EGL config" }
        config = chosen
        context = ctx
        glVersion = ver
    }

    private fun chooseConfig(version: Int): EGLConfig? {
        val renderable = if (version >= 3) EGLExt.EGL_OPENGL_ES3_BIT_KHR else EGL14.EGL_OPENGL_ES2_BIT
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, renderable,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) || num[0] == 0) return null
        return configs[0]
    }

    fun createWindowSurface(surface: Surface): EGLSurface {
        val s = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        checkEgl("eglCreateWindowSurface")
        return s
    }

    fun createOffscreenSurface(width: Int, height: Int): EGLSurface {
        val s = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0)
        checkEgl("eglCreatePbufferSurface")
        return s
    }

    fun makeCurrent(surface: EGLSurface) {
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) throw IllegalStateException("eglMakeCurrent failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
    }

    fun makeNothingCurrent() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
    }

    fun swapBuffers(surface: EGLSurface): Boolean = EGL14.eglSwapBuffers(display, surface)

    /** Sets the presentation time used by MediaCodec encoder surfaces. */
    fun setPresentationTime(surface: EGLSurface, nanos: Long) {
        EGLExt.eglPresentationTimeANDROID(display, surface, nanos)
    }

    fun querySurface(surface: EGLSurface, what: Int): Int {
        val v = IntArray(1)
        EGL14.eglQuerySurface(display, surface, what, v, 0)
        return v[0]
    }

    fun releaseSurface(surface: EGLSurface) {
        EGL14.eglDestroySurface(display, surface)
    }

    fun release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
    }

    private fun checkEgl(op: String) {
        val err = EGL14.eglGetError()
        if (err != EGL14.EGL_SUCCESS) throw IllegalStateException("$op: EGL error 0x${Integer.toHexString(err)}")
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
