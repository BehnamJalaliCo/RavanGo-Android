package com.ravango.engine.camera.session

import com.ravango.engine.camera.CameraErrorKind

/**
 * What the engine does after a camera error. Pure (the clock is passed in) so the state machine is unit tested.
 *
 * - Permanent problems (permission, policy-disabled, no camera) give up at once.
 * - Everything else reopens with exponential backoff. Streaming again does NOT reset the budget: a camera that
 *   streams for a few seconds and then fails again (a flaky/emulated HAL) is still failing. Only a stable run of
 *   [stableAfterMs] counts as recovered.
 * - Repeated device/configuration failures escalate once to a minimal request (template defaults + fps range), then
 *   once to a lighter stream (720p30), and finally give up so the UI can show a clear "tap to retry" state.
 */
internal class SessionRecoveryPolicy(
    private val maxFailures: Int = 6,
    private val stableAfterMs: Long = 60_000,
    private val minimalAfterFailures: Int = 2,
    private val reducedAfterFailures: Int = 4,
) {
    enum class Action { RETRY, RETRY_MINIMAL_REQUEST, RETRY_REDUCED_STREAM, GIVE_UP }

    data class Decision(val action: Action, val delayMs: Long) {
        val retrying: Boolean get() = action != Action.GIVE_UP
    }

    /** The repeating request is reduced to template defaults + fps range. */
    var minimalRequest = false
        private set

    /** The stream/recording size is capped to 720p30. */
    var reducedStream = false
        private set

    /** Failures since the last stable run (device/configuration errors and busy camera alike). */
    var failures = 0
        private set

    private var hardFailures = 0
    private var streamingSinceMs = -1L

    fun onStreaming(nowMs: Long) {
        streamingSinceMs = nowMs
    }

    /** The user asked to retry, or the camera was (re)started: fresh budget and full request again. */
    fun reset() {
        failures = 0
        hardFailures = 0
        minimalRequest = false
        reducedStream = false
        streamingSinceMs = -1
    }

    fun onError(kind: CameraErrorKind, recoverable: Boolean, nowMs: Long): Decision {
        val permanent = kind == CameraErrorKind.PERMISSION || kind == CameraErrorKind.DISABLED || kind == CameraErrorKind.NO_CAMERA ||
            (!recoverable && kind != CameraErrorKind.CONFIGURATION)
        if (permanent) return Decision(Action.GIVE_UP, 0)
        val since = streamingSinceMs
        streamingSinceMs = -1
        if (since >= 0 && nowMs - since >= stableAfterMs) {
            // It streamed fine for a long time: an isolated failure, not a configuration the camera cannot run.
            failures = 0
            hardFailures = 0
        }
        failures++
        val hard = kind == CameraErrorKind.DEVICE || kind == CameraErrorKind.CONFIGURATION || kind == CameraErrorKind.SERVICE ||
            kind == CameraErrorKind.UNKNOWN
        if (hard) hardFailures++
        if (failures > maxFailures) return Decision(Action.GIVE_UP, 0)
        if (hard && !minimalRequest && (hardFailures >= minimalAfterFailures || kind == CameraErrorKind.CONFIGURATION)) {
            minimalRequest = true
            return Decision(Action.RETRY_MINIMAL_REQUEST, SHORT_DELAY_MS)
        }
        if (hard && minimalRequest && !reducedStream && (hardFailures >= reducedAfterFailures || kind == CameraErrorKind.CONFIGURATION)) {
            reducedStream = true
            return Decision(Action.RETRY_REDUCED_STREAM, SHORT_DELAY_MS)
        }
        return Decision(Action.RETRY, backoffMs(failures))
    }

    companion object {
        const val SHORT_DELAY_MS = 500L
        const val MAX_DELAY_MS = 8_000L

        /** 1 s, 2 s, 4 s, 8 s, 8 s… */
        fun backoffMs(failure: Int): Long = minOf(MAX_DELAY_MS, 500L shl failure.coerceIn(1, 5))
    }
}
