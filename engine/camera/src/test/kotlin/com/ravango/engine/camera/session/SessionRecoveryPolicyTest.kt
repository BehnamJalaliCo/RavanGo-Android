package com.ravango.engine.camera.session

import com.google.common.truth.Truth.assertThat
import com.ravango.engine.camera.CameraErrorKind
import com.ravango.engine.camera.session.SessionRecoveryPolicy.Action
import org.junit.Test

class SessionRecoveryPolicyTest {

    @Test
    fun `permanent problems give up immediately`() {
        val p = SessionRecoveryPolicy()
        assertThat(p.onError(CameraErrorKind.PERMISSION, recoverable = false, nowMs = 0).action).isEqualTo(Action.GIVE_UP)
        assertThat(p.onError(CameraErrorKind.DISABLED, recoverable = false, nowMs = 0).action).isEqualTo(Action.GIVE_UP)
        assertThat(p.onError(CameraErrorKind.NO_CAMERA, recoverable = false, nowMs = 0).action).isEqualTo(Action.GIVE_UP)
    }

    @Test
    fun `a flapping device escalates to minimal request, then reduced stream, then gives up`() {
        // The emulator pattern: streams for ~20 s, then ERROR_CAMERA_DEVICE, again and again.
        val p = SessionRecoveryPolicy()
        var now = 0L
        val actions = ArrayList<Action>()
        repeat(8) {
            p.onStreaming(now)
            now += 20_000
            actions += p.onError(CameraErrorKind.DEVICE, recoverable = true, nowMs = now).action
            now += 1_000
        }
        assertThat(actions).containsExactly(
            Action.RETRY,
            Action.RETRY_MINIMAL_REQUEST,
            Action.RETRY,
            Action.RETRY_REDUCED_STREAM,
            Action.RETRY,
            Action.RETRY,
            Action.GIVE_UP,
            Action.GIVE_UP,
        ).inOrder()
        assertThat(p.minimalRequest).isTrue()
        assertThat(p.reducedStream).isTrue()
    }

    @Test
    fun `a long stable run resets the budget`() {
        val p = SessionRecoveryPolicy()
        p.onStreaming(0)
        assertThat(p.onError(CameraErrorKind.DEVICE, true, 5_000).action).isEqualTo(Action.RETRY)
        p.onStreaming(6_000)
        // Two minutes of clean streaming: the next failure is treated as isolated, no escalation.
        assertThat(p.onError(CameraErrorKind.DEVICE, true, 126_000).action).isEqualTo(Action.RETRY)
        assertThat(p.minimalRequest).isFalse()
        assertThat(p.failures).isEqualTo(1)
    }

    @Test
    fun `configuration failure goes to the minimal request at once, then to a lighter stream`() {
        val p = SessionRecoveryPolicy()
        assertThat(p.onError(CameraErrorKind.CONFIGURATION, recoverable = false, nowMs = 0).action).isEqualTo(Action.RETRY_MINIMAL_REQUEST)
        assertThat(p.onError(CameraErrorKind.CONFIGURATION, recoverable = false, nowMs = 1_000).action).isEqualTo(Action.RETRY_REDUCED_STREAM)
        assertThat(p.onError(CameraErrorKind.CONFIGURATION, recoverable = false, nowMs = 2_000).action).isEqualTo(Action.RETRY)
    }

    @Test
    fun `busy camera retries with backoff but never degrades the request`() {
        val p = SessionRecoveryPolicy()
        val decisions = (1..7).map { p.onError(CameraErrorKind.IN_USE, recoverable = true, nowMs = it * 1_000L) }
        assertThat(decisions.take(6).map { it.action }.distinct()).containsExactly(Action.RETRY)
        assertThat(decisions.take(6).map { it.delayMs }).containsExactly(1_000L, 2_000L, 4_000L, 8_000L, 8_000L, 8_000L).inOrder()
        assertThat(decisions.last().action).isEqualTo(Action.GIVE_UP)
        assertThat(p.minimalRequest).isFalse()
    }

    @Test
    fun `reset restores the full request and budget`() {
        val p = SessionRecoveryPolicy()
        p.onError(CameraErrorKind.CONFIGURATION, false, 0)
        p.onError(CameraErrorKind.CONFIGURATION, false, 0)
        p.reset()
        assertThat(p.minimalRequest).isFalse()
        assertThat(p.reducedStream).isFalse()
        assertThat(p.failures).isEqualTo(0)
        assertThat(p.onError(CameraErrorKind.DEVICE, true, 0).action).isEqualTo(Action.RETRY)
    }
}
