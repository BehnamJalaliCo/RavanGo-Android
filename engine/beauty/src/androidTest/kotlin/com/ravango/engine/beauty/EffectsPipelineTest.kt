package com.ravango.engine.beauty

import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES20
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.ravango.core.model.BeautyFeature
import com.ravango.core.model.BeautyState
import com.ravango.engine.beauty.effects.EffectsState
import com.ravango.engine.beauty.effects.FilterSwipe
import com.ravango.engine.beauty.effects.Lens
import com.ravango.engine.beauty.effects.LiveFilter
import com.ravango.engine.beauty.quality.TierHint
import com.ravango.engine.render.EglCore
import com.ravango.engine.render.GlProcessingContext
import com.ravango.engine.render.GlTextureFrame
import com.ravango.engine.render.GlUtil
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.random.Random

/**
 * Runs the real beauty + effects processor headless (pbuffer context, synthetic skin-toned frame) and proves that each
 * face-independent Camera Studio control changes the output pixels, that "nothing selected" bypasses, and that no GPU
 * pass had to be switched off on this device. Face lenses and background effects need a real face / person and are
 * covered by the plumbing tests plus a device run.
 */
@RunWith(AndroidJUnit4::class)
class EffectsPipelineTest {

    private lateinit var egl: EglCore
    private lateinit var surface: EGLSurface
    private lateinit var controls: BeautyControls
    private lateinit var processor: BeautyProcessor
    private var inputTex = 0
    private var readFbo = 0
    private var frameNs = 1_000_000_000L

    @Before
    fun setUp() {
        egl = EglCore()
        surface = egl.createOffscreenSurface(16, 16)
        egl.makeCurrent(surface)
        inputTex = GlUtil.createTexture2D(W, H)
        val rnd = Random(7)
        val pixels = ByteBuffer.allocateDirect(W * H * 4).order(ByteOrder.nativeOrder())
        for (y in 0 until H) for (x in 0 until W) {
            // Skin tone with fine noise (pores) and a soft gradient, so skin smoothing has something to remove.
            val n = rnd.nextInt(-22, 23)
            val g = (y * 40 / H)
            pixels.put((200 + n - g).coerceIn(0, 255).toByte())
            pixels.put((150 + n - g).coerceIn(0, 255).toByte())
            pixels.put((125 + n - g).coerceIn(0, 255).toByte())
            pixels.put(255.toByte())
        }
        pixels.position(0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, W, H, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        val fb = IntArray(1)
        GLES20.glGenFramebuffers(1, fb, 0)
        readFbo = fb[0]
        controls = BeautyControls(TierHint.HIGH)
        controls.state = BeautyState.Off
        processor = BeautyProcessor(controls, InstrumentationRegistry.getInstrumentation().targetContext)
        val max = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, max, 0)
        processor.onAttach(GlProcessingContext(egl.glVersion, max[0]))
    }

    @After
    fun tearDown() {
        processor.onDetach()
        GLES20.glDeleteFramebuffers(1, intArrayOf(readFbo), 0)
        GlUtil.deleteTexture(inputTex)
        egl.makeNothingCurrent()
        if (surface != EGL14.EGL_NO_SURFACE) egl.releaseSurface(surface)
        egl.release()
    }

    private fun frame() = GlTextureFrame(inputTex, W, H, frameNs.also { frameNs += 33_333_333L }, mirrored = false)

    /** Runs frames until the output settles (LUTs are generated in the background); returns the last output. */
    private fun run(frames: Int = 40): GlTextureFrame {
        var out = processor.process(frame())
        repeat(frames) {
            Thread.sleep(15)
            out = processor.process(frame())
        }
        GLES20.glFinish()
        return out
    }

    private fun read(texture: Int): IntArray {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, readFbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0)
        val buf = ByteBuffer.allocateDirect(W * H * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, W, H, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return IntArray(W * H * 4) { buf.get(it).toInt() and 0xFF }
    }

    private fun meanAbsDiff(a: IntArray, b: IntArray): Double {
        var sum = 0L
        for (i in a.indices) if (i % 4 != 3) sum += abs(a[i] - b[i])
        return sum.toDouble() / (a.size / 4 * 3)
    }

    private fun assertChanges(what: String, minDiff: Double = 1.0) {
        val out = run()
        assertWithMessage("$what: processor bypassed").that(out.textureId).isNotEqualTo(inputTex)
        val diff = meanAbsDiff(read(inputTex), read(out.textureId))
        assertWithMessage("$what: mean pixel change $diff").that(diff).isGreaterThan(minDiff)
        assertWithMessage("$what: GPU passes switched off").that(processor.disabledStageNames).isEmpty()
        assertThat(processor.isBypassedByFailure).isFalse()
    }

    /**
     * A fresh processor per scenario: its adaptive quality starts at FULL again (a slow emulator GPU would otherwise
     * step quality down across scenarios and switch smoothing off by design).
     */
    private fun reset() {
        processor.onDetach()
        controls = BeautyControls(TierHint.HIGH)
        controls.state = BeautyState.Off
        processor = BeautyProcessor(controls, InstrumentationRegistry.getInstrumentation().targetContext)
        val max = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, max, 0)
        processor.onAttach(GlProcessingContext(egl.glVersion, max[0]))
    }

    @Test
    fun nothingSelectedBypasses() {
        reset()
        assertThat(run(3).textureId).isEqualTo(inputTex)
    }

    @Test
    fun everyFilterChangesThePicture() {
        for (f in LiveFilter.entries.filter { it != LiveFilter.NONE }) {
            reset()
            controls.effects = EffectsState(filter = f, filterIntensity = 100)
            assertChanges("filter $f")
        }
    }

    @Test
    fun filterSwipePreviewChangesThePicture() {
        reset()
        controls.filterSwipe = FilterSwipe(LiveFilter.MONO, -0.5f)
        assertChanges("swipe")
    }

    @Test
    fun skinSlidersChangeThePictureWithoutAFace() {
        for ((f, v) in listOf(
            BeautyFeature.SMOOTH_SKIN to 35, BeautyFeature.SMOOTH_SKIN to 100, BeautyFeature.SKIN_BRIGHTNESS to 60,
            BeautyFeature.WHITENING to 60, BeautyFeature.SKIN_TONE to 90, BeautyFeature.SHARPEN to 80,
        )) {
            reset()
            controls.state = BeautyState(enabled = true, beauty = mapOf(f to v))
            assertChanges("beauty $f=$v", minDiff = 0.4)
        }
    }

    @Test
    fun defaultBeautyWithAFilterAndGlowStillRenders() {
        reset()
        controls.state = BeautyState()
        controls.effects = EffectsState(filter = LiveFilter.WARM, lens = Lens.SMOOTH_GLOW)
        assertChanges("default beauty + warm + glow")
    }

    private companion object {
        const val W = 360
        const val H = 640
    }
}
