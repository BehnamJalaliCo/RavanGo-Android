package com.ravango.engine.audio

import com.ravango.core.model.AudioInputDevice
import com.ravango.core.model.AudioLevel
import com.ravango.core.model.AudioSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * CONTRACT — audio capture subsystem.
 *
 * One capture session at a time owns the microphone. While metering only (preview), PCM is analysed and dropped.
 * While recording, processed PCM is delivered to the [PcmSink] provided by the video recorder (which encodes AAC
 * and muxes it), or written by [startAudioOnlyRecording] to an .m4a file.
 *
 * Implementation notes (engine:audio):
 * - The microphone is open while preview is requested or at least one sink/recording is attached.
 * - [startPreview], [attachSink] and [startAudioOnlyRecording] throw [AudioPermissionException] (a
 *   [SecurityException]) when RECORD_AUDIO is missing; [startPreview] also throws it for BLUETOOTH_CONNECT
 *   (API 31+) when the preferred input is a Bluetooth microphone. Problems found later (device unplugged,
 *   Bluetooth link failed, permission revoked…) never stop an ongoing recording: capture falls back to the
 *   built-in microphone and the reason is published on [error].
 * - [PcmSink] callbacks run on the high-priority capture thread.
 */
interface AudioEngine {
    /** Currently available inputs (built-in, wired, USB, Bluetooth…), updated on hot-plug. */
    val inputs: StateFlow<List<AudioInputDevice>>

    /** The input actually in use (may differ from the preference if unavailable). */
    val activeInput: StateFlow<AudioInputDevice?>

    /** Live level, ~30 Hz. */
    val level: StateFlow<AudioLevel>

    /** True if headphones suitable for monitoring (wired/USB/low-latency) are connected. */
    val monitoringAvailable: StateFlow<Boolean>

    val settings: StateFlow<AudioSettings>

    /** Applies settings immediately (DSP changes are glitch-free; device changes restart capture). */
    fun updateSettings(settings: AudioSettings)

    /** Opens the mic for metering/monitoring in preview. Requires RECORD_AUDIO permission. */
    fun startPreview()
    fun stopPreview()

    /**
     * Starts delivering processed PCM to [sink]. The engine keeps running after [PcmSinkHandle.detach].
     * Timestamps are in the System.nanoTime() base (same as camera frames) for A/V sync.
     */
    fun attachSink(sink: PcmSink): PcmSinkHandle

    /** Records processed audio to an AAC .m4a file (audio-only mode and editor voice-over). */
    fun startAudioOnlyRecording(output: File): AudioRecordingHandle

    fun release()

    // ---- Additive members (with defaults so existing fakes keep compiling) ----

    /** Last non-fatal problem (null when none). The UI maps it with [AudioEngineError.messageRes]. */
    val error: StateFlow<AudioEngineError?> get() = NoAudioEngineError

    /** Clears [error] after the UI has shown it. */
    fun clearError() {}

    /**
     * Finalizes audio-only recordings interrupted by a crash/process death in [directory] (their crash-safe
     * AAC journal is remuxed into the target .m4a). Returns the recovered files. Safe to call at any time.
     */
    suspend fun recoverInterruptedRecordings(directory: File): List<File> = emptyList()
}

private val NoAudioEngineError: StateFlow<AudioEngineError?> = MutableStateFlow<AudioEngineError?>(null).asStateFlow()

/** PCM format delivered to sinks. */
data class PcmFormat(val sampleRate: Int, val channels: Int)

interface PcmSink {
    fun onFormat(format: PcmFormat)

    /** [samples] is interleaved 16-bit PCM; must be consumed synchronously (the buffer is reused). */
    fun onPcm(samples: ShortArray, length: Int, presentationTimeNs: Long)
}

interface PcmSinkHandle {
    fun detach()
}

interface AudioRecordingHandle {
    val file: File
    val durationUs: StateFlow<Long>
    fun pause()
    fun resume()

    /** Stops and finalizes the file. Returns the file on success. */
    suspend fun stop(): File?
}

/** Thrown when a runtime permission needed to open the requested input is missing; request [permission] and retry. */
class AudioPermissionException(val permission: String) : SecurityException("Missing permission: $permission")

/** Non-fatal problems reported on [AudioEngine.error]. */
sealed interface AudioEngineError {
    /** A permission is missing (e.g. BLUETOOTH_CONNECT for a Bluetooth mic); capture continues on the built-in mic. */
    data class PermissionRequired(val permission: String) : AudioEngineError

    /** The selected input disappeared (unplugged/disconnected); capture continues on [fallback]. */
    data class InputLost(val lost: AudioInputDevice, val fallback: AudioInputDevice?) : AudioEngineError

    /** The Bluetooth microphone could not be connected (SCO/LE audio link); capture uses the built-in mic. */
    data class BluetoothUnavailable(val device: AudioInputDevice) : AudioEngineError

    /** The microphone cannot be opened (in use by another app/call, or a hardware error). Capture keeps retrying. */
    data object MicrophoneUnavailable : AudioEngineError

    /** An audio-only recording could not be written (codec/storage failure). */
    data class RecordingFailed(val file: File, val reason: String?) : AudioEngineError
}
