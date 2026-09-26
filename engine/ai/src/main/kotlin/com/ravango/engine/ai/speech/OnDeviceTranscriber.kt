package com.ravango.engine.ai.speech

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionPart
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import com.ravango.core.common.log.RgLog
import com.ravango.core.common.result.AppException
import com.ravango.core.common.result.ErrorKind
import com.ravango.core.model.WordTiming
import com.ravango.engine.ai.api.AiErrors
import com.ravango.engine.ai.api.Transcript
import com.ravango.engine.ai.api.TranscriptSegment
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Android's on-device recognizer fed from a file instead of the microphone (API 33+: `EXTRA_AUDIO_SOURCE` with a
 * pipe of 16 kHz mono 16-bit PCM). Only the *on-device* recognizer is used, so no audio leaves the phone.
 *
 * The recognizer returns text per request, so audio is first split into utterances ([UtteranceSegmenter]); each
 * utterance is one request, which places its text in time. On API 34+ word timestamps come from
 * `RecognitionPart.timestampMillis` when the engine supports them; otherwise words are timed by length within the
 * utterance ([WhisperParser.estimateWords]).
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class OnDeviceTranscriber(
    private val context: Context,
    private val io: CoroutineDispatcher,
) {

    private class Result(val text: String, val parts: List<Pair<String, Long>>)

    /** Transcribes one prepared chunk; times are chunk-relative. */
    suspend fun transcribe(samples: ShortArray, sampleRate: Int, language: String?, onProgress: (Float) -> Unit): Transcript {
        val utterances = UtteranceSegmenter.segment(samples, sampleRate)
        val segments = mutableListOf<TranscriptSegment>()
        for ((i, u) in utterances.withIndex()) {
            coroutineContext.ensureActive()
            val startUs = u.startSample * 1_000_000L / sampleRate
            val endUs = u.endSample * 1_000_000L / sampleRate
            val result = recognize(samples, u.startSample, u.endSample, sampleRate, language)
            val text = result.text.trim()
            if (text.isNotEmpty()) {
                val words = if (result.parts.size >= 2) {
                    result.parts.mapIndexed { idx, (word, ms) ->
                        val s = startUs + ms * 1000
                        val e = result.parts.getOrNull(idx + 1)?.let { startUs + it.second * 1000 } ?: endUs
                        WordTiming(word, s, e.coerceAtLeast(s + 80_000).coerceAtMost(endUs))
                    }
                } else {
                    WhisperParser.estimateWords(text, startUs, endUs)
                }
                segments += TranscriptSegment(startUs, endUs, text, words)
            }
            onProgress((i + 1f) / utterances.size)
        }
        return Transcript(language?.substringBefore('-'), segments)
    }

    private suspend fun recognize(samples: ShortArray, from: Int, to: Int, sampleRate: Int, language: String?): Result = coroutineScope {
        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]
        val writer = launch(io) {
            try {
                FileOutputStream(writeSide.fileDescriptor).use { out ->
                    val buf = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
                    var i = from
                    while (i < to) {
                        buf.clear()
                        while (i < to && buf.remaining() >= 2) buf.putShort(samples[i++])
                        out.write(buf.array(), 0, buf.position())
                    }
                }
            } catch (e: java.io.IOException) {
                RgLog.w(TAG, "pipe closed early: ${e.message}")
            } finally {
                runCatching { writeSide.close() }
            }
        }
        val durationMs = (to - from) * 1000L / sampleRate
        try {
            withTimeout(durationMs * 3 + 20_000) {
                withContext(Dispatchers.Main) { runRecognizer(readSide, sampleRate, language) }
            }
        } finally {
            writer.cancel()
            runCatching { readSide.close() }
        }
    }

    private suspend fun runRecognizer(source: ParcelFileDescriptor, sampleRate: Int, language: String?): Result =
        suspendCancellableCoroutine { cont ->
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                language?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag(it)) }
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, sampleRate)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) putExtra(RecognizerIntent.EXTRA_REQUEST_WORD_TIMING, true)
            }
            recognizer.setRecognitionListener(
                object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit

                    override fun onResults(results: Bundle?) {
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                        val parts = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) readParts(results) else emptyList()
                        recognizer.destroy()
                        if (cont.isActive) cont.resume(Result(text, parts))
                    }

                    override fun onError(error: Int) {
                        recognizer.destroy()
                        if (!cont.isActive) return
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> cont.resume(Result("", emptyList()))
                            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                                cont.resumeWithException(AppException(ErrorKind.NOT_SUPPORTED, AiErrors.ON_DEVICE_UNSUPPORTED))
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                                cont.resumeWithException(AppException(ErrorKind.PERMISSION, "recognizer permission"))
                            else -> cont.resumeWithException(AppException(ErrorKind.UNKNOWN, "recognizer error $error"))
                        }
                    }
                },
            )
            cont.invokeOnCancellation {
                // invokeOnCancellation may run off the main thread; SpeechRecognizer must be touched on main.
                android.os.Handler(android.os.Looper.getMainLooper()).post { runCatching { recognizer.cancel(); recognizer.destroy() } }
            }
            recognizer.startListening(intent)
        }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun readParts(results: Bundle?): List<Pair<String, Long>> {
        val parts = results?.getParcelableArrayList(SpeechRecognizer.RECOGNITION_PARTS, RecognitionPart::class.java) ?: return emptyList()
        val timed = parts.filter { it.timestampMillis > 0 || parts.indexOf(it) == 0 }
        if (timed.size < parts.size / 2) return emptyList()
        return parts.map { (it.formattedText ?: it.rawText).trim() to it.timestampMillis }.filter { it.first.isNotEmpty() }
    }

    private fun localeTag(language: String): String = when (language.substringBefore('-')) {
        "fa" -> "fa-IR"
        "en" -> "en-US"
        else -> language
    }

    companion object {
        private const val TAG = "OnDeviceStt"

        fun isAvailable(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    }
}
