package com.ravango.feature.beauty.looks

import com.google.common.truth.Truth.assertThat
import com.ravango.core.model.BeautyFeature
import com.ravango.core.model.BeautyState
import com.ravango.core.model.MakeupFeature
import com.ravango.core.model.MakeupLayer
import com.ravango.engine.beauty.EyeColorSetting
import com.ravango.engine.beauty.effects.LiveFilter
import com.ravango.engine.beauty.makeup.MakeupStyle
import org.junit.Test

class LookResolverTest {

    private val base = BeautyState(
        beauty = mapOf(BeautyFeature.SMOOTH_SKIN to 20, BeautyFeature.SHARPEN to 30, BeautyFeature.JAW to 50),
        makeup = mapOf(MakeupFeature.BLUSH to MakeupLayer(70, 0xFFD81B60)),
    )
    private val recipe = LookRecipe(
        beauty = mapOf(BeautyFeature.SMOOTH_SKIN to 60, BeautyFeature.JAW to 40),
        makeup = mapOf(MakeupFeature.LIPSTICK to MakeupLayer(80, 0xFFC4122F), MakeupFeature.EYELINER to MakeupLayer(90, 0xFF000000)),
        style = LookStyle(wing = 1f, lashVolume = 0.8f, shimmer = 0.6f, lipFinish = -0.5f, skinFinish = 0.8f, freckles = 0.4f),
        eyeColor = 0xFF8FB7D9,
        eyeIntensity = 40,
        filterId = "warm",
        filterIntensity = 30,
    )

    @Test
    fun fullIntensityIsTheLook() {
        val r = LookResolver.resolve(recipe, 100, base)
        assertThat(r.state.enabled).isTrue()
        assertThat(r.state.intensity(BeautyFeature.SMOOTH_SKIN)).isEqualTo(60)
        assertThat(r.state.intensity(BeautyFeature.JAW)).isEqualTo(40)
        // Values the look does not set keep the user's own.
        assertThat(r.state.intensity(BeautyFeature.SHARPEN)).isEqualTo(30)
        assertThat(r.state.layer(MakeupFeature.LIPSTICK)).isEqualTo(MakeupLayer(80, 0xFFC4122F))
        // Layers the look does not use are off, keeping the user's colour.
        assertThat(r.state.layer(MakeupFeature.BLUSH)).isEqualTo(MakeupLayer(0, 0xFFD81B60))
        assertThat(r.style).isEqualTo(recipe.style.toEngine(1f))
        assertThat(r.eyeColor).isEqualTo(EyeColorSetting(40, 0xFF8FB7D9))
        assertThat(r.filter).isEqualTo(LiveFilter.WARM)
        assertThat(r.filterIntensity).isEqualTo(30)
    }

    @Test
    fun intensityScalesEverythingFromTheBase() {
        val half = LookResolver.resolve(recipe, 50, base)
        assertThat(half.state.intensity(BeautyFeature.SMOOTH_SKIN)).isEqualTo(40) // 20 → 60
        assertThat(half.state.intensity(BeautyFeature.JAW)).isEqualTo(45) // bipolar 50 → 40
        assertThat(half.state.layer(MakeupFeature.LIPSTICK).intensity).isEqualTo(40)
        assertThat(half.eyeColor.intensity).isEqualTo(20)
        assertThat(half.filterIntensity).isEqualTo(15)
        // Amounts scale, shapes do not.
        assertThat(half.style.shimmer).isWithin(1e-6f).of(0.3f)
        assertThat(half.style.freckles).isWithin(1e-6f).of(0.2f)
        assertThat(half.style.skinFinish).isWithin(1e-6f).of(0.4f)
        assertThat(half.style.wing).isEqualTo(1f)
        assertThat(half.style.lashVolume).isEqualTo(0.8f)
        assertThat(half.style.lipFinish).isEqualTo(-0.5f)

        val zero = LookResolver.resolve(recipe, 0, base)
        assertThat(zero.state.intensity(BeautyFeature.SMOOTH_SKIN)).isEqualTo(20)
        assertThat(zero.state.intensity(BeautyFeature.JAW)).isEqualTo(50)
        assertThat(MakeupFeature.entries.all { zero.state.layer(it).intensity == 0 }).isTrue()
        assertThat(zero.eyeColor.active).isFalse()

        // Out-of-range intensities are clamped.
        assertThat(LookResolver.resolve(recipe, 250, base)).isEqualTo(LookResolver.resolve(recipe, 100, base))
    }

    @Test
    fun lookWithoutLensesTurnsIrisColourOff() {
        val r = LookResolver.resolve(recipe.copy(eyeColor = null), 100, base, EyeColorSetting(60, 0xFF6E9B6A))
        assertThat(r.eyeColor.intensity).isEqualTo(0)
        assertThat(r.eyeColor.color).isEqualTo(0xFF6E9B6A)
        assertThat(r.filter).isEqualTo(LiveFilter.WARM)
        assertThat(LookResolver.resolve(recipe.copy(filterId = null), 100, base).filter).isNull()
    }

    @Test
    fun captureRoundTripsTheCurrentSettings() {
        val r = LookResolver.resolve(recipe, 100, base)
        val captured = LookResolver.capture(r.state, r.style, r.eyeColor, r.filter, r.filterIntensity)
        val again = LookResolver.resolve(captured, 100, BeautyState())
        assertThat(BeautyFeature.entries.map { again.state.intensity(it) }).isEqualTo(BeautyFeature.entries.map { r.state.intensity(it) })
        assertThat(MakeupFeature.entries.map { again.state.layer(it).intensity }).isEqualTo(MakeupFeature.entries.map { r.state.layer(it).intensity })
        assertThat(again.style).isEqualTo(r.style)
        assertThat(again.eyeColor).isEqualTo(r.eyeColor)
        assertThat(again.filter).isEqualTo(LiveFilter.WARM)
        assertThat(LookStyle.from(MakeupStyle.Default).toEngine()).isEqualTo(MakeupStyle.Default)
    }
}
