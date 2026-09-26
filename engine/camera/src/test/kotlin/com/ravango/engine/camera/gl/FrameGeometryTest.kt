package com.ravango.engine.camera.gl

import com.google.common.truth.Truth.assertThat
import com.ravango.core.model.LensFacing
import org.junit.Test

class FrameGeometryTest {

    private fun Affine.map(x: Float, y: Float) = mapX(x, y) to mapY(x, y)

    private fun assertPoint(actual: Pair<Float, Float>, x: Float, y: Float) {
        assertThat(actual.first).isWithin(1e-4f).of(x)
        assertThat(actual.second).isWithin(1e-4f).of(y)
    }

    @Test
    fun `rotation follows the camera jpeg-orientation formula`() {
        assertThat(FrameGeometry.rotationCw(90, 0, LensFacing.BACK)).isEqualTo(90)
        assertThat(FrameGeometry.rotationCw(90, 90, LensFacing.BACK)).isEqualTo(180)
        assertThat(FrameGeometry.rotationCw(90, 270, LensFacing.BACK)).isEqualTo(0)
        assertThat(FrameGeometry.rotationCw(270, 0, LensFacing.FRONT)).isEqualTo(270)
        assertThat(FrameGeometry.rotationCw(270, 90, LensFacing.FRONT)).isEqualTo(180)
        assertThat(FrameGeometry.rotationCw(270, 88, LensFacing.FRONT)).isEqualTo(180)
    }

    @Test
    fun `snap rounds to quarter turns`() {
        assertThat(FrameGeometry.snap(80)).isEqualTo(90)
        assertThat(FrameGeometry.snap(350)).isEqualTo(0)
        assertThat(FrameGeometry.snap(-10)).isEqualTo(0)
        assertThat(FrameGeometry.snap(225)).isEqualTo(270)
    }

    @Test
    fun `inverse rotation maps corners of the rotated image back to the source`() {
        // Rotating CW by 90: the source bottom-left corner becomes the top-left corner.
        assertPoint(Affine.inverseRotation(90).map(0f, 0f), 0f, 1f)
        assertPoint(Affine.inverseRotation(90).map(1f, 0f), 0f, 0f)
        assertPoint(Affine.inverseRotation(180).map(0f, 0f), 1f, 1f)
        assertPoint(Affine.inverseRotation(270).map(0f, 0f), 1f, 0f)
        assertPoint(Affine.inverseRotation(0).map(0.3f, 0.7f), 0.3f, 0.7f)
    }

    @Test
    fun `affine composition applies left then right`() {
        val t = Affine(a = 2f, d = 2f) then Affine(tx = 1f, ty = -1f)
        assertPoint(t.map(1f, 1f), 3f, 1f)
        val gl = FloatArray(16)
        t.toGlMatrix(gl)
        assertThat(gl[0]).isEqualTo(2f)
        assertThat(gl[12]).isEqualTo(1f)
        assertThat(gl[13]).isEqualTo(-1f)
        assertThat(gl[15]).isEqualTo(1f)
    }

    @Test
    fun `center crop keeps the middle`() {
        val crop = FrameGeometry.centerCrop(16f / 9f, 9f / 16f)
        assertThat(crop.scaleY).isEqualTo(1f)
        assertThat(crop.scaleX).isWithin(1e-4f).of((9f / 16f) / (16f / 9f))
        assertThat(crop.offsetX + crop.scaleX / 2f).isWithin(1e-4f).of(0.5f)
        val tall = FrameGeometry.centerCrop(9f / 16f, 1f)
        assertThat(tall.scaleX).isEqualTo(1f)
        assertThat(tall.scaleY).isWithin(1e-4f).of(9f / 16f)
    }

    @Test
    fun `identity mapping when nothing is rotated cropped or mirrored`() {
        val t = FrameGeometry.outputToBufferGl(0, CropRect.FULL, mirror = false)
        assertPoint(t.map(0.2f, 0.9f), 0.2f, 0.9f)
    }

    @Test
    fun `mirroring flips horizontally in GL space`() {
        val t = FrameGeometry.outputToBufferGl(0, CropRect.FULL, mirror = true)
        assertPoint(t.map(0.2f, 0.9f), 0.8f, 0.9f)
    }

    @Test
    fun `gl mapping for a portrait back camera`() {
        // Output GL bottom-left (image bottom-left) comes from the buffer's bottom-right when rotating by 90° CW.
        val t = FrameGeometry.outputToBufferGl(90, CropRect.FULL, mirror = false)
        // image (0,1) -> buffer image (1,1) -> GL (1,0)
        assertPoint(t.map(0f, 0f), 1f, 0f)
        // GL top-left = image (0,0) -> buffer image (0,1) -> GL (0,0)
        assertPoint(t.map(0f, 1f), 0f, 0f)
    }

    @Test
    fun `tap point maps through preview rotation, crop and rotation to the buffer`() {
        val crop = CropRect.FULL
        val (bx, by) = FrameGeometry.displayPointToBuffer(0.25f, 0.1f, previewRotationCw = 0, rotationCw = 90, crop = crop, mirror = false)
        assertThat(bx).isWithin(1e-4f).of(0.1f)
        assertThat(by).isWithin(1e-4f).of(0.75f)
        // Mirrored front camera: left on screen is right in the (unmirrored) upright image.
        val (mx, my) = FrameGeometry.displayPointToBuffer(0.25f, 0.5f, previewRotationCw = 0, rotationCw = 0, crop = crop, mirror = true)
        assertThat(mx).isWithin(1e-4f).of(0.75f)
        assertThat(my).isWithin(1e-4f).of(0.5f)
    }

    @Test
    fun `preview rotation undoes device rotation`() {
        assertThat(FrameGeometry.previewRotationCw(0)).isEqualTo(0)
        assertThat(FrameGeometry.previewRotationCw(90)).isEqualTo(270)
        assertThat(FrameGeometry.previewRotationCw(270)).isEqualTo(90)
        assertThat(FrameGeometry.displayedSize(1920, 1080, 270)).isEqualTo(1080 to 1920)
    }

    @Test
    fun `fit letterboxes content`() {
        val fit = FrameGeometry.fit(1080f, 1920f, 1080f, 2400f)
        assertThat(fit.width).isEqualTo(1080f)
        assertThat(fit.height).isEqualTo(1920f)
        assertThat(fit.top).isEqualTo(240f)
        val pillar = FrameGeometry.fit(1f, 1f, 200f, 100f)
        assertThat(pillar.left).isEqualTo(50f)
        assertThat(pillar.width).isEqualTo(100f)
    }
}
