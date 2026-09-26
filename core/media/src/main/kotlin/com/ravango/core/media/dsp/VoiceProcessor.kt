package com.ravango.core.media.dsp

import kotlin.math.exp

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

/**
 * Implementation notes:
 * - High-pass: RBJ 2nd-order Butterworth (Q 0.707) at 80 Hz.
 * - Noise reduction: [SpectralNoiseReducer] (STFT 512/256 at 48 kHz). The STFT always runs, so the output is
 *   delayed by a constant [latencySamples] frames whatever the config — timestamps of processed audio must be
 *   moved back by `latencySamples / sampleRate` seconds (the audio engine does this for its sinks). To flush the
 *   tail at end of stream, feed [latencySamples] frames of silence.
 * - Voice enhancement: −2 dB low shelf at 250 Hz (de-mud), +3 dB peak at 3 kHz (Q 1), RMS compressor
 *   (−18 dBFS, 3:1, 10/120 ms, +4 dB makeup).
 * - Gain: smoothed (≈ 20 ms), so moving a slider never produces zipper noise.
 * - Limiter: instant-attack peak limiter to a −1 dBFS ceiling; the output is always clamped to full scale.
 *
 * Threading: [process]/[reset] must be called from one thread at a time; [updateConfig] may be called from any
 * thread and takes effect at the start of the next [process] call. Switching stages on/off and changing the
 * gain/strength is crossfaded (glitch-free). Changing [VoiceProcessingConfig.sampleRate] or
 * [VoiceProcessingConfig.channels] rebuilds the chain (state is reset).
 *
 * [process] never allocates. Samples of a trailing partial frame (length not a multiple of channels) are left as is.
 */
class VoiceProcessor(config: VoiceProcessingConfig) {
    var config: VoiceProcessingConfig = config
        private set

    @Volatile private var pending: VoiceProcessingConfig? = null
    private var chain = Chain(config)

    /** Constant processing delay in frames (per channel) at the current sample rate. */
    val latencySamples: Int get() = chain.nr.latencySamples

    /** Processing delay in nanoseconds. */
    val latencyNs: Long get() = latencySamples * 1_000_000_000L / chain.sampleRate

    fun updateConfig(newConfig: VoiceProcessingConfig) {
        require(newConfig.sampleRate > 0 && newConfig.channels > 0) { "Invalid config $newConfig" }
        config = newConfig
        pending = newConfig
    }

    /** Processes [length] interleaved samples starting at [offset], in place. */
    fun process(samples: ShortArray, offset: Int, length: Int) {
        applyPending()
        val c = chain
        val ch = c.channels
        val scratch = c.scratch
        var remainingFrames = length / ch
        var pos = offset
        while (remainingFrames > 0) {
            val frames = minOf(remainingFrames, BLOCK_FRAMES)
            val n = frames * ch
            for (i in 0 until n) scratch[i] = samples[pos + i] * INV_32768
            c.processBlock(scratch, 0, frames)
            for (i in 0 until n) {
                val v = scratch[i] * 32768f
                samples[pos + i] = when {
                    v >= 32767f -> 32767
                    v <= -32768f -> -32768
                    else -> (if (v >= 0f) v + 0.5f else v - 0.5f).toInt().toShort()
                }
            }
            pos += n
            remainingFrames -= frames
        }
    }

    /** Float variant (samples in -1..1), in place. */
    fun process(samples: FloatArray, offset: Int, length: Int) {
        applyPending()
        val c = chain
        val ch = c.channels
        var remainingFrames = length / ch
        var pos = offset
        while (remainingFrames > 0) {
            val frames = minOf(remainingFrames, BLOCK_FRAMES)
            c.processBlock(samples, pos, frames)
            pos += frames * ch
            remainingFrames -= frames
        }
    }

    /** Clears all filter/detector/STFT state (e.g. before processing a discontinuous stream). */
    fun reset() {
        applyPending()
        chain.reset()
    }

    private fun applyPending() {
        val p = pending ?: return
        pending = null
        if (p.sampleRate != chain.sampleRate || p.channels != chain.channels) {
            chain = Chain(p)
        } else {
            chain.setTargets(p)
        }
    }

    private class Chain(initial: VoiceProcessingConfig) {
        val sampleRate = initial.sampleRate
        val channels = initial.channels
        val scratch = FloatArray(BLOCK_FRAMES * channels)

        private val rampStep = 1f / (0.02f * sampleRate) // 20 ms crossfades
        private val hpf = Biquad(BiquadCoefficients.highPass(sampleRate, 80.0, 0.7071), channels)
        private val hpfMix = LinearRamp(rampStep, if (initial.highPass) 1f else 0f)

        val nr = SpectralNoiseReducer(sampleRate, channels)

        private val deMud = Biquad(BiquadCoefficients.lowShelf(sampleRate, 250.0, -2.0), channels)
        private val presence = Biquad(BiquadCoefficients.peaking(sampleRate, minOf(3000.0, sampleRate * 0.4), 3.0, 1.0), channels)
        private val compressor = Compressor(sampleRate, channels)
        private val enhanceMix = LinearRamp(rampStep, if (initial.voiceEnhance) 1f else 0f)
        private val wet = FloatArray(channels)

        private val gainCoef = (1.0 - exp(-1.0 / (0.02 * sampleRate))).toFloat()
        private var gainTarget = dbToLinear(initial.gainDb)
        private var gain = gainTarget

        private val limiter = PeakLimiter(sampleRate, channels)
        private val limiterMix = LinearRamp(rampStep, if (initial.limiter) 1f else 0f)
        private val limited = FloatArray(channels)

        init { nr.setStrength(initial.noiseReduction); nr.reset() }

        fun setTargets(c: VoiceProcessingConfig) {
            if (c.highPass && hpfMix.isSilent) hpf.reset()
            hpfMix.target = if (c.highPass) 1f else 0f
            nr.setStrength(c.noiseReduction)
            if (c.voiceEnhance && enhanceMix.isSilent) { deMud.reset(); presence.reset(); compressor.reset() }
            enhanceMix.target = if (c.voiceEnhance) 1f else 0f
            gainTarget = dbToLinear(c.gainDb.coerceIn(-60f, 40f))
            if (c.limiter && limiterMix.isSilent) limiter.reset()
            limiterMix.target = if (c.limiter) 1f else 0f
        }

        fun reset() {
            hpf.reset(); nr.reset(); deMud.reset(); presence.reset(); compressor.reset(); limiter.reset()
            hpfMix.snap(hpfMix.target); enhanceMix.snap(enhanceMix.target); limiterMix.snap(limiterMix.target)
            gain = gainTarget
        }

        fun processBlock(buf: FloatArray, offset: Int, frames: Int) {
            val ch = channels
            val end = offset + frames * ch

            // 1. High-pass.
            if (!hpfMix.isSilent) {
                var i = offset
                while (i < end) {
                    val m = hpfMix.next()
                    for (c in 0 until ch) {
                        val x = buf[i + c]
                        buf[i + c] = x + m * (hpf.process(x, c) - x)
                    }
                    i += ch
                }
                hpf.sanitize()
            }

            // 2. Noise reduction (always runs: constant latency).
            nr.process(buf, offset, frames)

            // 3. Voice enhancement.
            if (!enhanceMix.isSilent) {
                var i = offset
                while (i < end) {
                    val m = enhanceMix.next()
                    for (c in 0 until ch) wet[c] = presence.process(deMud.process(buf[i + c], c), c)
                    compressor.processFrame(wet, 0)
                    for (c in 0 until ch) {
                        val x = buf[i + c]
                        buf[i + c] = x + m * (wet[c] - x)
                    }
                    i += ch
                }
                deMud.sanitize(); presence.sanitize()
            }

            // 4. Digital gain (smoothed) + 5. limiter + final clamp.
            val limiterActive = !limiterMix.isSilent
            var i = offset
            while (i < end) {
                gain += gainCoef * (gainTarget - gain)
                val g = gain
                if (limiterActive) {
                    for (c in 0 until ch) { val v = buf[i + c] * g; buf[i + c] = v; limited[c] = v }
                    limiter.processFrame(limited, 0)
                    val m = limiterMix.next()
                    for (c in 0 until ch) {
                        val x = buf[i + c]
                        buf[i + c] = clamp(x + m * (limited[c] - x))
                    }
                } else {
                    for (c in 0 until ch) buf[i + c] = clamp(buf[i + c] * g)
                }
                i += ch
            }
        }

        private fun clamp(v: Float): Float = if (v > 1f) 1f else if (v < -1f) -1f else v
    }

    private companion object {
        const val BLOCK_FRAMES = 256
        const val INV_32768 = 1f / 32768f
    }
}
