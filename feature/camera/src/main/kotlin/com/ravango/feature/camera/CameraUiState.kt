package com.ravango.feature.camera

import com.ravango.core.common.device.ThermalLevel
import com.ravango.core.common.result.ErrorKind
import com.ravango.core.model.AudioInputDevice
import com.ravango.core.model.AudioSettings
import com.ravango.core.model.CameraSettings
import com.ravango.core.model.CaptureMode
import com.ravango.core.model.Entitlements
import com.ravango.core.model.LensFacing
import com.ravango.core.model.ManualControls
import com.ravango.core.model.Script
import com.ravango.core.model.TeleprompterSettings
import com.ravango.engine.beauty.effects.EffectsState
import com.ravango.engine.beauty.effects.EffectsStatus
import com.ravango.engine.camera.CameraState
import com.ravango.engine.camera.CameraWarning
import com.ravango.engine.camera.FocusState
import com.ravango.engine.camera.LiveExposure
import com.ravango.engine.camera.PipelineStats
import com.ravango.engine.camera.PreviewFrame
import com.ravango.engine.camera.RecordingStatus
import com.ravango.engine.camera.StopReason
import com.ravango.engine.camera.capability.CameraCapabilities
import com.ravango.engine.camera.capability.LensOption

/** Top-level studio mode: the bottom mode switch. */
enum class StudioMode { VIDEO, AUDIO }

/** Bottom sheets / panels that can be open (one at a time). */
enum class StudioSheet { NONE, RESOLUTION, ASPECT, SETTINGS, AUDIO, BEAUTY, EFFECTS }

sealed interface PostRecordState {
    data object Saving : PostRecordState
    data class Saved(val take: SavedTake) : PostRecordState
    data class Failed(val kind: ErrorKind) : PostRecordState
}

/** One-shot user messages (shown as a snackbar). */
sealed interface StudioMessage {
    data class Warning(val warning: CameraWarning) : StudioMessage
    data class Error(val kind: ErrorKind) : StudioMessage
    data object MicUnavailableVideoOnly : StudioMessage
    data class Recovered(val count: Int) : StudioMessage
    data object NothingRecorded : StudioMessage

    /** The take ended because of the camera/encoder ([reason]) before anything playable was recorded. */
    data class TakeLost(val reason: StopReason) : StudioMessage
    data object BackgroundPhotoFailed : StudioMessage
}

data class PrompterUi(
    val script: Script,
    val settings: TeleprompterSettings,
    val visible: Boolean,
)

data class CameraUiState(
    val initialized: Boolean = false,
    val mode: StudioMode = StudioMode.VIDEO,
    /** Settings the user asked for (after entitlement + capability validation). */
    val settings: CameraSettings = CameraSettings(),
    val cameraState: CameraState = CameraState.Closed,
    val capabilities: CameraCapabilities? = null,
    val lenses: List<LensOption> = emptyList(),
    val facings: List<LensFacing> = emptyList(),
    val noCamera: Boolean = false,
    val controls: ManualControls = ManualControls(),
    val focus: FocusState = FocusState(),
    val live: LiveExposure = LiveExposure(),
    val previewFrame: PreviewFrame? = null,
    val recording: RecordingStatus = RecordingStatus(),
    val stats: PipelineStats = PipelineStats(),
    val thermal: ThermalLevel = ThermalLevel.NORMAL,
    val entitlements: Entitlements = Entitlements(),
    val audioSettings: AudioSettings = AudioSettings(),
    val audioInputs: List<AudioInputDevice> = emptyList(),
    val activeInput: AudioInputDevice? = null,
    val monitoringAvailable: Boolean = false,
    val prompter: PrompterUi? = null,
    val countdown: Int? = null,
    /** Recording time that fits on storage with the current settings (seconds). */
    val storageEstimateSeconds: Long? = null,
    val lastProjectId: String? = null,
    val lastThumbnailPath: String? = null,
    val postRecord: PostRecordState? = null,
    val sheet: StudioSheet = StudioSheet.NONE,
    val proControlsOpen: Boolean = false,
    val comparing: Boolean = false,
    /** Lenses, live filter and background effect currently applied. */
    val effects: EffectsState = EffectsState(),
    val effectsStatus: EffectsStatus = EffectsStatus(),
    /** The Snapchat-style lens carousel around the shutter is open. */
    val lensTrayOpen: Boolean = false,
    val hasBackgroundImage: Boolean = false,
    /** Number of faces tracked right now (0 while no face effect needs tracking). */
    val facesTracked: Int = 0,
) {
    val isRecording: Boolean get() = recording.isActive
    val videoCaptureMode: CaptureMode get() = if (settings.captureMode == CaptureMode.AUDIO_ONLY) CaptureMode.VIDEO_WITH_AUDIO else settings.captureMode
    val needsMic: Boolean get() = mode == StudioMode.AUDIO || settings.captureMode == CaptureMode.VIDEO_WITH_AUDIO
    val isStreaming: Boolean get() = cameraState is CameraState.Streaming
}
