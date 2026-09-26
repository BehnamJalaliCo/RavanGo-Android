package com.ravango.feature.beauty

import com.google.common.truth.Truth.assertThat
import com.ravango.core.model.BeautyFeature
import com.ravango.core.model.BeautyState
import com.ravango.core.model.MakeupFeature
import com.ravango.core.model.MakeupLayer
import com.ravango.core.model.ProFeature
import org.junit.Test
import java.util.Locale

class BeautyCatalogTest {

    @Test
    fun gatingMapsFeaturesToEntitlements() {
        assertThat(BeautyCatalog.requiredPro(BeautyItem.Beauty(BeautyFeature.SMOOTH_SKIN))).isNull()
        assertThat(BeautyCatalog.requiredPro(BeautyItem.Beauty(BeautyFeature.EYE_SIZE))).isEqualTo(ProFeature.FACE_RESHAPE)
        assertThat(BeautyCatalog.requiredPro(BeautyItem.Beauty(BeautyFeature.TEETH_WHITENING))).isEqualTo(ProFeature.ADVANCED_BEAUTY)
        assertThat(BeautyCatalog.requiredPro(BeautyItem.Makeup(MakeupFeature.BLUSH))).isEqualTo(ProFeature.MAKEUP)
    }

    @Test
    fun missingEntitlementForPreset() {
        val glam = BeautyState(
            beauty = mapOf(BeautyFeature.SMOOTH_SKIN to 50, BeautyFeature.FACE_SLIM to 20),
            makeup = mapOf(MakeupFeature.LIPSTICK to MakeupLayer(40, 0xFFB0204E)),
        )
        assertThat(BeautyCatalog.missingEntitlement(glam) { false }).isEqualTo(ProFeature.FACE_RESHAPE)
        assertThat(BeautyCatalog.missingEntitlement(glam) { it == ProFeature.FACE_RESHAPE }).isEqualTo(ProFeature.MAKEUP)
        assertThat(BeautyCatalog.missingEntitlement(glam) { true }).isNull()
        assertThat(BeautyCatalog.missingEntitlement(BeautyState()) { false }).isNull()
    }

    @Test
    fun lerpInterpolatesValuesAndColors() {
        val from = BeautyState(beauty = mapOf(BeautyFeature.SMOOTH_SKIN to 0, BeautyFeature.SKIN_TONE to 50))
        val to = BeautyState(
            beauty = mapOf(BeautyFeature.SMOOTH_SKIN to 100, BeautyFeature.SKIN_TONE to 70),
            makeup = mapOf(MakeupFeature.BLUSH to MakeupLayer(60, 0xFFF48FB1)),
        )
        val mid = BeautyCatalog.lerp(from, to, 0.5f)
        assertThat(mid.intensity(BeautyFeature.SMOOTH_SKIN)).isEqualTo(50)
        assertThat(mid.intensity(BeautyFeature.SKIN_TONE)).isEqualTo(60)
        assertThat(mid.layer(MakeupFeature.BLUSH).intensity).isEqualTo(30)
        // Fading in a layer keeps its target colour (no colour sweep from the default).
        assertThat(mid.layer(MakeupFeature.BLUSH).color).isEqualTo(0xFFF48FB1)
        val end = BeautyCatalog.lerp(from, to, 1f)
        assertThat(BeautyCatalog.activeCount(end)).isEqualTo(BeautyCatalog.activeCount(to))
    }

    @Test
    fun withColorEnablesAnInactiveLayer() {
        val s = BeautyCatalog.withColor(BeautyState(), MakeupFeature.LIPSTICK, 0xFF8E2433)
        assertThat(s.layer(MakeupFeature.LIPSTICK)).isEqualTo(MakeupLayer(50, 0xFF8E2433))
    }

    @Test
    fun valueLabels() {
        assertThat(valueLabel(35, bipolar = false, locale = Locale.US)).isEqualTo("35")
        assertThat(valueLabel(62, bipolar = true, locale = Locale.US)).isEqualTo("+12")
        assertThat(valueLabel(42, bipolar = true, locale = Locale.US)).isEqualTo("−8")
        assertThat(valueLabel(50, bipolar = true, locale = Locale.US)).isEqualTo("0")
        assertThat(valueLabel(35, bipolar = false, locale = Locale.forLanguageTag("fa"))).isEqualTo("۳۵")
    }
}
