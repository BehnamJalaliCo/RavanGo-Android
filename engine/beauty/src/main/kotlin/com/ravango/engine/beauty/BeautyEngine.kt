package com.ravango.engine.beauty

import com.ravango.core.model.BeautyState
import com.ravango.engine.render.GlFrameProcessor
import kotlinx.coroutines.flow.StateFlow

/** Processing quality chosen adaptively from device tier, thermal state and measured frame time. */
enum class BeautyQuality { FULL, BALANCED, LIGHT, MINIMAL }

data class BeautyStatus(
    val faceDetected: Boolean = false,
    val faceCount: Int = 0,
    val quality: BeautyQuality = BeautyQuality.FULL,
    /** Average GPU+CPU cost of the beauty pass per frame, in milliseconds. */
    val frameCostMs: Float = 0f,
    /** Features currently skipped because no face is tracked or quality was reduced. One of [BeautySuspendReason]. */
    val suspendedReason: String? = null,
    /** True while face tracking is running (it is paused when every value is neutral or quality is MINIMAL). */
    val tracking: Boolean = false,
)

/** Stable codes used in [BeautyStatus.suspendedReason]; the UI maps them to localized text. */
object BeautySuspendReason {
    /** Face-dependent features are active but no face is currently tracked. */
    const val NO_FACE = "no_face"
    /** Quality was reduced to protect the frame rate; heavy passes are skipped. */
    const val REDUCED_QUALITY = "reduced_quality"
    /** Quality is capped by device temperature. */
    const val THERMAL = "thermal"
    /** Face detection could not run on this device (model unavailable). */
    const val TRACKING_UNAVAILABLE = "tracking_unavailable"
    /** A GPU program failed to build; beauty is bypassed so recording is never affected. */
    const val GPU_ERROR = "gpu_error"
}

/**
 * CONTRACT — real-time beauty & makeup.
 *
 * The camera engine attaches [processor] to its GL pipeline, so the exact same output is shown in preview and
 * written to the recording. Face landmarks are tracked asynchronously; landmark-dependent features fade out
 * smoothly when the face is lost, never glitch.
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

    fun setState(state: BeautyState)

    /** Before/after: when true, preview shows the unprocessed image (recording is unaffected unless [affectsRecording]). */
    fun setCompareMode(showOriginal: Boolean, affectsRecording: Boolean = false)

    /** Hint that recording started/stopped: the engine becomes more conservative while recording to protect frame rate. */
    fun setRecording(recording: Boolean)
}
