package com.ravango.core.media.dsp

/**
 * CONTRACT (implemented by the audio engine work): real-time voice processing chain used both while recording
 * and by the editor's audio export. Processing is in place on interleaved 16-bit PCM.
 *
 * Chain order: high-pass (80 Hz) → spectral noise reduction → voice enhancement (presence EQ + compressor)
 * → digital gain → limiter.
 */
data class VoiceProcessingConfig(
    val sampleRate: Int,
    val channels: Int,
    val gainDb: Float = 0f,
    val highPass: Boolean = true,
    /** 0 = off, 1 = maximum. */
    val noiseReduction: Float = 0f,
    val voiceEnhance: Boolean = false,
    val limiter: Boolean = true,
)

class VoiceProcessor(config: VoiceProcessingConfig) {
    var config: VoiceProcessingConfig = config
        private set

    fun updateConfig(newConfig: VoiceProcessingConfig) {
        config = newConfig
    }

    /** Processes [length] interleaved samples starting at [offset], in place. */
    fun process(samples: ShortArray, offset: Int, length: Int) {
        TODO("Implemented by audio engine")
    }

    /** Float variant (samples in -1..1), in place. */
    fun process(samples: FloatArray, offset: Int, length: Int) {
        TODO("Implemented by audio engine")
    }

    fun reset() {}
}
