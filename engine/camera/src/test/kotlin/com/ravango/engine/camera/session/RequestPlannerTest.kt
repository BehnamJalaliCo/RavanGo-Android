package com.ravango.engine.camera.session

import android.hardware.camera2.CameraMetadata
import com.google.common.truth.Truth.assertThat
import com.ravango.core.model.StabilizationMode
import org.junit.Test

class RequestPlannerTest {

    /** A fully featured back camera (FULL level, AF, OIS, manual sensor/post-processing, flash). */
    private val full = RequestSupport(
        controlModes = setOf(CameraMetadata.CONTROL_MODE_OFF, CameraMetadata.CONTROL_MODE_AUTO, CameraMetadata.CONTROL_MODE_USE_SCENE_MODE),
        aeModes = setOf(CameraMetadata.CONTROL_AE_MODE_OFF, CameraMetadata.CONTROL_AE_MODE_ON),
        afModes = setOf(
            CameraMetadata.CONTROL_AF_MODE_OFF, CameraMetadata.CONTROL_AF_MODE_AUTO,
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
        ),
        awbModes = setOf(CameraMetadata.CONTROL_AWB_MODE_OFF, CameraMetadata.CONTROL_AWB_MODE_AUTO, CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
        sceneModes = setOf(CameraMetadata.CONTROL_SCENE_MODE_HDR),
        videoStabilizationModes = setOf(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON),
        opticalStabilizationModes = setOf(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON),
        maxAfRegions = 1,
        maxAeRegions = 1,
        manualSensor = true,
        manualPostProcessing = true,
        fpsRanges = listOf(15 to 30, 30 to 30, 60 to 60),
        flashAvailable = true,
        minFocusDistance = 10f,
        evRange = -12..12,
        aeLockAvailable = true,
        zoomRatioRange = 1f..8f,
        maxDigitalZoom = 8f,
    )

    /** An emulated / LIMITED fixed-focus front camera: AF_MODE_OFF only, no regions, no manual controls, no flash. */
    private val fixedFront = RequestSupport(
        controlModes = setOf(CameraMetadata.CONTROL_MODE_AUTO),
        aeModes = setOf(CameraMetadata.CONTROL_AE_MODE_ON),
        afModes = setOf(CameraMetadata.CONTROL_AF_MODE_OFF),
        awbModes = setOf(CameraMetadata.CONTROL_AWB_MODE_AUTO),
        videoStabilizationModes = setOf(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF),
        fpsRanges = listOf(15 to 30, 30 to 30),
        minFocusDistance = 0f,
    )

    private val region = SensorRect(100, 100, 400, 400)

    private fun everything(support: RequestSupport) = RequestParams(
        fpsRange = 30 to 30,
        stabilization = StabilizationMode.STANDARD,
        hdrScene = true,
        torch = true,
        zoomRatio = 2f,
        zoomRatioApi = true,
        afMode = AfRequestMode.CONTINUOUS,
        afRegion = region,
        aeRegion = region,
        aeLock = true,
        evIndex = 3,
        awbMode = CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
        awbGains = floatArrayOf(2f, 1f, 1f, 1.5f),
        support = support,
    )

    @Test
    fun `fixed-focus camera gets no AF mode, no regions, no flash, no manual white balance`() {
        val plan = everything(fixedFront).plan(sdkInt = 28)
        assertThat(plan.values.keys).containsNoneOf(
            RequestKey.AF_MODE, RequestKey.AF_REGIONS, RequestKey.AE_REGIONS, RequestKey.FLASH_MODE, RequestKey.AE_LOCK,
            RequestKey.COLOR_CORRECTION_MODE, RequestKey.COLOR_CORRECTION_GAINS, RequestKey.SCENE_MODE,
            RequestKey.OPTICAL_STABILIZATION_MODE, RequestKey.AE_EXPOSURE_COMPENSATION, RequestKey.ZOOM_RATIO,
        )
        assertThat(plan[RequestKey.CONTROL_MODE]).isEqualTo(CameraMetadata.CONTROL_MODE_AUTO)
        assertThat(plan[RequestKey.AE_MODE]).isEqualTo(CameraMetadata.CONTROL_AE_MODE_ON)
        assertThat(plan[RequestKey.AWB_MODE]).isEqualTo(CameraMetadata.CONTROL_AWB_MODE_AUTO)
        // Stabilization requested but only OFF listed: OFF is sent, and the drop is reported.
        assertThat(plan[RequestKey.VIDEO_STABILIZATION_MODE]).isEqualTo(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        assertThat(plan.dropped.joinToString()).contains("AF_MODE")
        assertThat(plan.dropped.joinToString()).contains("FLASH_MODE=TORCH")
        assertThat(plan.dropped.joinToString()).contains("SCENE_MODE=HDR")
        assertThat(plan.dropped.joinToString()).contains("stabilization")
    }

    @Test
    fun `AF modes are chosen from the advertised list`() {
        val onlyAuto = full.copy(afModes = setOf(CameraMetadata.CONTROL_AF_MODE_OFF, CameraMetadata.CONTROL_AF_MODE_AUTO))
        assertThat(RequestParams(afMode = AfRequestMode.CONTINUOUS, support = onlyAuto).plan(33)[RequestKey.AF_MODE])
            .isEqualTo(CameraMetadata.CONTROL_AF_MODE_AUTO)
        assertThat(RequestParams(afMode = AfRequestMode.CONTINUOUS, support = full).plan(33)[RequestKey.AF_MODE])
            .isEqualTo(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        // A reported minimum focus distance of 0 means fixed focus even when AF modes are (wrongly) listed.
        assertThat(RequestKey.AF_MODE in RequestParams(afMode = AfRequestMode.AUTO, support = full.copy(minFocusDistance = 0f)).plan(33)).isFalse()
        // Not reported at all (some LEGACY HALs): trust the AF mode list.
        assertThat(RequestParams(afMode = AfRequestMode.CONTINUOUS, support = full.copy(minFocusDistance = null)).plan(28)[RequestKey.AF_MODE])
            .isEqualTo(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
    }

    @Test
    fun `manual focus needs AF_MODE_OFF and a focus range`() {
        val plan = RequestParams(afMode = AfRequestMode.OFF, focusDistance = 50f, support = full).plan(33)
        assertThat(plan[RequestKey.AF_MODE]).isEqualTo(CameraMetadata.CONTROL_AF_MODE_OFF)
        assertThat(plan[RequestKey.LENS_FOCUS_DISTANCE]).isEqualTo(10f) // clamped to the minimum focus distance
        val fixed = RequestParams(afMode = AfRequestMode.OFF, focusDistance = 1f, support = fixedFront).plan(33)
        assertThat(RequestKey.LENS_FOCUS_DISTANCE in fixed).isFalse()
    }

    @Test
    fun `regions only when the camera has metering regions`() {
        val plan = RequestParams(afMode = AfRequestMode.AUTO, afRegion = region, aeRegion = region, support = full).plan(33)
        assertThat(plan[RequestKey.AF_REGIONS]).isEqualTo(region)
        assertThat(plan[RequestKey.AE_REGIONS]).isEqualTo(region)
        val noRegions = RequestParams(afMode = AfRequestMode.AUTO, afRegion = region, aeRegion = region, support = full.copy(maxAfRegions = 0, maxAeRegions = 0)).plan(33)
        assertThat(noRegions.values.keys).containsNoneOf(RequestKey.AF_REGIONS, RequestKey.AE_REGIONS)
    }

    @Test
    fun `manual white balance needs MANUAL_POST_PROCESSING and AWB off`() {
        val gains = floatArrayOf(2f, 1f, 1f, 1.5f)
        val plan = RequestParams(awbGains = gains, support = full).plan(33)
        assertThat(plan[RequestKey.AWB_MODE]).isEqualTo(CameraMetadata.CONTROL_AWB_MODE_OFF)
        assertThat(plan[RequestKey.COLOR_CORRECTION_MODE]).isEqualTo(CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
        assertThat(plan[RequestKey.COLOR_CORRECTION_GAINS]).isEqualTo(gains)

        val noPost = RequestParams(awbGains = gains, support = full.copy(manualPostProcessing = false)).plan(33)
        assertThat(noPost.values.keys).containsNoneOf(RequestKey.COLOR_CORRECTION_MODE, RequestKey.COLOR_CORRECTION_GAINS)
        assertThat(noPost[RequestKey.AWB_MODE]).isEqualTo(CameraMetadata.CONTROL_AWB_MODE_AUTO)
        // Auto white balance never touches COLOR_CORRECTION_* (template defaults).
        assertThat(RequestKey.COLOR_CORRECTION_MODE in RequestParams(support = full).plan(33)).isFalse()
    }

    @Test
    fun `unlisted AWB mode falls back to auto`() {
        val plan = RequestParams(awbMode = CameraMetadata.CONTROL_AWB_MODE_SHADE, support = full).plan(33)
        assertThat(plan[RequestKey.AWB_MODE]).isEqualTo(CameraMetadata.CONTROL_AWB_MODE_AUTO)
        assertThat(plan.dropped.joinToString()).contains("AWB_MODE")
    }

    @Test
    fun `manual exposure needs MANUAL_SENSOR`() {
        val manual = RequestParams(manualIso = 400, manualExposureNs = 50_000_000, frameDurationNs = 33_333_333, support = full).plan(33)
        assertThat(manual[RequestKey.AE_MODE]).isEqualTo(CameraMetadata.CONTROL_AE_MODE_OFF)
        assertThat(manual[RequestKey.SENSOR_SENSITIVITY]).isEqualTo(400)
        assertThat(manual[RequestKey.SENSOR_EXPOSURE_TIME]).isEqualTo(33_333_333L) // never longer than a frame
        val limited = RequestParams(manualIso = 400, manualExposureNs = 10_000_000, support = full.copy(manualSensor = false)).plan(33)
        assertThat(limited[RequestKey.AE_MODE]).isEqualTo(CameraMetadata.CONTROL_AE_MODE_ON)
        assertThat(limited.values.keys).containsNoneOf(RequestKey.SENSOR_SENSITIVITY, RequestKey.SENSOR_EXPOSURE_TIME, RequestKey.SENSOR_FRAME_DURATION)
    }

    @Test
    fun `fps range is always one the camera lists`() {
        assertThat(RequestParams(fpsRange = 30 to 30, support = full).plan(33)[RequestKey.AE_TARGET_FPS_RANGE]).isEqualTo(30 to 30)
        // 24 to 24 is not listed: the closest listed range containing 24 fps is used instead.
        assertThat(RequestParams(fpsRange = 24 to 24, support = full).plan(33)[RequestKey.AE_TARGET_FPS_RANGE]).isEqualTo(15 to 30)
        val none = RequestParams(fpsRange = 120 to 120, support = full).plan(33)
        assertThat(RequestKey.AE_TARGET_FPS_RANGE in none).isFalse()
        assertThat(none.dropped.joinToString()).contains("AE_TARGET_FPS_RANGE")
    }

    @Test
    fun `hdr scene, stabilization, torch and zoom on a full camera`() {
        val plan = everything(full).copy(awbGains = null).plan(sdkInt = 34)
        assertThat(plan[RequestKey.CONTROL_MODE]).isEqualTo(CameraMetadata.CONTROL_MODE_USE_SCENE_MODE)
        assertThat(plan[RequestKey.SCENE_MODE]).isEqualTo(CameraMetadata.CONTROL_SCENE_MODE_HDR)
        assertThat(plan[RequestKey.VIDEO_STABILIZATION_MODE]).isEqualTo(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
        assertThat(plan[RequestKey.OPTICAL_STABILIZATION_MODE]).isEqualTo(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
        assertThat(plan[RequestKey.FLASH_MODE]).isEqualTo(CameraMetadata.FLASH_MODE_TORCH)
        assertThat(plan[RequestKey.ZOOM_RATIO]).isEqualTo(2f)
        assertThat(plan[RequestKey.AE_EXPOSURE_COMPENSATION]).isEqualTo(3)
        assertThat(plan[RequestKey.AE_LOCK]).isEqualTo(true)
        assertThat(plan.dropped).isEmpty()
    }

    @Test
    fun `zoom ratio needs API 30 and a ratio range, crop region needs digital zoom`() {
        val crop = SensorRect(500, 375, 3500, 2625)
        val p = RequestParams(zoomRatio = 2f, zoomRatioApi = true, cropRegion = crop, support = full)
        assertThat(RequestKey.ZOOM_RATIO in p.plan(29)).isFalse()
        assertThat(p.plan(29)[RequestKey.CROP_REGION]).isEqualTo(crop)
        assertThat(p.copy(zoomRatio = 20f).plan(30)[RequestKey.ZOOM_RATIO]).isEqualTo(8f) // clamped into range
        val noZoom = RequestParams(cropRegion = crop, support = fixedFront).plan(33)
        assertThat(RequestKey.CROP_REGION in noZoom).isFalse()
    }

    @Test
    fun `minimal request keeps only the fps range`() {
        val plan = everything(full).minimalCopy().plan(33)
        assertThat(plan.values.keys).containsExactly(RequestKey.AE_TARGET_FPS_RANGE)
        assertThat(plan[RequestKey.AE_TARGET_FPS_RANGE]).isEqualTo(30 to 30)
    }

    @Test
    fun `an unknown camera gets only keys every camera accepts`() {
        val plan = everything(RequestSupport()).plan(33)
        assertThat(plan.values.keys).containsExactly(RequestKey.CONTROL_MODE, RequestKey.AE_MODE, RequestKey.AWB_MODE)
    }
}
