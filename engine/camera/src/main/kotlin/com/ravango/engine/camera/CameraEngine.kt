package com.ravango.engine.camera

import android.view.Surface
import com.ravango.core.common.device.ThermalLevel
import com.ravango.core.common.result.ErrorKind
import com.ravango.core.common.result.Outcome
import com.ravango.core.model.AspectRatioSpec
import com.ravango.core.model.CameraSettings
import com.ravango.core.model.CaptureMode
import com.ravango.core.model.LensFacing
import com.ravango.core.model.ManualControls
import com.ravango.core.model.StabilizationMode
import com.ravango.core.model.VideoCodec
import com.ravango.core.model.VideoSize
import com.ravango.core.model.WhiteBalanceMode
import com.ravango.engine.camera.capability.CameraCapabilities
import com.ravango.engine.camera.capability.CameraInventory
import com.ravango.engine.camera.capability.LensOption
import com.ravango.engine.camera.capability.OutputSize
import com.ravango.engine.render.GlFrameProcessor
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Why the camera could not stream. */
enum class CameraErrorKind { PERMISSION, NO_CAMERA, IN_USE, MAX_CAMERAS_IN_USE, DISABLED, DEVICE, SERVICE, CONFIGURATION, UNKNOWN }

sealed interface CameraState {
    data object Closed : CameraState
    data object Opening : CameraState
    data class Streaming(val config: ActiveCameraConfig) : CameraState
    /** [retrying] = the engine will reopen by itself (e.g. when another app releases the camera). */
    data class Error(val kind: CameraErrorKind, val message: String? = null, val retrying: Boolean = false) : CameraState
}

/** The configuration actually running (after validation against capabilities). */
data class ActiveCameraConfig(
    val cameraId: String,
    val facing: LensFacing,
    /** Camera buffer size (sensor orientation, landscape). */
    val streamSize: VideoSize,
    /** Recording size requested (landscape convention). */
    val resolution: VideoSize,
    /** Frame size produced by the GL pipeline and encoded (already cropped to [aspectRatio], upright). */
    val output: OutputSize,
    val frameRate: Int,
    val codec: VideoCodec,
    val aspectRatio: AspectRatioSpec,
    val stabilization: StabilizationMode,
    val hdr: Boolean,
    val mirrorPreview: Boolean,
    val mirrorRecording: Boolean,
)

/** What the preview surface shows: the output frame rotated for the portrait-locked screen. */
data class PreviewFrame(val outputWidth: Int, val outputHeight: Int, val rotationCw: Int) {
    val displayWidth: Int get() = if (rotationCw % 180 == 90) outputHeight else outputWidth
    val displayHeight: Int get() = if (rotationCw % 180 == 90) outputWidth else outputHeight
}

enum class FocusMode { CONTINUOUS, POINT, MANUAL, LOCKED, FIXED }
enum class AfStatus { INACTIVE, SCANNING, FOCUSED, FAILED }

data class FocusState(
    val mode: FocusMode = FocusMode.CONTINUOUS,
    /** Normalized point in the displayed preview frame, when metering at a point. */
    val pointX: Float? = null,
    val pointY: Float? = null,
    val afStatus: AfStatus = AfStatus.INACTIVE,
    val aeLocked: Boolean = false,
)

/** Values chosen by auto exposure / focus, shown next to manual controls. */
data class LiveExposure(
    val iso: Int? = null,
    val exposureTimeNs: Long? = null,
    val focusDistanceDiopters: Float? = null,
)

data class PipelineStats(
    val previewFps: Float = 0f,
    val encoderFps: Float = 0f,
    val droppedCameraFrames: Long = 0,
    val droppedPreviewFrames: Long = 0,
    val processorMs: Float = 0f,
    val frameBudgetMs: Float = 0f,
)

enum class RecordingPhase { IDLE, STARTING, RECORDING, PAUSED, FINALIZING }

enum class StopReason { USER, LIFECYCLE, LOW_STORAGE, THERMAL, CAMERA_ERROR, ENCODER_ERROR }

data class RecordingStatus(
    val phase: RecordingPhase = RecordingPhase.IDLE,
    val captureMode: CaptureMode = CaptureMode.VIDEO_WITH_AUDIO,
    val durationUs: Long = 0,
    val bytesWritten: Long = 0,
    /** Estimated seconds that still fit on storage at the current bitrate. */
    val remainingSeconds: Long = Long.MAX_VALUE,
    val videoBitrate: Int = 0,
    val audioBitrate: Int = 0,
    val output: OutputSize? = null,
    val frameRate: Int = 0,
) {
    val isActive: Boolean get() = phase == RecordingPhase.RECORDING || phase == RecordingPhase.PAUSED || phase == RecordingPhase.STARTING
}

/** A finished recording on disk. */
data class RecordedMedia(
    val file: File,
    val captureMode: CaptureMode,
    val mimeType: String,
    val durationUs: Long,
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val sizeBytes: Long,
    val hasAudio: Boolean,
    val aspectRatio: AspectRatioSpec?,
    val stopReason: StopReason = StopReason.USER,
    val createdAt: Long = System.currentTimeMillis(),
)

enum class CameraWarning {
    THERMAL_WARM,
    THERMAL_HOT,
    STOPPED_THERMAL,
    LOW_STORAGE,
    STOPPED_LOW_STORAGE,
    STOPPED_CAMERA_ERROR,
    STOPPED_ENCODER_ERROR,
    AUDIO_UNAVAILABLE,
    AUDIO_FORMAT_CHANGED,
}

sealed interface CameraEvent {
    data class RecordingFinished(val media: RecordedMedia) : CameraEvent

    /** Nothing playable could be saved. [reason] is why the take ended (e.g. the camera disconnected mid-take). */
    data class RecordingFailed(val kind: ErrorKind, val message: String? = null, val reason: StopReason? = null) : CameraEvent
    data class Warning(val warning: CameraWarning) : CameraEvent

    companion object {
        /**
         * Events for a take that ended for [reason]. The "stopped … your take was saved" warnings are only sent once
         * the take really was saved ([media] != null); otherwise a [RecordingFailed] carries the reason.
         */
        fun forStoppedTake(reason: StopReason, media: RecordedMedia?, detail: String? = null): List<CameraEvent> {
            if (media == null) return listOf(RecordingFailed(ErrorKind.UNKNOWN, detail ?: "nothing was recorded", reason))
            val warning = when (reason) {
                StopReason.THERMAL -> CameraWarning.STOPPED_THERMAL
                StopReason.LOW_STORAGE -> CameraWarning.STOPPED_LOW_STORAGE
                StopReason.CAMERA_ERROR -> CameraWarning.STOPPED_CAMERA_ERROR
                StopReason.ENCODER_ERROR -> CameraWarning.STOPPED_ENCODER_ERROR
                StopReason.USER, StopReason.LIFECYCLE -> null
            }
            return listOfNotNull(warning?.let(::Warning), RecordingFinished(media))
        }
    }
}

/**
 * Pro camera engine: Camera2 capture → GL pipeline (rotate, mirror, crop, [GlFrameProcessor]s) → preview + encoder.
 *
 * Threading: every command is non-blocking and safe from the main thread, except the preview-surface calls, which
 * wait briefly for the GL thread (required by the SurfaceHolder contract).
 */
interface CameraEngine {
    /** All cameras and lens chips; null until [detectCameras] ran. */
    val inventory: StateFlow<CameraInventory?>

    /** Capabilities of the camera currently selected. */
    val capabilities: StateFlow<CameraCapabilities?>
    val state: StateFlow<CameraState>

    /** Settings in effect after validation (null until started). */
    val settings: StateFlow<CameraSettings?>
    val previewFrame: StateFlow<PreviewFrame?>
    val controls: StateFlow<ManualControls>
    val focus: StateFlow<FocusState>
    val liveExposure: StateFlow<LiveExposure>
    val recording: StateFlow<RecordingStatus>
    val stats: StateFlow<PipelineStats>
    val thermal: StateFlow<ThermalLevel>
    val events: SharedFlow<CameraEvent>

    /** Queries cameras, lenses and encoder support (cached after the first call). */
    suspend fun detectCameras(forceRefresh: Boolean = false): CameraInventory

    /** Validates [settings] against the target camera's capabilities (falls back to the nearest supported). */
    fun validate(settings: CameraSettings): CameraSettings

    /** Opens the camera and starts the preview pipeline. Safe to call again with new settings. */
    fun start(settings: CameraSettings)

    /** Stops any recording (finalizing it), closes the camera and releases GPU resources. */
    fun stop()

    /** Applies new settings: reconfigures only what changed (request, session, or camera). */
    fun updateSettings(settings: CameraSettings)

    /** Retries after a [CameraState.Error]. */
    fun retry()

    fun setPreviewSurface(surface: Surface, width: Int, height: Int)
    fun clearPreviewSurface(surface: Surface)

    /** Physical device orientation (OrientationEventListener degrees); locked while recording. */
    fun setDeviceOrientation(degrees: Int)

    /** GPU stages applied to both preview and recording (e.g. the beauty processor). */
    fun setFrameProcessors(processors: List<GlFrameProcessor>)

    /**
     * Before/after for effects: when true the preview shows the frame before the processor chain while the encoder
     * keeps receiving the processed frame (bind it to `BeautyEngine.previewBypass`).
     */
    fun setPreviewBypass(bypass: Boolean)

    fun selectLens(option: LensOption)
    fun setZoom(ratio: Float)

    /** Tap-to-focus/meter at a normalized point of the displayed preview frame. */
    fun focusAndMeter(x: Float, y: Float)
    fun resetFocusAndMetering()
    fun setAeAfLock(locked: Boolean)
    fun setExposureCompensation(index: Int)
    fun setIso(iso: Int?)
    fun setShutter(exposureTimeNs: Long?)
    fun setFocusDistance(diopters: Float?)
    fun setWhiteBalance(mode: WhiteBalanceMode, kelvin: Int = controls.value.kelvin)
    fun resetManualControls()

    /** Seconds of recording that fit on storage for [settings]. */
    fun estimateRecordableSeconds(settings: CameraSettings, withAudio: Boolean): Long

    suspend fun startRecording(mode: CaptureMode, audioBitrateKbps: Int = 192): Outcome<Unit>
    fun pauseRecording()
    fun resumeRecording()

    /** Stops and finalizes. The result is also emitted as [CameraEvent.RecordingFinished]. */
    suspend fun stopRecording(reason: StopReason = StopReason.USER): RecordedMedia?
}
