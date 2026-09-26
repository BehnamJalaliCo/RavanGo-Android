package com.ravango.engine.audio

import com.ravango.core.model.AudioInputDevice
import com.ravango.core.model.AudioLevel
import com.ravango.core.model.AudioSettings
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * CONTRACT — audio capture subsystem.
 *
 * One capture session at a time owns the microphone. While metering only (preview), PCM is analysed and dropped.
 * While recording, processed PCM is delivered to the [PcmSink] provided by the video recorder (which encodes AAC
 * and muxes it), or written by [startAudioOnlyRecording] to an .m4a file.
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
}

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
