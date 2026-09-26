package com.ravango.engine.camera.capability

import com.google.common.truth.Truth.assertThat
import com.ravango.core.model.LensFacing
import org.junit.Test

class LensClassifierTest {

    @Test
    fun `logical camera exposes ultra wide via zoom ratio and tele via physical focal length`() {
        val lenses = LensClassifier.build(
            listOf(
                CameraOptics("0", LensFacing.BACK, 24f, true, listOf(24f, 13f, 70f), 0.6f..10f, zoomRatioApi = true),
                CameraOptics("1", LensFacing.FRONT, 26f, false, emptyList(), 1f..4f, zoomRatioApi = true),
            ),
        )
        val back = lenses.getValue(LensFacing.BACK)
        assertThat(back.map { it.kind }).containsExactly(LensKind.ULTRA_WIDE, LensKind.WIDE, LensKind.TELE).inOrder()
        assertThat(back.map { it.label }).containsExactly("0.6×", "1×", "2.9×").inOrder()
        assertThat(back.all { it.cameraId == "0" }).isTrue()
        assertThat(back.first().zoomRatio).isEqualTo(0.6f)
        assertThat(lenses.getValue(LensFacing.FRONT).map { it.label }).containsExactly("1×")
    }

    @Test
    fun `separate camera ids become lens chips and duplicates are skipped`() {
        val lenses = LensClassifier.build(
            listOf(
                CameraOptics("0", LensFacing.BACK, 26f, false, emptyList(), 1f..8f, zoomRatioApi = false),
                CameraOptics("1", LensFacing.FRONT, 24f, false, emptyList(), 1f..4f, zoomRatioApi = false),
                CameraOptics("2", LensFacing.BACK, 13f, false, emptyList(), 1f..4f, zoomRatioApi = false),
                CameraOptics("3", LensFacing.BACK, 26.5f, false, emptyList(), 1f..4f, zoomRatioApi = false),
                CameraOptics("4", LensFacing.BACK, 52f, false, emptyList(), 1f..4f, zoomRatioApi = false),
            ),
        )
        val back = lenses.getValue(LensFacing.BACK)
        assertThat(back.map { it.cameraId }).containsExactly("2", "0", "4").inOrder()
        assertThat(back.map { it.label }).containsExactly("0.5×", "1×", "2×").inOrder()
        assertThat(back.all { it.zoomRatio == 1f }).isTrue()
    }

    @Test
    fun `equivalent focal length uses the sensor diagonal`() {
        // A 36x24mm sensor is full frame: equivalent == real focal length.
        assertThat(LensClassifier.equivalentFocal(50f, 36f, 24f)!!).isWithin(0.1f).of(50f)
        assertThat(LensClassifier.equivalentFocal(4f, 0f, 0f)).isNull()
    }
}
