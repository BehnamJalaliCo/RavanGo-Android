package com.ravango.engine.beauty

import com.google.common.truth.Truth.assertThat
import com.ravango.core.model.BeautyFeature

import com.ravango.core.model.BeautyState
import com.ravango.engine.beauty.effects.BackgroundEffect
import com.ravango.engine.beauty.effects.EffectsDefaults
import com.ravango.engine.beauty.effects.EffectsState
import com.ravango.engine.beauty.effects.FilterSwipe
import com.ravango.engine.beauty.effects.Lens
import com.ravango.engine.beauty.effects.LiveFilter
import com.ravango.engine.beauty.quality.QualityProfile
import com.ravango.engine.beauty.tracking.DelegateDecision
import org.junit.Test

/**
 * Proves every Camera Studio control reaches a shader uniform or a pass gate (the GPU side is covered by the
 * instrumented `ShaderProgramsTest`).
 */
class BeautyMappingTest {

    private val full = QualityProfile.of(BeautyQuality.FULL)
    private val off = BeautyState(enabled = true, beauty = emptyMap())

    private fun with(f: BeautyFeature, v: Int) = off.copy(beauty = mapOf(f to v))

    @Test
    fun `every beauty slider changes at least one uniform when a face is tracked`() {
        for (f in BeautyFeature.entries) {
            val neutral = if (f.bipolar) 50 else 0
            val base = BeautyMapping.uniforms(with(f, neutral), EyeColorSetting(), beautyOn = true, beautyFace = true, profile = full)
            for (v in listOf(0, 25, 75, 100).filter { it != neutral }) {
                val u = BeautyMapping.uniforms(with(f, v), EyeColorSetting(), beautyOn = true, beautyFace = true, profile = full)
                val reshape = f in RESHAPE
                // Reshape features are a mesh warp (ReshapeParams), not composite uniforms.
                if (!reshape) assertThat(u).isNotEqualTo(base)
            }
        }
    }

    @Test
    fun `reshape sliders reach the warp parameters`() {
        for (f in RESHAPE) {
            val p = com.ravango.engine.beauty.mesh.ReshapeParams()
            p.set(with(f, 90))
            assertThat(p.isNeutral).isFalse()
        }
    }

    @Test
    fun `the default soft skin is clearly visible`() {
        val u = BeautyMapping.uniforms(BeautyState(), EyeColorSetting(), beautyOn = true, beautyFace = false, profile = full)
        // Default Soft Skin (35): more than half-strength blend and less than half of the fine detail kept.
        assertThat(u.smoothK).isGreaterThan(0.5f)
        assertThat(u.detailKeep).isLessThan(0.45f)
        assertThat(u.brightK).isGreaterThan(0.2f)
        assertThat(u.any).isTrue()
    }

    @Test
    fun `curve is monotonic and keeps the ends`() {
        assertThat(BeautyMapping.curve(0)).isEqualTo(0f)
        assertThat(BeautyMapping.curve(100)).isEqualTo(1f)
        var last = -1f
        for (v in 0..100) {
            val c = BeautyMapping.curve(v)
            assertThat(c).isAtLeast(last)
            last = c
        }
        assertThat(BeautyMapping.detailKeep(1f)).isGreaterThan(0.1f) // never fully plastic
    }

    @Test
    fun `face features need a tracked face, skin features do not`() {
        val state = off.copy(beauty = mapOf(BeautyFeature.SMOOTH_SKIN to 60, BeautyFeature.TEETH_WHITENING to 60, BeautyFeature.DARK_CIRCLES to 60))
        val noFace = BeautyMapping.uniforms(state, EyeColorSetting(), beautyOn = true, beautyFace = false, profile = full)
        assertThat(noFace.smoothK).isGreaterThan(0f)
        assertThat(noFace.teethK).isEqualTo(0f)
        assertThat(noFace.darkK).isEqualTo(0f)
        val face = BeautyMapping.uniforms(state, EyeColorSetting(), beautyOn = true, beautyFace = true, profile = full)
        assertThat(face.teethK).isGreaterThan(0f)
        assertThat(face.darkK).isGreaterThan(0f)
    }

    @Test
    fun `eye colour reaches the composite only with a face`() {
        val eye = EyeColorSetting(intensity = 70)
        assertThat(BeautyMapping.uniforms(off, eye, beautyOn = true, beautyFace = true, profile = full).eyeColorK).isWithin(1e-6f).of(0.7f)
        assertThat(BeautyMapping.uniforms(off, eye, beautyOn = true, beautyFace = false, profile = full).eyeColorK).isEqualTo(0f)
    }

    @Test
    fun `quality levels only drop what they document`() {
        val state = off.copy(beauty = mapOf(BeautyFeature.SMOOTH_SKIN to 60, BeautyFeature.SKIN_RETOUCH to 60, BeautyFeature.SKIN_BRIGHTNESS to 60))
        val light = BeautyMapping.uniforms(state, EyeColorSetting(), true, false, QualityProfile.of(BeautyQuality.LIGHT))
        assertThat(light.smoothK).isGreaterThan(0f)
        assertThat(light.retouchK).isEqualTo(0f)
        val minimal = BeautyMapping.uniforms(state, EyeColorSetting(), true, false, QualityProfile.of(BeautyQuality.MINIMAL))
        assertThat(minimal.smoothK).isEqualTo(0f)
        // Brightness still applies at MINIMAL: the panel never looks completely dead.
        assertThat(minimal.brightK).isGreaterThan(0f)
    }

    @Test
    fun `beauty off means no uniform at all`() {
        assertThat(BeautyMapping.uniforms(BeautyState(), EyeColorSetting(80), beautyOn = false, beautyFace = true, profile = full).any).isFalse()
    }

    @Test
    fun `every filter, lens and background keeps the processor from bypassing`() {
        for (f in LiveFilter.entries.filter { it != LiveFilter.NONE }) {
            val fx = EffectsState(filter = f)
            assertThat(BeautyMapping.effectsActive(fx, null)).isTrue()
            assertThat(BeautyMapping.filterActive(fx, null)).isTrue()
        }
        for (l in Lens.entries) assertThat(BeautyMapping.effectsActive(EffectsState(lens = l), null)).isTrue()
        for (bg in listOf(BackgroundEffect.Blur(), BackgroundEffect.Color(0xFF000000), BackgroundEffect.Gradient(0xFF000000, 0xFFFFFFFF), BackgroundEffect.Image)) {
            assertThat(BeautyMapping.effectsActive(EffectsState(background = bg), null)).isTrue()
        }
        assertThat(BeautyMapping.effectsActive(EffectsState(), null)).isFalse()
    }

    @Test
    fun `a swipe preview is active even from Original`() {
        val swipe = FilterSwipe(LiveFilter.WARM, 0.3f)
        assertThat(BeautyMapping.effectsActive(EffectsState(), swipe)).isTrue()
        assertThat(BeautyMapping.filterActive(EffectsState(), swipe)).isTrue()
        assertThat(BeautyMapping.swipeIntensity(EffectsState(filterIntensity = 0))).isEqualTo(1f)
    }

    @Test
    fun `a photo background waits for its photo`() {
        assertThat(BeautyMapping.wantsBackground(EffectsState(background = BackgroundEffect.Image), hasPhoto = false)).isFalse()
        assertThat(BeautyMapping.wantsBackground(EffectsState(background = BackgroundEffect.Image), hasPhoto = true)).isTrue()
        assertThat(BeautyMapping.wantsBackground(EffectsState(background = BackgroundEffect.Blur()), hasPhoto = false)).isTrue()
    }

    @Test
    fun `face lenses are exactly the ones that need tracking`() {
        for (l in Lens.entries) assertThat(BeautyMapping.lensNeedsFace(l)).isEqualTo(l.kind != Lens.Kind.GRADE)
        assertThat(BeautyMapping.lensNeedsFace(null)).isFalse()
    }

    @Test
    fun `picking a filter at zero strength restores full strength`() {
        val zero = EffectsState(filterIntensity = 0)
        assertThat(EffectsDefaults.withFilter(zero, LiveFilter.VIVID).filterIntensity).isEqualTo(100)
        assertThat(EffectsDefaults.withFilter(zero, LiveFilter.NONE).filterIntensity).isEqualTo(0)
        assertThat(EffectsDefaults.withFilter(EffectsState(filterIntensity = 40), LiveFilter.VIVID).filterIntensity).isEqualTo(40)
    }

    @Test
    fun `gpu delegate is disabled after a crash during init or a native crash while running`() {
        assertThat(DelegateDecision.decide(false, false, false) { true }.block).isFalse()
        assertThat(DelegateDecision.decide(false, true, false) { false }.block).isTrue()
        assertThat(DelegateDecision.decide(false, false, true) { true }.block).isTrue()
        // Killed normally while running (e.g. low memory in the background): keep the GPU.
        assertThat(DelegateDecision.decide(false, false, true) { false }.block).isFalse()
        assertThat(DelegateDecision.decide(true, false, false) { false }.block).isTrue()
    }

    private companion object {
        val RESHAPE = setOf(
            BeautyFeature.FACE_SLIM, BeautyFeature.JAW, BeautyFeature.CHIN, BeautyFeature.CHEEKBONE,
            BeautyFeature.FOREHEAD, BeautyFeature.NOSE, BeautyFeature.EYE_SIZE, BeautyFeature.EYE_SHAPE,
        )
    }
}
