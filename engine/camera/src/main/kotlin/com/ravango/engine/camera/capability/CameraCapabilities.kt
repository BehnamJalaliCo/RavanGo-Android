package com.ravango.engine.camera.capability

import com.ravango.core.model.LensFacing
import com.ravango.core.model.StabilizationMode
import com.ravango.core.model.VideoCodec
import com.ravango.core.model.VideoSize
import com.ravango.core.model.WhiteBalanceMode
import com.ravango.engine.camera.session.RequestSupport
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Camera2 hardware level, ordered from least to most capable. */
enum class HardwareLevel { LEGACY, LIMITED, EXTERNAL, FULL, LEVEL_3 }

/** Optical class of a lens, relative to the main (wide) camera of the same facing. */
enum class LensKind { ULTRA_WIDE, WIDE, TELE }

/**
 * A lens the user can pick with a chip ("0.5×", "1×", "3×"). Either a separate camera id, or a zoom ratio on a
 * logical multi-camera (API 30+, where the HAL switches the physical sensor for us).
 */
data class LensOption(
    /** Stable key for UI lists. */
    val key: String,
    val cameraId: String,
    val facing: LensFacing,
    val kind: LensKind,
    /** Field-of-view factor relative to the main camera of this facing (0.5 = ultra-wide, 3 = 3× tele). */
    val factor: Float,
    /** Zoom ratio to apply on [cameraId] when this lens is selected. */
    val zoomRatio: Float,
) {
    /** "0.5×", "1×", "2.5×". */
    val label: String
        get() {
            val rounded = (factor * 10f).roundToInt() / 10f
            val text = if (abs(rounded - rounded.roundToInt()) < 0.05f) rounded.roundToInt().toString() else String.format(Locale.US, "%.1f", rounded)
            return "$text×"
        }
}

/** A recordable (size, fps) pair and the codecs that can encode it on this device. */
data class VideoMode(val size: VideoSize, val fps: Int, val codecs: Set<VideoCodec>)

/** Active pixel array of the sensor, used for 3A region coordinates. */
data class SensorArea(val left: Int, val top: Int, val width: Int, val height: Int)

/** HDR options the pipeline can deliver end-to-end. */
enum class HdrOption {
    /** CONTROL_SCENE_MODE_HDR on the video stream (8-bit SDR output, tone-mapped by the ISP). */
    SCENE_MODE,
}

/**
 * Everything the camera UI may offer for one camera id. Built by [CapabilityDetector] from CameraCharacteristics
 * and MediaCodec capabilities. The UI must only show options present here.
 */
data class CameraCapabilities(
    val cameraId: String,
    val facing: LensFacing,
    val hardwareLevel: HardwareLevel,
    /** Clockwise rotation (0/90/180/270) of the sensor image relative to the device's natural orientation. */
    val sensorOrientation: Int,
    val isLogicalMultiCamera: Boolean,
    val physicalCameraIds: List<String>,
    val focalLengthsMm: List<Float>,
    /** 35mm-equivalent focal length of the main focal length, when the sensor size is known. */
    val equivalentFocalLengthMm: Float?,
    val lensKind: LensKind,
    /** Camera buffer sizes offered to a SurfaceTexture (landscape convention). */
    val streamSizes: List<VideoSize>,
    /** Size × fps combinations valid for a normal (non high-speed) session and supported by an encoder. */
    val videoModes: List<VideoMode>,
    /** AE target fps ranges as (lower, upper). */
    val fpsRanges: List<Pair<Int, Int>>,
    /** Codecs with at least one hardware/software encoder on the device. */
    val codecs: Set<VideoCodec>,
    val manualSensor: Boolean,
    val isoRange: IntRange?,
    val exposureTimeRangeNs: LongRange?,
    val manualFocus: Boolean,
    /** Closest focus distance in diopters (0 = fixed focus). */
    val minFocusDistanceDiopters: Float,
    val autoFocus: Boolean,
    val exposureCompensationRange: IntRange,
    val exposureCompensationStep: Float,
    val whiteBalanceModes: List<WhiteBalanceMode>,
    /** True when MANUAL_POST_PROCESSING allows Kelvin white balance through color-correction gains. */
    val manualKelvin: Boolean,
    val aeLockAvailable: Boolean,
    val awbLockAvailable: Boolean,
    val flashAvailable: Boolean,
    val stabilizationModes: List<StabilizationMode>,
    /** CONTROL_VIDEO_STABILIZATION_MODE_ON (EIS) is available. */
    val electronicStabilization: Boolean,
    val opticalStabilization: Boolean,
    val hdrOptions: Set<HdrOption>,
    /** The device supports 10-bit HLG output; not offered because the GL pipeline and encoder path are 8-bit. */
    val tenBitHdrOnDevice: Boolean,
    val maxAfRegions: Int,
    val maxAeRegions: Int,
    val zoomRange: ClosedFloatingPointRange<Float>,
    /** True when zoom uses CONTROL_ZOOM_RATIO (API 30+), false for SCALER_CROP_REGION. */
    val zoomRatioApi: Boolean,
    val activeArray: SensorArea,
    /** SENSOR_INFO_TIMESTAMP_SOURCE == REALTIME (elapsedRealtimeNanos base). */
    val timestampRealtime: Boolean,
    /** Request keys/values this camera advertises; every capture request is gated by it (see RequestPlanner). */
    val requestSupport: RequestSupport = RequestSupport(),
) {
    /** Front cameras without a flash unit can light the face with the screen instead. */
    val screenFlash: Boolean get() = facing == LensFacing.FRONT

    val tapToFocus: Boolean get() = autoFocus && maxAfRegions > 0
    val tapToMeter: Boolean get() = maxAeRegions > 0
    val exposureCompensation: Boolean get() = exposureCompensationRange.last > exposureCompensationRange.first
    val manualExposure: Boolean get() = manualSensor && isoRange != null && exposureTimeRangeNs != null
    val videoSizes: List<VideoSize> get() = videoModes.map { it.size }.distinct().sortedDescending()

    fun frameRatesFor(size: VideoSize): List<Int> = videoModes.filter { it.size == size }.map { it.fps }.distinct().sorted()
    fun modeFor(size: VideoSize, fps: Int): VideoMode? = videoModes.firstOrNull { it.size == size && it.fps == fps }
}

/** All cameras on the device plus the lens chips per facing. */
data class CameraInventory(
    val cameras: List<CameraCapabilities> = emptyList(),
    val lenses: Map<LensFacing, List<LensOption>> = emptyMap(),
) {
    val facings: List<LensFacing> get() = cameras.map { it.facing }.distinct()
    fun camera(id: String): CameraCapabilities? = cameras.firstOrNull { it.cameraId == id }

    /** The main camera for [facing]: the first public id with that facing (Android convention). */
    fun main(facing: LensFacing): CameraCapabilities? =
        lenses[facing]?.firstOrNull { it.kind == LensKind.WIDE && it.zoomRatio == 1f }?.let { camera(it.cameraId) }
            ?: cameras.firstOrNull { it.facing == facing }

    val isEmpty: Boolean get() = cameras.isEmpty()
}
