package com.ravango.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class AudioInputType { BUILT_IN, WIRED, USB, BLUETOOTH, BLUETOOTH_LE, HDMI, OTHER }

/** An input the user can pick. [id] is the platform AudioDeviceInfo id at runtime (not stable across reboots). */
@Serializable
data class AudioInputDevice(
    val id: Int,
    val name: String,
    val type: AudioInputType,
    val channelCounts: List<Int> = emptyList(),
    val sampleRates: List<Int> = emptyList(),
)

@Serializable
data class AudioSettings(
    /** Preferred input type; resolved to a concrete device at runtime. */
    val preferredInput: AudioInputType = AudioInputType.BUILT_IN,
    val preferredDeviceName: String? = null,
    val sampleRate: Int = 48_000,
    val stereo: Boolean = false,
    /** Digital gain applied before encoding, in dB (-12 … +24). */
    val gainDb: Float = 0f,
    val noiseReduction: Boolean = true,
    /** 0..1 strength of the spectral noise reduction. */
    val noiseReductionStrength: Float = 0.5f,
    val voiceEnhancement: Boolean = false,
    val highPassFilter: Boolean = true,
    val limiter: Boolean = true,
    /** Route the live input to headphones (only enabled when wired/USB headphones are connected). */
    val monitoring: Boolean = false,
    val useUnprocessedSource: Boolean = false,
    val audioBitrateKbps: Int = 192,
)

/** Live metering snapshot, produced ~30 times per second by the audio engine. */
data class AudioLevel(
    val peakDbfs: Float,
    val rmsDbfs: Float,
    val clipping: Boolean,
) {
    companion object {
        val Silent = AudioLevel(-90f, -90f, false)
    }
}
