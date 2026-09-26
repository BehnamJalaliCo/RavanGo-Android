package com.ravango.engine.beauty.gl

import android.opengl.EGL14
import android.opengl.GLES20
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import com.ravango.engine.render.EglCore
import com.ravango.engine.render.GlProgram
import com.ravango.engine.render.Shaders
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compiles and links every program of the camera + beauty/effects pipeline on the device's real GL driver, in a
 * GLES 3 context and in a GLES 2 context. A failure lists the program name and the driver's info log.
 */
@RunWith(AndroidJUnit4::class)
class ShaderProgramsTest {

    private val programs = ShaderCatalog.all() + listOf(
        ShaderCatalog.Program("camera.oes", Shaders.VERTEX_TRANSFORM, Shaders.FRAGMENT_OES),
        ShaderCatalog.Program("camera.copy", Shaders.VERTEX_TRANSFORM, Shaders.FRAGMENT_2D),
    )

    @Test
    fun everyProgramBuildsOnGles3() = buildAll(maxVersion = 3)

    @Test
    fun everyProgramBuildsOnGles2() = buildAll(maxVersion = 2)

    private fun buildAll(maxVersion: Int) {
        val egl = EglCore(maxVersion = maxVersion)
        val surface = egl.createOffscreenSurface(16, 16)
        try {
            egl.makeCurrent(surface)
            val renderer = GLES20.glGetString(GLES20.GL_RENDERER)
            val failures = programs.mapNotNull { p ->
                try {
                    GlProgram(p.vertex, p.fragment, p.name).release()
                    null
                } catch (t: Throwable) {
                    "${p.name}: ${t.message}"
                }
            }
            assertWithMessage("GLES ${egl.glVersion} on $renderer:\n" + failures.joinToString("\n")).that(failures).isEmpty()
        } finally {
            egl.makeNothingCurrent()
            if (surface != EGL14.EGL_NO_SURFACE) egl.releaseSurface(surface)
            egl.release()
        }
    }
}
