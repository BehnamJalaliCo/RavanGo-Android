package com.ravango.engine.camera.session

import android.hardware.camera2.CameraMetadata
import com.ravango.core.model.StabilizationMode
import com.ravango.engine.camera.capability.VideoModeSelector

/**
 * What a camera accepts in a capture request, read from its CameraCharacteristics by the capability detector.
 * LIMITED / LEGACY / EXTERNAL and emulated HALs reject (or fail the whole device on) keys and values they do not
 * advertise, so every key the planner sets must be backed by one of these facts.
 *
 * The defaults describe the most conservative camera (only what every Camera2 device must support), so a
 * [RequestSupport] that was never filled in produces a minimal, safe request.
 */
data class RequestSupport(
    /** CONTROL_AVAILABLE_MODES; empty = not reported (CONTROL_MODE_AUTO is then assumed). */
    val controlModes: Set<Int> = emptySet(),
    /** CONTROL_AE_AVAILABLE_MODES; empty = not reported (AE_MODE_ON is then assumed). */
    val aeModes: Set<Int> = emptySet(),
    /** CONTROL_AF_AVAILABLE_MODES (fixed-focus cameras report only AF_MODE_OFF). */
    val afModes: Set<Int> = emptySet(),
    /** CONTROL_AWB_AVAILABLE_MODES; empty = not reported (AWB_MODE_AUTO is then assumed). */
    val awbModes: Set<Int> = emptySet(),
    /** CONTROL_AVAILABLE_SCENE_MODES. */
    val sceneModes: Set<Int> = emptySet(),
    /** CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES. */
    val videoStabilizationModes: Set<Int> = emptySet(),
    /** LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION. */
    val opticalStabilizationModes: Set<Int> = emptySet(),
    val maxAfRegions: Int = 0,
    val maxAeRegions: Int = 0,
    /** REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR. */
    val manualSensor: Boolean = false,
    /** REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING. */
    val manualPostProcessing: Boolean = false,
    /** CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES as (lower, upper). */
    val fpsRanges: List<Pair<Int, Int>> = emptyList(),
    /** FLASH_INFO_AVAILABLE. */
    val flashAvailable: Boolean = false,
    /** LENS_INFO_MINIMUM_FOCUS_DISTANCE in diopters (0 = fixed focus, null = not reported, as on some LEGACY HALs). */
    val minFocusDistance: Float? = 0f,
    /** CONTROL_AE_COMPENSATION_RANGE. */
    val evRange: IntRange = 0..0,
    /** CONTROL_AE_LOCK_AVAILABLE. */
    val aeLockAvailable: Boolean = false,
    /** CONTROL_ZOOM_RATIO_RANGE (API 30+), null when the camera zooms with SCALER_CROP_REGION only. */
    val zoomRatioRange: ClosedFloatingPointRange<Float>? = null,
    /** SCALER_AVAILABLE_MAX_DIGITAL_ZOOM. */
    val maxDigitalZoom: Float = 1f,
)

/** Capture-request keys the planner can set; mapped to `CaptureRequest` keys by [RequestParams.applyTo]. */
enum class RequestKey {
    CONTROL_MODE,
    SCENE_MODE,
    AE_TARGET_FPS_RANGE,
    VIDEO_STABILIZATION_MODE,
    OPTICAL_STABILIZATION_MODE,
    AE_MODE,
    SENSOR_SENSITIVITY,
    SENSOR_EXPOSURE_TIME,
    SENSOR_FRAME_DURATION,
    AE_EXPOSURE_COMPENSATION,
    AE_LOCK,
    FLASH_MODE,
    AF_MODE,
    LENS_FOCUS_DISTANCE,
    AF_REGIONS,
    AE_REGIONS,
    AWB_MODE,
    COLOR_CORRECTION_MODE,
    COLOR_CORRECTION_GAINS,
    COLOR_CORRECTION_TRANSFORM,
    ZOOM_RATIO,
    CROP_REGION,
}

/**
 * The keys to put on top of the TEMPLATE_RECORD defaults. Values are plain Kotlin types (Int, Long, Float, Boolean,
 * `Pair<Int, Int>` for ranges, [SensorRect], FloatArray for RGGB gains, or the ColorSpaceTransform passed in) so the
 * planning is unit-testable without the Android framework. [dropped] lists requested values the camera does not
 * support (for the diagnostics log).
 */
data class RequestPlan(val values: Map<RequestKey, Any>, val dropped: List<String>) {
    operator fun get(key: RequestKey): Any? = values[key]
    operator fun contains(key: RequestKey): Boolean = key in values
}

/** Pure mapping of [RequestParams] + [RequestSupport] to the request keys a camera will accept. */
object RequestPlanner {

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun plan(p: RequestParams, sdkInt: Int): RequestPlan {
        val s = p.support
        val v = LinkedHashMap<RequestKey, Any>()
        val dropped = ArrayList<String>()

        // Exactly one of the advertised AE target ranges (never a made-up one).
        p.fpsRange?.let { wanted ->
            val range = if (wanted in s.fpsRanges) wanted else VideoModeSelector.aeRangeFor(wanted.second, s.fpsRanges)
            if (range != null) v[RequestKey.AE_TARGET_FPS_RANGE] = range else dropped += "AE_TARGET_FPS_RANGE=$wanted (available ${s.fpsRanges})"
        }
        if (p.minimal) return RequestPlan(v, dropped)

        // --- control mode / HDR scene ---
        val sceneModeOk = s.controlModes.isEmpty() || CameraMetadata.CONTROL_MODE_USE_SCENE_MODE in s.controlModes
        val hdr = p.hdrScene && CameraMetadata.CONTROL_SCENE_MODE_HDR in s.sceneModes && sceneModeOk
        if (p.hdrScene && !hdr) dropped += "SCENE_MODE=HDR"
        if (hdr) {
            v[RequestKey.CONTROL_MODE] = CameraMetadata.CONTROL_MODE_USE_SCENE_MODE
            v[RequestKey.SCENE_MODE] = CameraMetadata.CONTROL_SCENE_MODE_HDR
        } else if (s.controlModes.isEmpty() || CameraMetadata.CONTROL_MODE_AUTO in s.controlModes) {
            v[RequestKey.CONTROL_MODE] = CameraMetadata.CONTROL_MODE_AUTO
        }

        // --- stabilization ---
        val eisModes = s.videoStabilizationModes
        val wantsStabilization = p.stabilization != StabilizationMode.OFF
        val eis = when {
            p.stabilization == StabilizationMode.PREVIEW_OPTIMIZED && sdkInt >= 33 && EIS_PREVIEW in eisModes -> EIS_PREVIEW
            wantsStabilization && CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON in eisModes -> CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF in eisModes -> CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            else -> null
        }
        eis?.let { v[RequestKey.VIDEO_STABILIZATION_MODE] = it }
        val oisModes = s.opticalStabilizationModes
        val ois = when {
            wantsStabilization && CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON in oisModes -> CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
            CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF in oisModes -> CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
            else -> null
        }
        ois?.let { v[RequestKey.OPTICAL_STABILIZATION_MODE] = it }
        if (wantsStabilization && eis != CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON && eis != EIS_PREVIEW &&
            ois != CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
        ) {
            dropped += "stabilization=${p.stabilization}"
        }

        // --- exposure ---
        val aeOffOk = CameraMetadata.CONTROL_AE_MODE_OFF in s.aeModes
        val manual = p.manualExposure && !hdr && s.manualSensor && aeOffOk
        if (p.manualExposure && !manual) dropped += "manual exposure (MANUAL_SENSOR / AE_MODE_OFF not available)"
        if (manual) {
            v[RequestKey.AE_MODE] = CameraMetadata.CONTROL_AE_MODE_OFF
            v[RequestKey.SENSOR_SENSITIVITY] = p.manualIso!!
            v[RequestKey.SENSOR_EXPOSURE_TIME] = p.manualExposureNs!!.coerceAtMost(p.frameDurationNs)
            v[RequestKey.SENSOR_FRAME_DURATION] = p.frameDurationNs
        } else {
            if (s.aeModes.isEmpty() || CameraMetadata.CONTROL_AE_MODE_ON in s.aeModes) v[RequestKey.AE_MODE] = CameraMetadata.CONTROL_AE_MODE_ON
            if (s.evRange.last > s.evRange.first) {
                v[RequestKey.AE_EXPOSURE_COMPENSATION] = p.evIndex.coerceIn(s.evRange)
            } else if (p.evIndex != 0) {
                dropped += "AE_EXPOSURE_COMPENSATION=${p.evIndex}"
            }
            if (s.aeLockAvailable) {
                v[RequestKey.AE_LOCK] = p.aeLock
            } else if (p.aeLock) {
                dropped += "AE_LOCK"
            }
        }
        if (p.aeRegion != null) {
            if (s.maxAeRegions > 0 && !manual) v[RequestKey.AE_REGIONS] = p.aeRegion else dropped += "AE_REGIONS"
        }

        // --- flash ---
        if (s.flashAvailable) {
            v[RequestKey.FLASH_MODE] = if (p.torch) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF
        } else if (p.torch) {
            dropped += "FLASH_MODE=TORCH"
        }

        // --- focus ---
        val minFocus = s.minFocusDistance
        val fixedFocus = minFocus == 0f || s.afModes.none { it != CameraMetadata.CONTROL_AF_MODE_OFF }
        val afMode: Int? = when (p.afMode) {
            AfRequestMode.NONE -> null
            AfRequestMode.CONTINUOUS -> if (fixedFocus) {
                null
            } else {
                listOf(
                    CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
                    CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                    CameraMetadata.CONTROL_AF_MODE_AUTO,
                ).firstOrNull { it in s.afModes }
            }
            AfRequestMode.AUTO -> if (fixedFocus) {
                null
            } else {
                listOf(CameraMetadata.CONTROL_AF_MODE_AUTO, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO).firstOrNull { it in s.afModes }
            }
            AfRequestMode.OFF -> CameraMetadata.CONTROL_AF_MODE_OFF.takeIf { it in s.afModes && minFocus != null && minFocus > 0f }
        }
        if (afMode == null && p.afMode != AfRequestMode.NONE) dropped += "AF_MODE=${p.afMode} (available ${s.afModes.sorted()}, min focus $minFocus)"
        if (afMode != null) {
            v[RequestKey.AF_MODE] = afMode
            if (afMode == CameraMetadata.CONTROL_AF_MODE_OFF) v[RequestKey.LENS_FOCUS_DISTANCE] = p.focusDistance.coerceIn(0f, minFocus!!)
        }
        if (p.afRegion != null) {
            val afActive = afMode != null && afMode != CameraMetadata.CONTROL_AF_MODE_OFF
            if (s.maxAfRegions > 0 && afActive) v[RequestKey.AF_REGIONS] = p.afRegion else dropped += "AF_REGIONS"
        }

        // --- white balance ---
        val awbAuto = s.awbModes.isEmpty() || CameraMetadata.CONTROL_AWB_MODE_AUTO in s.awbModes
        val gains = p.awbGains
        val manualWb = gains != null && gains.size == 4 && !hdr && s.manualPostProcessing && CameraMetadata.CONTROL_AWB_MODE_OFF in s.awbModes
        if (gains != null && !manualWb) dropped += "manual white balance (MANUAL_POST_PROCESSING / AWB_MODE_OFF not available)"
        if (manualWb) {
            v[RequestKey.AWB_MODE] = CameraMetadata.CONTROL_AWB_MODE_OFF
            v[RequestKey.COLOR_CORRECTION_MODE] = CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
            v[RequestKey.COLOR_CORRECTION_GAINS] = gains!!
            p.colorTransform?.let { v[RequestKey.COLOR_CORRECTION_TRANSFORM] = it }
        } else {
            val awb = when {
                p.awbMode in s.awbModes -> p.awbMode
                awbAuto -> CameraMetadata.CONTROL_AWB_MODE_AUTO
                else -> null
            }
            if (awb != p.awbMode) dropped += "AWB_MODE=${p.awbMode}"
            awb?.let { v[RequestKey.AWB_MODE] = it }
            // COLOR_CORRECTION_* stay at the template defaults: only MANUAL_POST_PROCESSING cameras accept them.
        }

        // --- zoom ---
        val ratioRange = s.zoomRatioRange
        if (p.zoomRatioApi && sdkInt >= 30 && ratioRange != null) {
            v[RequestKey.ZOOM_RATIO] = p.zoomRatio.coerceIn(ratioRange)
        } else if (p.cropRegion != null) {
            if (s.maxDigitalZoom > 1f) v[RequestKey.CROP_REGION] = p.cropRegion else dropped += "SCALER_CROP_REGION"
        }
        return RequestPlan(v, dropped)
    }

    /** CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION (API 33). */
    private const val EIS_PREVIEW = 2
}
