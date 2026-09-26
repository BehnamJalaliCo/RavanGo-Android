package com.ravango.core.media.dsp

import com.ravango.core.media.PcmDecoder
import com.ravango.core.model.AudioLevel
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** CONTRACT: level metering for a buffer of interleaved 16-bit samples. */
object LevelMeter {
    /** Lowest reported level. */
    const val FLOOR_DBFS = -90f

    /** 16-bit samples at or above this magnitude count as clipping. */
    const val CLIP_THRESHOLD_16 = 32700

    /** Float samples at or above this magnitude count as clipping. */
    const val CLIP_THRESHOLD_FLOAT = 0.998f

    /**
     * Peak and RMS (over all samples, all channels) in dBFS relative to 32768, floored at [FLOOR_DBFS].
     * A full-scale sine reads ≈ 0 dBFS peak / −3 dBFS RMS.
     */
    fun measure(samples: ShortArray, offset: Int, length: Int): AudioLevel {
        if (length <= 0) return AudioLevel.Silent
        var peak = 0
        var sum = 0.0
        for (i in offset until offset + length) {
            val s = samples[i].toInt()
            val a = if (s < 0) -s else s
            if (a > peak) peak = a
            sum += (s * s).toDouble()
        }
        return AudioLevel(
            peakDbfs = toDbfs(peak / 32768.0),
            rmsDbfs = toDbfs(sqrt(sum / length) / 32768.0),
            clipping = peak >= CLIP_THRESHOLD_16,
        )
    }

    /** Float variant (samples in −1..1). */
    fun measure(samples: FloatArray, offset: Int, length: Int): AudioLevel {
        if (length <= 0) return AudioLevel.Silent
        var peak = 0f
        var sum = 0.0
        for (i in offset until offset + length) {
            val s = samples[i]
            val a = abs(s)
            if (a > peak) peak = a
            sum += (s * s).toDouble()
        }
        return AudioLevel(toDbfs(peak.toDouble()), toDbfs(sqrt(sum / length)), peak >= CLIP_THRESHOLD_FLOAT)
    }

    /** Linear amplitude (1.0 = full scale) to dBFS, floored at [FLOOR_DBFS]. */
    fun toDbfs(linear: Double): Float =
        if (linear <= 0.0) FLOOR_DBFS else max(FLOOR_DBFS, (20.0 * log10(linear)).toFloat())
}

/**
 * Allocation-free running level measurement (for real-time metering): feed buffers with [add], read the peak/RMS
 * of everything since the last [reset].
 */
class LevelAccumulator {
    var peak: Int = 0
        private set
    private var sumSquares = 0.0
    var count: Int = 0
        private set
    var clipped: Boolean = false
        private set

    fun add(samples: ShortArray, offset: Int, length: Int) {
        var p = peak
        var sum = 0.0
        for (i in offset until offset + length) {
            val s = samples[i].toInt()
            val a = if (s < 0) -s else s
            if (a > p) p = a
            sum += (s * s).toDouble()
        }
        peak = p
        sumSquares += sum
        count += length
        if (p >= LevelMeter.CLIP_THRESHOLD_16) clipped = true
    }

    val peakDbfs: Float get() = LevelMeter.toDbfs(peak / 32768.0)
    val rmsDbfs: Float get() = if (count == 0) LevelMeter.FLOOR_DBFS else LevelMeter.toDbfs(sqrt(sumSquares / count) / 32768.0)

    fun reset() {
        peak = 0; sumSquares = 0.0; count = 0; clipped = false
    }
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

/**
 * CONTRACT: silence detection on a file's audio track. Returns silent ranges in source time.
 *
 * Algorithm: decode to mono (via [PcmDecoder]), 20 ms RMS frames, noise floor = 10th percentile of frame levels,
 * threshold = min(floor + [SilenceOptions.thresholdAboveFloorDb], [SilenceOptions.maxThresholdDbfs]) with 3 dB
 * hysteresis, ranges separated by ≤ 60 ms merged, ranges shorter than [SilenceOptions.minSilenceMs] dropped, then
 * each range shrunk by [SilenceOptions.paddingMs] at both ends (ranges that would become empty are dropped).
 * Results are clamped to [startUs, endUs). Files without an audio track yield an empty list.
 */
class SilenceDetector @Inject constructor(private val decoder: PcmDecoder) {
    suspend fun detect(uri: String, options: SilenceOptions = SilenceOptions(), startUs: Long = 0, endUs: Long = Long.MAX_VALUE): List<TimeRange> {
        val frames = FrameLevels(SilenceAnalysis.FRAME_MS)
        val sampleRate = decoder.decode(uri, startUs, endUs) { block -> frames.add(block.samples, block.count, block.sampleRate, block.startUs, startUs, endUs) }
        if (sampleRate <= 0) return emptyList()
        frames.finish()
        return SilenceAnalysis.findSilences(frames, options, startUs, endUs)
    }

    /** RMS loudness envelope in dBFS, one value per [windowMs]. Used for highlight detection. */
    suspend fun loudnessEnvelope(uri: String, windowMs: Int = 100): FloatArray {
        val envelope = LoudnessEnvelopeBuilder(windowMs)
        val sampleRate = decoder.decode(uri) { block -> envelope.add(block.samples, block.count, block.sampleRate, block.startUs) }
        if (sampleRate <= 0) return FloatArray(0)
        return envelope.build()
    }
}

/**
 * Accumulates mono float PCM (with source timestamps) into fixed-length RMS frames. Pure JVM logic, testable
 * without a decoder. Frames never span a timestamp discontinuity larger than one frame.
 */
class FrameLevels(private val frameMs: Int) {
    private var db = FloatArray(1024)
    private var starts = LongArray(1024)
    private var ends = LongArray(1024)
    var size: Int = 0
        private set

    private var frameSamples = 0
    private var target = 0
    private var sum = 0.0
    private var frameStart = 0L
    private var lastSampleEnd = Long.MIN_VALUE

    fun dbAt(i: Int): Float = db[i]
    fun startAt(i: Int): Long = starts[i]
    fun endAt(i: Int): Long = ends[i]

    /**
     * Adds [count] samples starting at [blockStartUs]. Samples outside [rangeStartUs, rangeEndUs) are ignored.
     */
    fun add(samples: FloatArray, count: Int, sampleRate: Int, blockStartUs: Long, rangeStartUs: Long = 0, rangeEndUs: Long = Long.MAX_VALUE) {
        if (sampleRate <= 0) return
        target = max(1, sampleRate * frameMs / 1000)
        val usPerSample = 1_000_000.0 / sampleRate
        for (i in 0 until count) {
            val t = blockStartUs + (i * usPerSample).toLong()
            if (t < rangeStartUs) continue
            if (t >= rangeEndUs) break
            if (frameSamples > 0 && abs(t - lastSampleEnd) > frameMs * 1000L) closeFrame() // discontinuity
            if (frameSamples == 0) frameStart = t
            val s = samples[i]
            sum += (s * s).toDouble()
            frameSamples++
            lastSampleEnd = t + usPerSample.toLong().coerceAtLeast(1)
            if (frameSamples >= target) closeFrame()
        }
    }

    /** Closes the trailing partial frame. */
    fun finish() { if (frameSamples > 0) closeFrame() }

    private fun closeFrame() {
        if (size == db.size) {
            db = db.copyOf(size * 2); starts = starts.copyOf(size * 2); ends = ends.copyOf(size * 2)
        }
        db[size] = LevelMeter.toDbfs(sqrt(sum / frameSamples))
        starts[size] = frameStart
        ends[size] = lastSampleEnd
        size++
        frameSamples = 0
        sum = 0.0
    }

    companion object {
        /** Builds frames from explicit levels (for tests / precomputed analyses); frame i spans [i·frameMs, (i+1)·frameMs). */
        fun of(frameMs: Int, levelsDb: FloatArray, startUs: Long = 0): FrameLevels = FrameLevels(frameMs).apply {
            for (level in levelsDb) {
                if (size == db.size) { db = db.copyOf(size * 2); starts = starts.copyOf(size * 2); ends = ends.copyOf(size * 2) }
                db[size] = level
                starts[size] = startUs + size * frameMs * 1000L
                ends[size] = starts[size] + frameMs * 1000L
                size++
            }
        }
    }
}

/** Pure silence analysis over [FrameLevels]. */
object SilenceAnalysis {
    const val FRAME_MS = 20
    const val FLOOR_PERCENTILE = 0.10
    const val HYSTERESIS_DB = 3f
    const val MERGE_GAP_MS = 60L

    fun noiseFloorDb(frames: FrameLevels): Float {
        if (frames.size == 0) return LevelMeter.FLOOR_DBFS
        val sorted = FloatArray(frames.size) { frames.dbAt(it) }
        sorted.sort()
        return sorted[((sorted.size - 1) * FLOOR_PERCENTILE).toInt()]
    }

    fun thresholdDb(floorDb: Float, options: SilenceOptions): Float =
        min(floorDb + options.thresholdAboveFloorDb, options.maxThresholdDbfs)

    fun findSilences(frames: FrameLevels, options: SilenceOptions, rangeStartUs: Long = 0, rangeEndUs: Long = Long.MAX_VALUE): List<TimeRange> {
        if (frames.size == 0) return emptyList()
        val threshold = thresholdDb(noiseFloorDb(frames), options)
        val release = threshold + HYSTERESIS_DB

        // 1. Raw silent runs with hysteresis.
        val raw = ArrayList<TimeRange>()
        var silent = false
        var runStart = 0L
        var runEnd = 0L
        for (i in 0 until frames.size) {
            val level = frames.dbAt(i)
            if (silent) {
                val gap = frames.startAt(i) - runEnd > FRAME_MS * 1000L
                if (level > release || gap) {
                    raw.add(TimeRange(runStart, runEnd)); silent = false
                } else {
                    runEnd = frames.endAt(i)
                }
            }
            if (!silent && level < threshold) {
                silent = true; runStart = frames.startAt(i); runEnd = frames.endAt(i)
            }
        }
        if (silent) raw.add(TimeRange(runStart, runEnd))

        // 2. Merge runs separated by a short blip (click, breath).
        val merged = ArrayList<TimeRange>(raw.size)
        for (r in raw) {
            val last = merged.lastOrNull()
            if (last != null && r.startUs - last.endUs <= MERGE_GAP_MS * 1000L) {
                merged[merged.size - 1] = TimeRange(last.startUs, max(last.endUs, r.endUs))
            } else {
                merged.add(r)
            }
        }

        // 3. Minimum length, 4. padding, 5. clamp to the requested range.
        val minUs = options.minSilenceMs * 1000L
        val padUs = options.paddingMs.coerceAtLeast(0) * 1000L
        val out = ArrayList<TimeRange>(merged.size)
        for (r in merged) {
            if (r.durationUs < minUs) continue
            val s = max(r.startUs + padUs, rangeStartUs)
            val e = min(r.endUs - padUs, rangeEndUs)
            if (e > s) out.add(TimeRange(s, e))
        }
        return out
    }
}

/** Builds an RMS dBFS envelope (one value per window, window i covering [i·w, (i+1)·w) in source time). */
class LoudnessEnvelopeBuilder(windowMs: Int) {
    private val windowUs = windowMs.coerceAtLeast(1) * 1000L
    private var sums = DoubleArray(256)
    private var counts = IntArray(256)
    private var maxIndex = -1

    fun add(samples: FloatArray, count: Int, sampleRate: Int, blockStartUs: Long) {
        if (sampleRate <= 0) return
        val usPerSample = 1_000_000.0 / sampleRate
        for (i in 0 until count) {
            val t = blockStartUs + (i * usPerSample).toLong()
            if (t < 0) continue
            val idx = (t / windowUs).toInt()
            if (idx >= sums.size) {
                var n = sums.size
                while (n <= idx) n *= 2
                sums = sums.copyOf(n); counts = counts.copyOf(n)
            }
            val s = samples[i]
            sums[idx] += (s * s).toDouble()
            counts[idx]++
            if (idx > maxIndex) maxIndex = idx
        }
    }

    fun build(): FloatArray = FloatArray(maxIndex + 1) { i ->
        if (counts[i] == 0) LevelMeter.FLOOR_DBFS else LevelMeter.toDbfs(sqrt(sums[i] / counts[i]))
    }
}
