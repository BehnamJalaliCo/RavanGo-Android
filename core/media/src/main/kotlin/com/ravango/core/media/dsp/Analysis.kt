package com.ravango.core.media.dsp

import com.ravango.core.media.PcmDecoder
import com.ravango.core.model.AudioLevel

/** CONTRACT: level metering for a buffer of interleaved 16-bit samples. */
object LevelMeter {
    fun measure(samples: ShortArray, offset: Int, length: Int): AudioLevel = TODO("Implemented by audio engine")
}

data class TimeRange(val startUs: Long, val endUs: Long) {
    val durationUs: Long get() = endUs - startUs
}

data class SilenceOptions(
    /** Frames quieter than (noise floor + this) are silent. */
    val thresholdAboveFloorDb: Float = 8f,
    /** Absolute ceiling for "silent", regardless of the floor. */
    val maxThresholdDbfs: Float = -35f,
    val minSilenceMs: Long = 450,
    /** Kept around speech so cuts don't clip words. */
    val paddingMs: Long = 120,
)

/** CONTRACT: silence detection on a file's audio track. Returns silent ranges in source time. */
class SilenceDetector(private val decoder: PcmDecoder) {
    suspend fun detect(uri: String, options: SilenceOptions = SilenceOptions(), startUs: Long = 0, endUs: Long = Long.MAX_VALUE): List<TimeRange> =
        TODO("Implemented by audio engine")

    /** RMS loudness envelope in dBFS, one value per [windowMs]. Used for highlight detection. */
    suspend fun loudnessEnvelope(uri: String, windowMs: Int = 100): FloatArray = TODO("Implemented by audio engine")
}
