package com.ravango.engine.ai.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.ravango.core.common.di.IoDispatcher
import com.ravango.core.common.log.RgLog
import com.ravango.core.common.result.AppException
import com.ravango.core.common.result.ErrorKind
import com.ravango.core.common.result.Outcome
import com.ravango.core.common.result.outcomeOf
import com.ravango.core.media.MediaProbe
import com.ravango.core.media.PcmDecoder
import com.ravango.core.media.dsp.VoiceProcessingConfig
import com.ravango.core.media.dsp.VoiceProcessor
import com.ravango.engine.ai.api.AiErrors
import com.ravango.engine.ai.api.AudioCleanupService
import com.ravango.engine.ai.speech.Resampler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Offline voice cleanup to a new mono AAC `.m4a`:
 * 1. decode (MediaExtractor/MediaCodec via [PcmDecoder]) → [VoiceProcessor] (high-pass, noise reduction at
 *    [strength], voice enhancement) → temp float file, while measuring integrated loudness ([LoudnessMeter]);
 * 2. apply the gain that brings the result to about −16 LUFS (the common target for spoken social video) through a
 *    second [VoiceProcessor] stage with its limiter on, then encode AAC-LC 128 kbps with MediaCodec + MediaMuxer.
 * Everything runs on device; nothing is uploaded.
 */
@Singleton
class OfflineAudioCleanupService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val decoder: PcmDecoder,
    private val probe: MediaProbe,
    @IoDispatcher private val io: CoroutineDispatcher,
) : AudioCleanupService {

    override suspend fun cleanup(inputUri: String, output: File, strength: Float, onProgress: (Float) -> Unit): Outcome<File> = outcomeOf {
        withContext(io) {
            val durationUs = probe.probe(inputUri)?.durationUs?.takeIf { it > 0 } ?: 0L
            val temp = File(context.cacheDir, "cleanup_${System.nanoTime()}.f32")
            try {
                // Pass 1: decode → process → temp; measure loudness.
                var rate = 0
                var resampler: Resampler? = null
                var processor: VoiceProcessor? = null
                var meter: LoudnessMeter? = null
                DataOutputStream(BufferedOutputStream(FileOutputStream(temp), 256 * 1024)).use { out ->
                    val sourceRate = decoder.decode(inputUri) { block ->
                        if (rate == 0) {
                            rate = if (block.sampleRate in AAC_RATES) block.sampleRate else 48_000
                            if (rate != block.sampleRate) resampler = Resampler(block.sampleRate, rate)
                            processor = VoiceProcessor(
                                VoiceProcessingConfig(
                                    sampleRate = rate,
                                    channels = 1,
                                    highPass = true,
                                    noiseReduction = strength.coerceIn(0f, 1f),
                                    voiceEnhance = true,
                                    limiter = false,
                                ),
                            )
                            meter = LoudnessMeter(rate)
                        }
                        val samples = resampler?.process(block.samples, block.count) ?: block.samples.copyOf(block.count)
                        processor!!.process(samples, 0, samples.size)
                        meter!!.add(samples)
                        for (s in samples) out.writeFloat(s)
                        if (durationUs > 0) onProgress((block.startUs.toFloat() / durationUs * 0.5f).coerceIn(0f, 0.5f))
                    }
                    if (sourceRate == 0) throw AppException(ErrorKind.INVALID_INPUT, AiErrors.NO_AUDIO)
                }
                coroutineContext.ensureActive()
                val m = meter ?: throw AppException(ErrorKind.INVALID_INPUT, AiErrors.NO_AUDIO)
                val lufs = m.integratedLufs()
                val gainDb = lufs?.let { LoudnessMeter.normalizationGainDb(it, m.peakDbfs) } ?: 0.0
                RgLog.d(TAG, "cleanup: measured ${lufs?.let { "%.1f".format(it) } ?: "n/a"} LUFS, gain ${"%.1f".format(gainDb)} dB")

                // Pass 2: gain + limiter → AAC.
                val finalStage = VoiceProcessor(
                    VoiceProcessingConfig(sampleRate = rate, channels = 1, gainDb = gainDb.toFloat(), highPass = false, noiseReduction = 0f, voiceEnhance = false, limiter = true),
                )
                output.parentFile?.mkdirs()
                val totalSamples = temp.length() / 4
                encodeAac(temp, output, rate, totalSamples, finalStage, onProgress)
                onProgress(1f)
                output
            } catch (e: Throwable) {
                output.delete()
                throw e
            } finally {
                temp.delete()
            }
        }
    }

    private suspend fun encodeAac(source: File, output: File, sampleRate: Int, totalSamples: Long, stage: VoiceProcessor, onProgress: (Float) -> Unit) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_SAMPLES * 2 * 4)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var track = -1
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            val floats = FloatArray(FRAME_SAMPLES)
            var samplesQueued = 0L
            var inputDone = false
            var outputDone = false
            DataInputStream(BufferedInputStream(FileInputStream(source), 256 * 1024)).use { input ->
                while (!outputDone) {
                    coroutineContext.ensureActive()
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val buf = codec.getInputBuffer(inIndex)!!
                            buf.clear()
                            val capacitySamples = minOf(FRAME_SAMPLES, buf.capacity() / 2)
                            var n = 0
                            while (n < capacitySamples) {
                                floats[n] = try { input.readFloat() } catch (_: EOFException) { break }
                                n++
                            }
                            if (n > 0) {
                                stage.process(floats, 0, n)
                                val shorts = buf.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                                for (i in 0 until n) shorts.put((floats[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                            }
                            val ptsUs = samplesQueued * 1_000_000L / sampleRate
                            samplesQueued += n
                            if (n < capacitySamples) {
                                codec.queueInputBuffer(inIndex, 0, n * 2, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, n * 2, ptsUs, 0)
                            }
                            if (totalSamples > 0) onProgress(0.5f + 0.5f * (samplesQueued.toFloat() / totalSamples).coerceIn(0f, 1f))
                        }
                    }
                    val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        outIndex >= 0 -> {
                            val data = codec.getOutputBuffer(outIndex)!!
                            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (!isConfig && info.size > 0 && muxerStarted) {
                                data.position(info.offset)
                                data.limit(info.offset + info.size)
                                muxer.writeSampleData(track, data, info)
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { if (muxerStarted) muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    private companion object {
        const val TAG = "AudioCleanup"
        const val FRAME_SAMPLES = 1024
        val AAC_RATES = setOf(8_000, 11_025, 12_000, 16_000, 22_050, 24_000, 32_000, 44_100, 48_000)
    }
}
