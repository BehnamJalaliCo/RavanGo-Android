package com.ravango.engine.beauty

import com.ravango.core.model.BeautyState
import com.ravango.engine.beauty.makeup.MakeupStyle
import com.ravango.engine.render.GlFrameProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Processing quality chosen adaptively from device tier, thermal state and measured frame time. */
enum class BeautyQuality { FULL, BALANCED, LIGHT, MINIMAL }

/** ADDED — where face tracking runs (MediaPipe delegate), for diagnostics. */
enum class BeautyTrackingDelegate { GPU, CPU }

data class BeautyStatus(
    val faceDetected: Boolean = false,
    /** Number of faces currently tracked and styled (0, 1 or 2). */
    val faceCount: Int = 0,
    val quality: BeautyQuality = BeautyQuality.FULL,
    /** Average GPU+CPU cost of the beauty pass per frame, in milliseconds. */
    val frameCostMs: Float = 0f,
    /** Features currently skipped because no face is tracked or quality was reduced. One of [BeautySuspendReason]. */
    val suspendedReason: String? = null,
    /** True while face tracking is running (it is paused when every value is neutral or quality is MINIMAL). */
    val tracking: Boolean = false,
    /** ADDED — delegate the face landmarker runs on while [tracking]; null while starting or not tracking. */
    val trackingDelegate: BeautyTrackingDelegate? = null,
)

/** Stable codes used in [BeautyStatus.suspendedReason]; the UI maps them to localized text. */
object BeautySuspendReason {
    /** Face-dependent features are active but no face is currently tracked. */
    const val NO_FACE = "no_face"
    /** Quality was reduced to protect the frame rate; heavy passes are skipped. */
    const val REDUCED_QUALITY = "reduced_quality"
    /** Quality is capped by device temperature. */
    const val THERMAL = "thermal"
    /** Face tracking could not run on this device (model or native library unavailable). */
    const val TRACKING_UNAVAILABLE = "tracking_unavailable"
    /** A GPU program failed to build; beauty is bypassed so recording is never affected. */
    const val GPU_ERROR = "gpu_error"
}

/**
 * ADDED — iris recolour ("Eye Color"). Kept next to [BeautyState] rather than inside it because `core:model` is
 * shared and read-only for this module; it is persisted by the engine and is not part of saved presets.
 */
data class EyeColorSetting(
    /** 0..100; 0 = off. */
    val intensity: Int = 0,
    /** ARGB. */
    val color: Long = DEFAULT_COLOR,
) {
    val active: Boolean get() = intensity > 0

    companion object {
        const val DEFAULT_COLOR: Long = 0xFF6D8BA8
    }
}

/**
 * CONTRACT — real-time beauty & makeup.
 *
 * The camera engine attaches [processor] to its GL pipeline, so the exact same output is shown in preview and
 * written to the recording. Faces are tracked asynchronously as a dense 3D mesh (MediaPipe Face Landmarker,
 * 478 points, up to two faces); landmark-dependent features fade in/out smoothly, never glitch.
 *
 * How settings map onto the Snapchat-style tool set:
 * - Face Retouch — Soft Skin = SMOOTH_SKIN (+ SKIN_RETOUCH, BLEMISH_REMOVAL), Teeth Whitening = TEETH_WHITENING,
 *   **Eye Whitening rides on WHITENING** (the sclera is brightened/desaturated with the iris excluded) and
 *   **Eye Sharpening rides on SHARPEN** (extra sharpening inside the eye openings), dark circles = DARK_CIRCLES.
 * - Face Liquify / Stretch — FACE_SLIM, JAW, CHIN, CHEEKBONE, FOREHEAD, NOSE, EYE_SIZE, EYE_SHAPE (mesh warp).
 * - Face Mask makeup — every [com.ravango.core.model.MakeupFeature] layer, rendered in the face-mesh UV space.
 * - Eye Color — [setEyeColor].
 */
interface BeautyEngine {
    val processor: GlFrameProcessor
    val status: StateFlow<BeautyStatus>
    val state: StateFlow<BeautyState>

    /**
     * ADDED — before/after for preview only. When true the camera should show the processor's *input* frame on the
     * preview surface while still sending the processed frame to the encoder. (When compare mode is set with
     * `affectsRecording = true`, the processor itself bypasses and this stays false.)
     */
    val previewBypass: StateFlow<Boolean>

    /** ADDED — current iris recolour. */
    val eyeColor: StateFlow<EyeColorSetting> get() = NoEyeColor

    fun setState(state: BeautyState)

    /** ADDED — sets the iris recolour (applied while [BeautyState.enabled]). */
    fun setEyeColor(setting: EyeColorSetting) {}

    /** ADDED — current makeup look style (liner / lash / lip / brow variants; not part of saved presets). */
    val makeupStyle: StateFlow<MakeupStyle> get() = DefaultMakeupStyle

    /** ADDED — sets the makeup look style (uniforms only: switching is instant and never re-bakes textures). */
    fun setMakeupStyle(style: MakeupStyle) {}

    /** Before/after: when true, preview shows the unprocessed image (recording is unaffected unless [affectsRecording]). */
    fun setCompareMode(showOriginal: Boolean, affectsRecording: Boolean = false)

    /** Hint that recording started/stopped: the engine becomes more conservative while recording to protect frame rate. */
    fun setRecording(recording: Boolean)
}

private val NoEyeColor: StateFlow<EyeColorSetting> = MutableStateFlow(EyeColorSetting()).asStateFlow()
private val DefaultMakeupStyle: StateFlow<MakeupStyle> = MutableStateFlow(MakeupStyle.Default).asStateFlow()
