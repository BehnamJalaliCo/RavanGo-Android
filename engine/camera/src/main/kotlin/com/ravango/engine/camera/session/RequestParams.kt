package com.ravango.engine.camera.session

import android.graphics.Rect
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.RggbChannelVector
import android.os.Build
import android.util.Range
import com.ravango.core.model.StabilizationMode

enum class AfRequestMode { CONTINUOUS, AUTO, OFF, NONE }

/** Fully resolved values for the repeating TEMPLATE_RECORD request. */
data class RequestParams(
    val fpsRange: Pair<Int, Int>? = null,
    val stabilization: StabilizationMode = StabilizationMode.OFF,
    val eisAvailable: Boolean = false,
    val oisAvailable: Boolean = false,
    val hdrScene: Boolean = false,
    val torch: Boolean = false,
    val zoomRatio: Float = 1f,
    val zoomRatioApi: Boolean = false,
    val cropRegion: SensorRect? = null,
    val afMode: AfRequestMode = AfRequestMode.CONTINUOUS,
    val focusDistance: Float = 0f,
    val afRegion: SensorRect? = null,
    val aeRegion: SensorRect? = null,
    val aeLock: Boolean = false,
    val aeLockAvailable: Boolean = false,
    val evIndex: Int = 0,
    val manualIso: Int? = null,
    val manualExposureNs: Long? = null,
    val frameDurationNs: Long = 33_333_333,
    val awbMode: Int = CameraMetadata.CONTROL_AWB_MODE_AUTO,
    val awbGains: FloatArray? = null,
    val colorTransform: ColorSpaceTransform? = null,
) {
    val manualExposure: Boolean get() = manualIso != null && manualExposureNs != null

    fun applyTo(builder: CaptureRequest.Builder) {
        if (hdrScene) {
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_USE_SCENE_MODE)
            builder.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_HDR)
        } else {
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        }
        fpsRange?.let { (lo, hi) -> builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(lo, hi)) }

        if (eisAvailable) {
            val eis = when {
                stabilization == StabilizationMode.PREVIEW_OPTIMIZED && Build.VERSION.SDK_INT >= 33 -> CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
                stabilization != StabilizationMode.OFF -> CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
                else -> CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            }
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, eis)
        }
        if (oisAvailable) {
            builder.set(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                if (stabilization != StabilizationMode.OFF) CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON else CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF,
            )
        }

        if (manualExposure && !hdrScene) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, manualIso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualExposureNs!!.coerceAtMost(frameDurationNs))
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, frameDurationNs)
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evIndex)
            if (aeLockAvailable) builder.set(CaptureRequest.CONTROL_AE_LOCK, aeLock)
        }
        builder.set(CaptureRequest.FLASH_MODE, if (torch) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)

        when (afMode) {
            AfRequestMode.CONTINUOUS -> builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            AfRequestMode.AUTO -> builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
            AfRequestMode.OFF -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
            }
            AfRequestMode.NONE -> Unit
        }
        builder.set(CaptureRequest.CONTROL_AF_REGIONS, afRegion?.let { arrayOf(it.toMetering()) })
        builder.set(CaptureRequest.CONTROL_AE_REGIONS, aeRegion?.let { arrayOf(it.toMetering()) })

        if (awbGains != null && !hdrScene) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(awbGains[0], awbGains[1], awbGains[2], awbGains[3]))
            colorTransform?.let { builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, it) }
        } else {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, awbMode)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_FAST)
        }

        if (zoomRatioApi && Build.VERSION.SDK_INT >= 30) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
        } else if (cropRegion != null) {
            builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(cropRegion.left, cropRegion.top, cropRegion.right, cropRegion.bottom))
        }
    }

    private fun SensorRect.toMetering() = MeteringRectangle(Rect(left, top, right, bottom), MeteringRectangle.METERING_WEIGHT_MAX - 1)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RequestParams) return false
        return fpsRange == other.fpsRange && stabilization == other.stabilization && eisAvailable == other.eisAvailable &&
            oisAvailable == other.oisAvailable && hdrScene == other.hdrScene && torch == other.torch && zoomRatio == other.zoomRatio &&
            zoomRatioApi == other.zoomRatioApi && cropRegion == other.cropRegion && afMode == other.afMode &&
            focusDistance == other.focusDistance && afRegion == other.afRegion && aeRegion == other.aeRegion &&
            aeLock == other.aeLock && aeLockAvailable == other.aeLockAvailable && evIndex == other.evIndex &&
            manualIso == other.manualIso && manualExposureNs == other.manualExposureNs && frameDurationNs == other.frameDurationNs &&
            awbMode == other.awbMode && awbGains.contentEquals(other.awbGains) && colorTransform == other.colorTransform
    }

    override fun hashCode(): Int {
        var result = fpsRange.hashCode()
        result = 31 * result + stabilization.hashCode()
        result = 31 * result + zoomRatio.hashCode()
        result = 31 * result + afMode.hashCode()
        result = 31 * result + evIndex
        result = 31 * result + (manualIso ?: 0)
        result = 31 * result + awbMode
        result = 31 * result + (awbGains?.contentHashCode() ?: 0)
        return result
    }
}
