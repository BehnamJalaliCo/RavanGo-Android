package com.ravango.engine.camera.capability

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Size
import com.ravango.core.common.log.RgLog
import com.ravango.core.model.LensFacing
import com.ravango.core.model.StabilizationMode
import com.ravango.core.model.VideoSize
import com.ravango.core.model.WhiteBalanceMode
import com.ravango.engine.camera.session.RequestSupport
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/** Reads Camera2 characteristics + encoder support into [CameraCapabilities]. Blocking; call off the main thread. */
@Singleton
class CapabilityDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encoders: EncoderSupport,
) {
    private val manager: CameraManager? get() = context.getSystemService(CameraManager::class.java)

    fun detectAll(): CameraInventory {
        val mgr = manager ?: return CameraInventory()
        val ids = runCatching { mgr.cameraIdList.toList() }
            .onFailure { RgLog.e(TAG, "cameraIdList failed", it) }
            .getOrDefault(emptyList())
        val optics = ArrayList<CameraOptics>()
        val caps = ids.mapNotNull { id ->
            runCatching {
                val chars = mgr.getCameraCharacteristics(id)
                detect(id, chars)?.also { optics += opticsOf(it, chars, mgr) }
            }.onFailure { RgLog.w(TAG, "camera $id skipped", it) }.getOrNull()
        }
        return CameraInventory(caps, LensClassifier.build(optics))
    }

    private fun opticsOf(caps: CameraCapabilities, chars: CameraCharacteristics, mgr: CameraManager): CameraOptics {
        val physicalEq = if (Build.VERSION.SDK_INT >= 28 && caps.isLogicalMultiCamera) {
            chars.physicalCameraIds.mapNotNull { pid ->
                runCatching { equivalentFocal(mgr.getCameraCharacteristics(pid)) }.getOrNull()
            }
        } else {
            emptyList()
        }
        return CameraOptics(
            cameraId = caps.cameraId,
            facing = caps.facing,
            equivalentFocalMm = caps.equivalentFocalLengthMm,
            isLogicalMultiCamera = caps.isLogicalMultiCamera,
            physicalEquivalentFocalMm = physicalEq,
            zoomRange = caps.zoomRange,
            zoomRatioApi = caps.zoomRatioApi,
        )
    }

    private fun equivalentFocal(chars: CameraCharacteristics): Float? {
        val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: return null
        val size = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        return LensClassifier.equivalentFocal(focal, size.width, size.height)
    }

    @Suppress("CyclomaticComplexMethod")
    private fun detect(id: String, chars: CameraCharacteristics): CameraCapabilities? {
        val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        // Depth-only or other non-color cameras cannot feed a preview.
        if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE !in capabilities) return null

        val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
            CameraMetadata.LENS_FACING_FRONT -> LensFacing.FRONT
            CameraMetadata.LENS_FACING_BACK -> LensFacing.BACK
            else -> LensFacing.EXTERNAL
        }
        val level = when (chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> HardwareLevel.LEGACY
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> HardwareLevel.FULL
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> HardwareLevel.LEVEL_3
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> HardwareLevel.EXTERNAL
            else -> HardwareLevel.LIMITED
        }
        val manualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilities
        val manualPost = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in capabilities
        val logical = Build.VERSION.SDK_INT >= 28 && CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in capabilities
        val physicalIds = if (Build.VERSION.SDK_INT >= 28 && logical) chars.physicalCameraIds.toList() else emptyList()
        val tenBit = Build.VERSION.SDK_INT >= 33 && CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT in capabilities

        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val outputSizes: Array<Size> = map.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()
        val streamSizes = outputSizes.map { VideoSize(max(it.width, it.height), min(it.width, it.height)) }.distinct()
        val sizeLookup = outputSizes.associateBy { VideoSize(max(it.width, it.height), min(it.width, it.height)) }
        val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.map { it.lower to it.upper } ?: emptyList()
        val codecs = encoders.availableCodecs()
        val modes = VideoModeSelector.buildModes(
            streamSizes = streamSizes,
            fpsRanges = fpsRanges,
            minFrameDurationNs = { size ->
                sizeLookup[size]?.let { runCatching { map.getOutputMinFrameDuration(SurfaceTexture::class.java, it) }.getOrDefault(0L) } ?: 0L
            },
            codecs = codecs,
            encoderSupports = encoders::supports,
            hardwareEncoderSupports = encoders::supportsInHardware,
        )

        val isoRange = if (manualSensor) chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { it.lower..it.upper } else null
        val exposureRange = if (manualSensor) chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { it.lower..it.upper } else null

        val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        val minFocusReported = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        val minFocus = minFocusReported ?: 0f
        // A minimum focus distance of 0 means a fixed-focus lens (typical front cameras): only AF_MODE_OFF works there.
        val autoFocus = (CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO in afModes || CameraMetadata.CONTROL_AF_MODE_AUTO in afModes) &&
            minFocusReported != 0f
        val manualFocus = minFocus > 0f && CameraMetadata.CONTROL_AF_MODE_OFF in afModes

        val evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.let { it.lower..it.upper } ?: 0..0
        val evStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f

        val awbModes = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf()
        val manualKelvin = manualPost && CameraMetadata.CONTROL_AWB_MODE_OFF in awbModes
        val wbModes = buildList {
            WB_MAPPING.forEach { (mode, value) -> if (value in awbModes) add(mode) }
            if (manualKelvin) add(WhiteBalanceMode.MANUAL_KELVIN)
        }.ifEmpty { listOf(WhiteBalanceMode.AUTO) }

        val eisModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: intArrayOf()
        val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: intArrayOf()
        val ois = CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON in oisModes
        val stabilization = buildList {
            add(StabilizationMode.OFF)
            if (CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON in eisModes || ois) add(StabilizationMode.STANDARD)
            if (Build.VERSION.SDK_INT >= 33 && CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION in eisModes) {
                add(StabilizationMode.PREVIEW_OPTIMIZED)
            }
        }

        val sceneModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES) ?: intArrayOf()
        val hdr = if (CameraMetadata.CONTROL_SCENE_MODE_HDR in sceneModes && level != HardwareLevel.LEGACY) setOf(HdrOption.SCENE_MODE) else emptySet()

        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val activeArea = if (active != null) SensorArea(active.left, active.top, active.width(), active.height()) else SensorArea(0, 0, 4000, 3000)

        val zoomRatioApi: Boolean
        val zoomRange: ClosedFloatingPointRange<Float>
        val ratioRange = if (Build.VERSION.SDK_INT >= 30) chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
        if (ratioRange != null && ratioRange.upper > ratioRange.lower) {
            zoomRatioApi = true
            zoomRange = ratioRange.lower..ratioRange.upper
        } else {
            zoomRatioApi = false
            zoomRange = 1f..max(1f, chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f)
        }

        val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList() ?: emptyList()
        val eq = equivalentFocal(chars)
        val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val aeLock = chars.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true
        val flash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        val maxAf = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        val maxAe = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        val requestSupport = RequestSupport(
            controlModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_MODES)?.toSet().orEmpty(),
            aeModes = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)?.toSet().orEmpty(),
            afModes = afModes.toSet(),
            awbModes = awbModes.toSet(),
            sceneModes = if (level != HardwareLevel.LEGACY) sceneModes.toSet() else emptySet(),
            videoStabilizationModes = eisModes.toSet(),
            opticalStabilizationModes = oisModes.toSet(),
            maxAfRegions = maxAf,
            maxAeRegions = maxAe,
            manualSensor = manualSensor,
            manualPostProcessing = manualPost,
            fpsRanges = fpsRanges,
            flashAvailable = flash,
            minFocusDistance = minFocusReported,
            evRange = evRange,
            aeLockAvailable = aeLock,
            zoomRatioRange = if (zoomRatioApi) zoomRange else null,
            maxDigitalZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f,
        )

        return CameraCapabilities(
            cameraId = id,
            facing = facing,
            hardwareLevel = level,
            sensorOrientation = sensorOrientation,
            isLogicalMultiCamera = logical,
            physicalCameraIds = physicalIds,
            focalLengthsMm = focal,
            equivalentFocalLengthMm = eq,
            lensKind = LensKind.WIDE,
            streamSizes = streamSizes,
            videoModes = modes,
            fpsRanges = fpsRanges,
            codecs = codecs,
            manualSensor = manualSensor,
            isoRange = isoRange,
            exposureTimeRangeNs = exposureRange,
            manualFocus = manualFocus,
            minFocusDistanceDiopters = minFocus,
            autoFocus = autoFocus,
            exposureCompensationRange = evRange,
            exposureCompensationStep = evStep,
            whiteBalanceModes = wbModes,
            manualKelvin = manualKelvin,
            aeLockAvailable = aeLock,
            awbLockAvailable = chars.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true,
            flashAvailable = flash,
            stabilizationModes = stabilization,
            electronicStabilization = CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON in eisModes,
            opticalStabilization = ois,
            hdrOptions = hdr,
            tenBitHdrOnDevice = tenBit,
            maxAfRegions = maxAf,
            maxAeRegions = maxAe,
            zoomRange = zoomRange,
            zoomRatioApi = zoomRatioApi,
            activeArray = activeArea,
            timestampRealtime = chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
            requestSupport = requestSupport,
        ).also {
            RgLog.d(
                TAG,
                "camera $id $facing level=$level modes=${modes.size} zoom=$zoomRange logical=$logical " +
                    "af=${afModes.toList()} minFocus=$minFocusReported awb=${awbModes.toList()} manualSensor=$manualSensor manualPost=$manualPost",
            )
        }
    }

    companion object {
        private const val TAG = "CameraCaps"

        val WB_MAPPING: List<Pair<WhiteBalanceMode, Int>> = listOf(
            WhiteBalanceMode.AUTO to CameraMetadata.CONTROL_AWB_MODE_AUTO,
            WhiteBalanceMode.INCANDESCENT to CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT,
            WhiteBalanceMode.FLUORESCENT to CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT,
            WhiteBalanceMode.WARM_FLUORESCENT to CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT,
            WhiteBalanceMode.DAYLIGHT to CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
            WhiteBalanceMode.CLOUDY to CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
            WhiteBalanceMode.TWILIGHT to CameraMetadata.CONTROL_AWB_MODE_TWILIGHT,
            WhiteBalanceMode.SHADE to CameraMetadata.CONTROL_AWB_MODE_SHADE,
        )
    }
}
