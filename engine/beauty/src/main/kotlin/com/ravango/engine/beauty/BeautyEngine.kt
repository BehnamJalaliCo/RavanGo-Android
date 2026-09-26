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
    /** Features currently skipped because no face is tracked or quality was reduced. */
    val suspendedReason: String? = null,
)

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

    fun setState(state: BeautyState)

    /** Before/after: when true, preview shows the unprocessed image (recording is unaffected unless [affectsRecording]). */
    fun setCompareMode(showOriginal: Boolean, affectsRecording: Boolean = false)

    /** Hint that recording started/stopped: the engine becomes more conservative while recording to protect frame rate. */
    fun setRecording(recording: Boolean)
}
