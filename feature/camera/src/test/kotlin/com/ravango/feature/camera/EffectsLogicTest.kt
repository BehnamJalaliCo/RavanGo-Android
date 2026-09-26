package com.ravango.feature.camera

import com.google.common.truth.Truth.assertThat
import com.ravango.core.model.Entitlements
import com.ravango.core.model.ProFeature
import com.ravango.engine.beauty.effects.BackgroundEffect
import com.ravango.engine.beauty.effects.Lens
import com.ravango.engine.beauty.effects.LiveFilter
import com.ravango.feature.camera.ui.CarouselLenses
import com.ravango.feature.camera.ui.FilterSwipeMath
import org.junit.Test

class EffectsLogicTest {

    private val free = Entitlements()
    private val pro = Entitlements(features = setOf(ProFeature.ADVANCED_BEAUTY))

    @Test
    fun `free users get a real base set of lenses, filters and backgrounds`() {
        assertThat(EffectsGating.lensAllowed(Lens.BIG_EYES, free)).isTrue()
        assertThat(EffectsGating.lensAllowed(Lens.CROWN, free)).isFalse()
        assertThat(EffectsGating.lensAllowed(Lens.CROWN, pro)).isTrue()
        assertThat(EffectsGating.filterAllowed(LiveFilter.WARM, free)).isTrue()
        assertThat(EffectsGating.filterAllowed(LiveFilter.CINEMA, free)).isFalse()
        assertThat(EffectsGating.backgroundAllowed(BackgroundEffect.Blur(), free)).isTrue()
        assertThat(EffectsGating.backgroundAllowed(BackgroundEffect.Image, free)).isFalse()
        assertThat(EffectsGating.backgroundAllowed(BackgroundEffect.Image, pro)).isTrue()
    }

    @Test
    fun `swipe list skips locked filters and wraps around`() {
        val list = EffectsGating.swipeable(free)
        assertThat(list.first()).isEqualTo(LiveFilter.NONE)
        assertThat(list.none { it.pro }).isTrue()
        assertThat(EffectsGating.swipeable(pro)).hasSize(LiveFilter.entries.size)
        assertThat(EffectsGating.neighbor(list, list.last(), 1)).isEqualTo(LiveFilter.NONE)
        assertThat(EffectsGating.neighbor(list, LiveFilter.NONE, -1)).isEqualTo(list.last())
        // A filter that is no longer allowed behaves like Original.
        assertThat(EffectsGating.neighbor(list, LiveFilter.CINEMA, 1)).isEqualTo(list[1])
    }

    @Test
    fun `swiping left brings the next filter in LTR and the previous one in RTL`() {
        val list = EffectsGating.swipeable(free)
        assertThat(FilterSwipeMath.target(list, LiveFilter.NONE, -0.2f, rtl = false)).isEqualTo(list[1])
        assertThat(FilterSwipeMath.target(list, LiveFilter.NONE, 0.2f, rtl = false)).isEqualTo(list.last())
        assertThat(FilterSwipeMath.target(list, LiveFilter.NONE, 0.2f, rtl = true)).isEqualTo(list[1])
        assertThat(FilterSwipeMath.target(list, LiveFilter.WARM, 0f, rtl = false)).isEqualTo(LiveFilter.WARM)
    }

    @Test
    fun `a swipe commits past a third of the width or on a fling`() {
        assertThat(FilterSwipeMath.shouldCommit(-0.4f, 0f)).isTrue()
        assertThat(FilterSwipeMath.shouldCommit(-0.1f, 0f)).isFalse()
        assertThat(FilterSwipeMath.shouldCommit(-0.1f, -2f)).isTrue()
        // A fling against the drag direction cancels.
        assertThat(FilterSwipeMath.shouldCommit(-0.1f, 2f)).isFalse()
    }

    @Test
    fun `carousel starts with no lens and lists every lens once`() {
        assertThat(CarouselLenses.first()).isNull()
        assertThat(CarouselLenses.drop(1)).containsExactlyElementsIn(Lens.entries).inOrder()
    }
}
