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

/**
 * Fully resolved values for the repeating TEMPLATE_RECORD request. What is actually put on the request is decided
 * by [RequestPlanner] against [support], so a value the camera does not advertise is never sent.
 */
data class RequestParams(
    val fpsRange: Pair<Int, Int>? = null,
    val stabilization: StabilizationMode = StabilizationMode.OFF,
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
    val evIndex: Int = 0,
    val manualIso: Int? = null,
    val manualExposureNs: Long? = null,
    val frameDurationNs: Long = 33_333_333,
    val awbMode: Int = CameraMetadata.CONTROL_AWB_MODE_AUTO,
    val awbGains: FloatArray? = null,
    val colorTransform: ColorSpaceTransform? = null,
    /** What the camera accepts; the conservative default keeps an unknown camera on a minimal request. */
    val support: RequestSupport = RequestSupport(),
    /** Template defaults + the fps range only: the fallback for a camera that keeps failing on the full request. */
    val minimal: Boolean = false,
) {
    val manualExposure: Boolean get() = manualIso != null && manualExposureNs != null

    /** The safest request for this camera: template defaults plus the AE target fps range. */
    fun minimalCopy(): RequestParams = RequestParams(fpsRange = fpsRange, support = support, minimal = true)

    fun plan(sdkInt: Int = Build.VERSION.SDK_INT): RequestPlan = RequestPlanner.plan(this, sdkInt)

    /** Applies the planned keys to [builder]; returns the plan (its `dropped` list is logged by the session). */
    fun applyTo(builder: CaptureRequest.Builder, sdkInt: Int = Build.VERSION.SDK_INT): RequestPlan {
        val plan = plan(sdkInt)
        for ((key, value) in plan.values) apply(builder, key, value, sdkInt)
        return plan
    }

    @Suppress("CyclomaticComplexMethod")
    private fun apply(builder: CaptureRequest.Builder, key: RequestKey, value: Any, sdkInt: Int) {
        when (key) {
            RequestKey.CONTROL_MODE -> builder.set(CaptureRequest.CONTROL_MODE, value as Int)
            RequestKey.SCENE_MODE -> builder.set(CaptureRequest.CONTROL_SCENE_MODE, value as Int)
            RequestKey.AE_TARGET_FPS_RANGE -> {
                val (lo, hi) = value as Pair<*, *>
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(lo as Int, hi as Int))
            }
            RequestKey.VIDEO_STABILIZATION_MODE -> builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, value as Int)
            RequestKey.OPTICAL_STABILIZATION_MODE -> builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, value as Int)
            RequestKey.AE_MODE -> builder.set(CaptureRequest.CONTROL_AE_MODE, value as Int)
            RequestKey.SENSOR_SENSITIVITY -> builder.set(CaptureRequest.SENSOR_SENSITIVITY, value as Int)
            RequestKey.SENSOR_EXPOSURE_TIME -> builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, value as Long)
            RequestKey.SENSOR_FRAME_DURATION -> builder.set(CaptureRequest.SENSOR_FRAME_DURATION, value as Long)
            RequestKey.AE_EXPOSURE_COMPENSATION -> builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, value as Int)
            RequestKey.AE_LOCK -> builder.set(CaptureRequest.CONTROL_AE_LOCK, value as Boolean)
            RequestKey.FLASH_MODE -> builder.set(CaptureRequest.FLASH_MODE, value as Int)
            RequestKey.AF_MODE -> builder.set(CaptureRequest.CONTROL_AF_MODE, value as Int)
            RequestKey.LENS_FOCUS_DISTANCE -> builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, value as Float)
            RequestKey.AF_REGIONS -> builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf((value as SensorRect).toMetering()))
            RequestKey.AE_REGIONS -> builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf((value as SensorRect).toMetering()))
            RequestKey.AWB_MODE -> builder.set(CaptureRequest.CONTROL_AWB_MODE, value as Int)
            RequestKey.COLOR_CORRECTION_MODE -> builder.set(CaptureRequest.COLOR_CORRECTION_MODE, value as Int)
            RequestKey.COLOR_CORRECTION_GAINS -> {
                val g = value as FloatArray
                builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(g[0], g[1], g[2], g[3]))
            }
            RequestKey.COLOR_CORRECTION_TRANSFORM -> builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, value as ColorSpaceTransform)
            RequestKey.ZOOM_RATIO -> if (sdkInt >= 30) builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, value as Float)
            RequestKey.CROP_REGION -> {
                val r = value as SensorRect
                builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(r.left, r.top, r.right, r.bottom))
            }
        }
    }

    private fun SensorRect.toMetering() = MeteringRectangle(Rect(left, top, right, bottom), MeteringRectangle.METERING_WEIGHT_MAX - 1)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RequestParams) return false
        return fpsRange == other.fpsRange && stabilization == other.stabilization && hdrScene == other.hdrScene &&
            torch == other.torch && zoomRatio == other.zoomRatio && zoomRatioApi == other.zoomRatioApi &&
            cropRegion == other.cropRegion && afMode == other.afMode && focusDistance == other.focusDistance &&
            afRegion == other.afRegion && aeRegion == other.aeRegion && aeLock == other.aeLock && evIndex == other.evIndex &&
            manualIso == other.manualIso && manualExposureNs == other.manualExposureNs && frameDurationNs == other.frameDurationNs &&
            awbMode == other.awbMode && awbGains.contentEquals(other.awbGains) && colorTransform == other.colorTransform &&
            support == other.support && minimal == other.minimal
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
        result = 31 * result + minimal.hashCode()
        return result
    }
}
