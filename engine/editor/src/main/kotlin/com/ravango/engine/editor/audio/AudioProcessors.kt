package com.ravango.engine.editor.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import com.ravango.core.common.log.RgLog
import com.ravango.core.media.dsp.VoiceProcessingConfig
import com.ravango.core.media.dsp.VoiceProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Applies a [GainEnvelope] (volume, fades, ducking) to interleaved PCM.
 *
 * Supports 16-bit integer PCM (the format Media3 decoders produce) and 32-bit float PCM. Position is counted in frames
 * since the last flush; in export there is exactly one flush at the start, so fades are sample-accurate. In preview a
 * seek flushes the processor, after which the envelope restarts from the seek point (the fade-in replays), which is the
 * accepted trade-off because [BaseAudioProcessor.flush] carries no stream position.
 */
class GainEnvelopeAudioProcessor(private val envelope: GainEnvelope) : BaseAudioProcessor() {
    private var framesProcessed = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return if (envelope.isUnity) AudioProcessor.AudioFormat.NOT_SET else inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val format = inputAudioFormat
        val channels = format.channelCount
        val bytesPerFrame = format.bytesPerFrame
        val frames = remaining / bytesPerFrame
        val out = replaceOutputBuffer(frames * bytesPerFrame)
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val sampleRate = format.sampleRate.toDouble()
        var gain = 1f
        for (f in 0 until frames) {
            // Re-evaluate the envelope every 32 frames (<1 ms): smooth enough, much cheaper than per sample.
            if (f and 31 == 0) gain = envelope.gainAt(((framesProcessed + f) * 1_000_000.0 / sampleRate).toLong())
            if (format.encoding == C.ENCODING_PCM_16BIT) {
                for (c in 0 until channels) {
                    val s = input.short * gain
                    out.putShort(s.coerceIn(-32768f, 32767f).toInt().toShort())
                }
            } else {
                for (c in 0 until channels) out.putFloat((input.float * gain).coerceIn(-1f, 1f))
            }
        }
        // Skip any partial frame the upstream might have left (should not happen).
        inputBuffer.position(inputBuffer.limit())
        framesProcessed += frames
        out.flip()
    }

    override fun onFlush() {
        framesProcessed = 0
    }

    override fun onReset() {
        framesProcessed = 0
    }
}

/**
 * Runs the shared [VoiceProcessor] chain (high-pass → spectral noise reduction → voice enhancement → limiter) on a clip.
 * The processor is created per configured format. If the DSP fails at runtime the audio passes through unchanged
 * (logged once) so an export never breaks because of an optional enhancement.
 */
class VoiceCleanupAudioProcessor(
    private val noiseReduction: Float,
    private val voiceEnhance: Boolean,
) : BaseAudioProcessor() {
    private var processor: VoiceProcessor? = null
    private var shorts = ShortArray(0)
    private var floats = FloatArray(0)
    private var failed = false

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        if (noiseReduction <= 0f && !voiceEnhance) return AudioProcessor.AudioFormat.NOT_SET
        processor = runCatching {
            VoiceProcessor(
                VoiceProcessingConfig(
                    sampleRate = inputAudioFormat.sampleRate,
                    channels = inputAudioFormat.channelCount,
                    highPass = true,
                    noiseReduction = noiseReduction.coerceIn(0f, 1f),
                    voiceEnhance = voiceEnhance,
                    limiter = true,
                ),
            )
        }.onFailure { RgLog.w(TAG, "Voice processor unavailable", it) }.getOrNull()
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining)
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val p = processor
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            val n = remaining / 2
            if (shorts.size < n) shorts = ShortArray(n)
            input.asShortBuffer().get(shorts, 0, n)
            if (p != null && !failed) {
                try {
                    p.process(shorts, 0, n)
                } catch (t: Throwable) {
                    failed = true
                    RgLog.w(TAG, "Voice processing failed; passing audio through", t)
                    input.asShortBuffer().get(shorts, 0, n)
                }
            }
            out.asShortBuffer().put(shorts, 0, n)
            out.position(n * 2)
        } else {
            val n = remaining / 4
            if (floats.size < n) floats = FloatArray(n)
            input.asFloatBuffer().get(floats, 0, n)
            if (p != null && !failed) {
                try {
                    p.process(floats, 0, n)
                } catch (t: Throwable) {
                    failed = true
                    RgLog.w(TAG, "Voice processing failed; passing audio through", t)
                    input.asFloatBuffer().get(floats, 0, n)
                }
            }
            out.asFloatBuffer().put(floats, 0, n)
            out.position(n * 4)
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    override fun onFlush() {
        runCatching { processor?.reset() }
    }

    override fun onReset() {
        processor = null
        failed = false
    }

    private companion object {
        const val TAG = "VoiceCleanup"
    }
}
