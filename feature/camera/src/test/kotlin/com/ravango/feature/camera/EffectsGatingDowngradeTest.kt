package com.ravango.feature.camera

import com.google.common.truth.Truth.assertThat
import com.ravango.core.model.Entitlements
import com.ravango.core.model.ProFeature
import com.ravango.engine.beauty.effects.BackgroundEffect
import com.ravango.engine.beauty.effects.EffectsState
import com.ravango.engine.beauty.effects.Lens
import com.ravango.engine.beauty.effects.LiveFilter
import org.junit.Test

class EffectsGatingDowngradeTest {

    private val free = Entitlements()
    private val pro = Entitlements(features = setOf(ProFeature.ADVANCED_BEAUTY))

    @Test
    fun `nothing changes for an entitled user`() {
        val fx = EffectsState(lens = Lens.CROWN, filter = LiveFilter.CINEMA, background = BackgroundEffect.Image)
        assertThat(EffectsGating.downgrade(fx, pro)).isNull()
    }

    @Test
    fun `a downgrade switches off only the Pro selections`() {
        val fx = EffectsState(lens = Lens.CROWN, filter = LiveFilter.CINEMA, filterIntensity = 70, background = BackgroundEffect.Image)
        val next = EffectsGating.downgrade(fx, free)!!
        assertThat(next.lens).isNull()
        assertThat(next.filter).isEqualTo(LiveFilter.NONE)
        assertThat(next.background).isEqualTo(BackgroundEffect.None)
        assertThat(next.filterIntensity).isEqualTo(70)
    }

    @Test
    fun `free selections survive a downgrade`() {
        val fx = EffectsState(lens = Lens.BIG_EYES, filter = LiveFilter.WARM, background = BackgroundEffect.Blur(40))
        assertThat(EffectsGating.downgrade(fx, free)).isNull()
    }

    @Test
    fun `entitlements settle before anything is switched off`() {
        // The provider's initial (free) value is replaced within milliseconds; the debounce must outlast that.
        assertThat(EffectsGating.SETTLE_MS).isAtLeast(1_000L)
    }
}
