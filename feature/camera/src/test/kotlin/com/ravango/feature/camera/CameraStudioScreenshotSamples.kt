package com.ravango.feature.camera

import com.ravango.core.model.AspectRatioSpec
import com.ravango.core.model.CameraSettings
import com.ravango.core.model.CaptureMode
import com.ravango.core.model.Entitlements
import com.ravango.core.model.GridType
import com.ravango.core.model.LensFacing
import com.ravango.core.model.StabilizationMode
import com.ravango.core.model.VideoCodec
import com.ravango.core.model.VideoSize
import com.ravango.core.model.WhiteBalanceMode
import com.ravango.engine.beauty.effects.EffectsStatus
import com.ravango.engine.beauty.effects.Lens
import com.ravango.engine.camera.ActiveCameraConfig
import com.ravango.engine.camera.CameraState
import com.ravango.engine.camera.PreviewFrame
import com.ravango.engine.camera.capability.CameraCapabilities
import com.ravango.engine.camera.capability.HardwareLevel
import com.ravango.engine.camera.capability.LensKind
import com.ravango.engine.camera.capability.LensOption
import com.ravango.engine.camera.capability.OutputSize
import com.ravango.engine.camera.capability.SensorArea
import com.ravango.engine.camera.capability.VideoMode

/** Sample studio state for the lens tray screenshots (a streaming front camera). */
internal object CameraStudioScreenshotSamples {
    fun state(): CameraUiState {
        val caps = CameraCapabilities(
            cameraId = "1", facing = LensFacing.FRONT, hardwareLevel = HardwareLevel.FULL, sensorOrientation = 270,
            isLogicalMultiCamera = false, physicalCameraIds = emptyList(), focalLengthsMm = listOf(2.2f), equivalentFocalLengthMm = 24f,
            lensKind = LensKind.WIDE, streamSizes = listOf(VideoSize(1920, 1080)),
            videoModes = listOf(VideoMode(VideoSize(1920, 1080), 30, setOf(VideoCodec.H264)), VideoMode(VideoSize(1920, 1080), 60, setOf(VideoCodec.H264))),
            fpsRanges = listOf(30 to 30), codecs = setOf(VideoCodec.H264), manualSensor = true, isoRange = 100..3200,
            exposureTimeRangeNs = 100_000L..100_000_000L, manualFocus = false, minFocusDistanceDiopters = 0f, autoFocus = true,
            exposureCompensationRange = -12..12, exposureCompensationStep = 1f / 6f,
            whiteBalanceModes = listOf(WhiteBalanceMode.AUTO, WhiteBalanceMode.DAYLIGHT), manualKelvin = true,
            aeLockAvailable = true, awbLockAvailable = true, flashAvailable = false,
            stabilizationModes = listOf(StabilizationMode.OFF, StabilizationMode.STANDARD), electronicStabilization = true,
            opticalStabilization = false, hdrOptions = emptySet(), tenBitHdrOnDevice = false, maxAfRegions = 1, maxAeRegions = 1,
            zoomRange = 1f..8f, zoomRatioApi = true, activeArray = SensorArea(0, 0, 4000, 3000), timestampRealtime = true,
        )
        val config = ActiveCameraConfig(
            cameraId = "1", facing = LensFacing.FRONT, streamSize = VideoSize(1920, 1080), resolution = VideoSize(1920, 1080),
            output = OutputSize(1080, 1920), frameRate = 30, codec = VideoCodec.H264, aspectRatio = AspectRatioSpec.Portrait9x16,
            stabilization = StabilizationMode.STANDARD, hdr = false, mirrorPreview = true, mirrorRecording = false,
        )
        return CameraUiState(
            initialized = true,
            settings = CameraSettings(grid = GridType.NONE, captureMode = CaptureMode.VIDEO_WITH_AUDIO, timerSeconds = 3),
            cameraState = CameraState.Streaming(config),
            capabilities = caps,
            lenses = listOf(
                LensOption("u", "1", LensFacing.FRONT, LensKind.ULTRA_WIDE, 0.6f, 0.6f),
                LensOption("w", "1", LensFacing.FRONT, LensKind.WIDE, 1f, 1f),
            ),
            facings = listOf(LensFacing.FRONT, LensFacing.BACK),
            previewFrame = PreviewFrame(1080, 1920, 0),
            storageEstimateSeconds = 3 * 3600L,
            entitlements = Entitlements(),
            effectsStatus = EffectsStatus(),
        )
    }}
