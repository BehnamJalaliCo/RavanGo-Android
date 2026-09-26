package com.ravango.engine.camera

import com.google.common.truth.Truth.assertThat
import com.ravango.core.model.CaptureMode
import org.junit.Test
import java.io.File

class StoppedTakeEventsTest {

    private fun media(reason: StopReason) = RecordedMedia(
        file = File("take.mp4"),
        captureMode = CaptureMode.VIDEO_WITH_AUDIO,
        mimeType = "video/mp4",
        durationUs = 9_000_000,
        width = 720,
        height = 1280,
        frameRate = 30,
        sizeBytes = 1_000,
        hasAudio = true,
        aspectRatio = null,
        stopReason = reason,
    )

    @Test
    fun `camera error mid take with a saved take - disconnect warning then the take for the saved-take sheet`() {
        val m = media(StopReason.CAMERA_ERROR)
        val events = CameraEvent.forStoppedTake(StopReason.CAMERA_ERROR, m)
        assertThat(events).containsExactly(CameraEvent.Warning(CameraWarning.STOPPED_CAMERA_ERROR), CameraEvent.RecordingFinished(m)).inOrder()
    }

    @Test
    fun `camera error before anything was recorded - no false saved claim, the failure carries the reason`() {
        val events = CameraEvent.forStoppedTake(StopReason.CAMERA_ERROR, null, "video samples in/written=0/0")
        assertThat(events).hasSize(1)
        val failed = events.single() as CameraEvent.RecordingFailed
        assertThat(failed.reason).isEqualTo(StopReason.CAMERA_ERROR)
        assertThat(failed.message).contains("0/0")
        assertThat(events.none { it is CameraEvent.Warning }).isTrue()
    }

    @Test
    fun `user stop has no warning`() {
        val m = media(StopReason.USER)
        assertThat(CameraEvent.forStoppedTake(StopReason.USER, m)).containsExactly(CameraEvent.RecordingFinished(m))
    }

    @Test
    fun `every automatic stop reason maps to its saved warning`() {
        val expected = mapOf(
            StopReason.THERMAL to CameraWarning.STOPPED_THERMAL,
            StopReason.LOW_STORAGE to CameraWarning.STOPPED_LOW_STORAGE,
            StopReason.CAMERA_ERROR to CameraWarning.STOPPED_CAMERA_ERROR,
            StopReason.ENCODER_ERROR to CameraWarning.STOPPED_ENCODER_ERROR,
        )
        for ((reason, warning) in expected) {
            assertThat(CameraEvent.forStoppedTake(reason, media(reason)).first()).isEqualTo(CameraEvent.Warning(warning))
        }
    }
}
