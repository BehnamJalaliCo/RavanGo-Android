package com.ravango.engine.camera.capability

import com.google.common.truth.Truth.assertThat
import com.ravango.core.common.device.DeviceTier
import com.ravango.core.model.AspectRatioSpec
import com.ravango.core.model.CameraSettings
import com.ravango.core.model.FlashMode
import com.ravango.core.model.LensFacing
import com.ravango.core.model.StabilizationMode
import com.ravango.core.model.VideoCodec
import com.ravango.core.model.VideoSize
import com.ravango.core.model.WhiteBalanceMode
import org.junit.Test

class VideoModeSelectorTest {

    private val ranges = listOf(15 to 30, 30 to 30, 24 to 24, 60 to 60, 120 to 120, 30 to 120)

    @Test
    fun `frame rates need an AE range ending at the rate and a short enough frame duration`() {
        assertThat(VideoModeSelector.frameRatesFor(ranges, 16_666_666)).containsExactly(24, 30, 60).inOrder()
        assertThat(VideoModeSelector.frameRatesFor(ranges, 33_366_666)).containsExactly(24, 30).inOrder()
        assertThat(VideoModeSelector.frameRatesFor(ranges, 8_333_333)).containsExactly(24, 30, 60, 120).inOrder()
    }

    @Test
    fun `rates above 60 require a fixed range`() {
        val variableOnly = listOf(30 to 30, 30 to 120)
        assertThat(VideoModeSelector.frameRatesFor(variableOnly, 8_000_000)).containsExactly(30)
    }

    @Test
    fun `ae range prefers fixed range`() {
        assertThat(VideoModeSelector.aeRangeFor(30, ranges)).isEqualTo(30 to 30)
        assertThat(VideoModeSelector.aeRangeFor(25, listOf(15 to 25, 20 to 25))).isEqualTo(20 to 25)
        assertThat(VideoModeSelector.aeRangeFor(48, listOf(15 to 60))).isEqualTo(15 to 60)
    }

    @Test
    fun `build modes keeps standard 16x9 sizes the encoder supports`() {
        val sizes = listOf(VideoSize(3840, 2160), VideoSize(1920, 1080), VideoSize(1440, 1080), VideoSize(1280, 720), VideoSize(640, 480))
        val modes = VideoModeSelector.buildModes(
            streamSizes = sizes,
            fpsRanges = listOf(30 to 30, 60 to 60),
            minFrameDurationNs = { if (it.width >= 3840) 33_333_333 else 16_666_666 },
            codecs = setOf(VideoCodec.H264, VideoCodec.HEVC),
            encoderSupports = { codec, w, h, fps -> !(codec == VideoCodec.H264 && maxOf(w, h) >= 3840 && fps > 30) },
        )
        assertThat(modes.map { "${it.size.label}@${it.fps}" }).containsExactly("4K@30", "1080p@30", "1080p@60", "720p@30", "720p@60").inOrder()
        assertThat(modes.first().codecs).containsExactly(VideoCodec.H264, VideoCodec.HEVC)
    }

    @Test
    fun `encoder must support portrait output too`() {
        val modes = VideoModeSelector.buildModes(
            streamSizes = listOf(VideoSize(1920, 1080)),
            fpsRanges = listOf(30 to 30),
            minFrameDurationNs = { 0 },
            codecs = setOf(VideoCodec.H264),
            encoderSupports = { _, w, h, _ -> w >= h },
        )
        assertThat(modes).isEmpty()
    }

    @Test
    fun `output size crops the resolution box to the aspect ratio`() {
        val hd = VideoSize(1920, 1080)
        assertThat(VideoModeSelector.outputSize(hd, AspectRatioSpec.Portrait9x16)).isEqualTo(OutputSize(1080, 1920))
        assertThat(VideoModeSelector.outputSize(hd, AspectRatioSpec.Landscape16x9)).isEqualTo(OutputSize(1920, 1080))
        assertThat(VideoModeSelector.outputSize(hd, AspectRatioSpec.Square1x1)).isEqualTo(OutputSize(1080, 1080))
        assertThat(VideoModeSelector.outputSize(hd, AspectRatioSpec.Portrait4x5)).isEqualTo(OutputSize(1080, 1344))
        assertThat(VideoModeSelector.outputSize(hd, AspectRatioSpec.Portrait3x4)).isEqualTo(OutputSize(1080, 1440))
        assertThat(VideoModeSelector.outputSize(hd, AspectRatioSpec.Landscape4x3)).isEqualTo(OutputSize(1440, 1080))
        assertThat(VideoModeSelector.outputSize(hd, AspectRatioSpec.Cinema21x9)).isEqualTo(OutputSize(1920, 816))
        assertThat(VideoModeSelector.outputSize(VideoSize(3840, 2160), AspectRatioSpec.Portrait4x5)).isEqualTo(OutputSize(2160, 2688))
        assertThat(VideoModeSelector.outputSize(VideoSize(1280, 720), AspectRatioSpec.Portrait9x16)).isEqualTo(OutputSize(720, 1280))
    }

    @Test
    fun `nearest mode prefers the same resolution at another frame rate`() {
        val modes = listOf(mode(3840, 2160, 30), mode(1920, 1080, 60), mode(1920, 1080, 30))
        assertThat(VideoModeSelector.nearestMode(modes, VideoSize(3840, 2160), 60)).isEqualTo(modes[0])
        assertThat(VideoModeSelector.nearestMode(modes, VideoSize(1920, 1080), 50)).isEqualTo(modes[1])
        assertThat(VideoModeSelector.nearestMode(modes, VideoSize(2560, 1440), 30)).isIn(listOf(modes[0], modes[2]))
        assertThat(VideoModeSelector.nearestMode(emptyList(), VideoSize(1920, 1080), 30)).isNull()
    }

    @Test
    fun `low tier devices never get 4K`() {
        val caps = caps(listOf(mode(3840, 2160, 30), mode(1920, 1080, 60), mode(1920, 1080, 30)))
        val requested = CameraSettings(resolution = VideoSize(3840, 2160), frameRate = 30)
        val low = VideoModeSelector.validate(requested, caps, DeviceTier.LOW)
        assertThat(low.resolution).isEqualTo(VideoSize(1920, 1080))
        val high = VideoModeSelector.validate(requested, caps, DeviceTier.HIGH)
        assertThat(high.resolution).isEqualTo(VideoSize(3840, 2160))
    }

    @Test
    fun `validate falls back for unsupported codec, stabilization, hdr and flash`() {
        val caps = caps(listOf(VideoMode(VideoSize(1920, 1080), 30, setOf(VideoCodec.H264))))
        val requested = CameraSettings(
            codec = VideoCodec.HEVC,
            stabilization = StabilizationMode.PREVIEW_OPTIMIZED,
            hdr = true,
            flash = FlashMode.TORCH,
            timerSeconds = 4,
        )
        val v = VideoModeSelector.validate(requested, caps, DeviceTier.MID)
        assertThat(v.codec).isEqualTo(VideoCodec.H264)
        assertThat(v.stabilization).isEqualTo(StabilizationMode.STANDARD)
        assertThat(v.hdr).isFalse()
        assertThat(v.flash).isEqualTo(FlashMode.SCREEN) // front camera without a flash unit
        assertThat(v.timerSeconds).isEqualTo(3)
        assertThat(v.cameraId).isEqualTo("1")
    }

    @Test
    fun `default mode is 1080p30`() {
        val modes = listOf(mode(3840, 2160, 60), mode(1920, 1080, 60), mode(1920, 1080, 30), mode(1280, 720, 30))
        assertThat(VideoModeSelector.defaultMode(modes, DeviceTier.HIGH)).isEqualTo(mode(1920, 1080, 30))
        assertThat(VideoModeSelector.defaultMode(listOf(mode(1280, 720, 30)), DeviceTier.LOW)).isEqualTo(mode(1280, 720, 30))
    }

    @Test
    fun `stream size prefers the exact recording size`() {
        val sizes = listOf(VideoSize(4000, 3000), VideoSize(3840, 2160), VideoSize(1920, 1080), VideoSize(1280, 720))
        assertThat(VideoModeSelector.streamSizeFor(VideoSize(1920, 1080), sizes)).isEqualTo(VideoSize(1920, 1080))
        assertThat(VideoModeSelector.streamSizeFor(VideoSize(2560, 1440), sizes)).isEqualTo(VideoSize(3840, 2160))
    }

    @Test
    fun `flash modes list only what exists`() {
        assertThat(VideoModeSelector.flashModes(caps(emptyList()))).containsExactly(FlashMode.OFF, FlashMode.SCREEN)
        assertThat(VideoModeSelector.flashModes(caps(emptyList()).copy(facing = LensFacing.BACK, flashAvailable = true)))
            .containsExactly(FlashMode.OFF, FlashMode.TORCH)
    }

    private fun mode(w: Int, h: Int, fps: Int) = VideoMode(VideoSize(w, h), fps, setOf(VideoCodec.H264, VideoCodec.HEVC))

    private fun caps(modes: List<VideoMode>) = CameraCapabilities(
        cameraId = "1",
        facing = LensFacing.FRONT,
        hardwareLevel = HardwareLevel.FULL,
        sensorOrientation = 270,
        isLogicalMultiCamera = false,
        physicalCameraIds = emptyList(),
        focalLengthsMm = listOf(3f),
        equivalentFocalLengthMm = 24f,
        lensKind = LensKind.WIDE,
        streamSizes = modes.map { it.size },
        videoModes = modes,
        fpsRanges = listOf(30 to 30),
        codecs = setOf(VideoCodec.H264),
        manualSensor = false,
        isoRange = null,
        exposureTimeRangeNs = null,
        manualFocus = false,
        minFocusDistanceDiopters = 0f,
        autoFocus = false,
        exposureCompensationRange = -12..12,
        exposureCompensationStep = 1f / 6f,
        whiteBalanceModes = listOf(WhiteBalanceMode.AUTO),
        manualKelvin = false,
        aeLockAvailable = true,
        awbLockAvailable = true,
        flashAvailable = false,
        stabilizationModes = listOf(StabilizationMode.OFF, StabilizationMode.STANDARD),
        electronicStabilization = true,
        opticalStabilization = false,
        hdrOptions = emptySet(),
        tenBitHdrOnDevice = false,
        maxAfRegions = 0,
        maxAeRegions = 1,
        zoomRange = 1f..4f,
        zoomRatioApi = true,
        activeArray = SensorArea(0, 0, 4000, 3000),
        timestampRealtime = false,
    )
}
