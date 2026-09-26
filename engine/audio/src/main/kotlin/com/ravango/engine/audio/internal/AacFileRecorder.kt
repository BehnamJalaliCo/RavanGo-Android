package com.ravango.engine.audio.internal

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.ravango.core.common.log.RgLog
import com.ravango.engine.audio.AudioEngineError
import com.ravango.engine.audio.AudioRecordingHandle
import com.ravango.engine.audio.PcmFormat
import com.ravango.engine.audio.PcmSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/** Lock-free single-producer/single-consumer ring of 16-bit samples. Overflowing writes are dropped. */
internal class ShortRing(capacity: Int) {
    private val buf = ShortArray(capacity)
    @Volatile private var written = 0L
    @Volatile private var read = 0L

    val available: Int get() = (written - read).toInt()

    /** Producer side. Returns the number of samples accepted. */
    fun write(src: ShortArray, offset: Int, length: Int): Int {
        val free = buf.size - available
        val n = minOf(free, length)
        var w = (written % buf.size).toInt()
        var i = 0
        while (i < n) {
            val chunk = minOf(n - i, buf.size - w)
            System.arraycopy(src, offset + i, buf, w, chunk)
            i += chunk
            w = (w + chunk) % buf.size
        }
        written += n
        return n
    }

    /** Consumer side: moves up to [maxSamples] samples into [dst] (native-order short view). */
    fun read(dst: ByteBuffer, maxSamples: Int): Int {
        val n = minOf(available, maxSamples, dst.remaining() / 2)
        var r = (read % buf.size).toInt()
        val sb = dst.order(ByteOrder.nativeOrder()).asShortBuffer()
        var i = 0
        while (i < n) {
            val chunk = minOf(n - i, buf.size - r)
            sb.put(buf, r, chunk)
            i += chunk
            r = (r + chunk) % buf.size
        }
        dst.position(dst.position() + n * 2)
        read += n
        return n
    }
}

/**
 * Audio-only recorder: receives processed PCM as a [PcmSink] (on the capture thread), hands it to an encoder
 * thread through a 4 s ring, encodes AAC-LC with MediaCodec and muxes into an .m4a with MediaMuxer.
 *
 * - Pause/resume drop PCM while paused; presentation times come from the encoded sample count, so the file's
 *   timeline is continuous (no gap for the paused time).
 * - Format changes mid-recording (input fallback) are converted to the initial format.
 * - Crash safety: MP4 files are only playable after `MediaMuxer.stop()` writes the index, so a process death
 *   would lose the file. Every encoded frame is therefore also appended to an ADTS journal
 *   (`<file>` + [AudioRecordingRecovery.JOURNAL_SUFFIX], flushed every second, fsync'd every 5 s). A clean stop
 *   deletes it; after a crash [AudioRecordingRecovery.recoverAll] remuxes it into the .m4a (at most the last
 *   ~1 s of audio is lost).
 */
internal class AacFileRecorder(
    override val file: File,
    bitrateKbps: Int,
    private val onError: (AudioEngineError) -> Unit,
) : PcmSink, AudioRecordingHandle {

    private val bitrate = bitrateKbps.coerceIn(32, 320) * 1000
    private val _duration = MutableStateFlow(0L)
    override val durationUs: StateFlow<Long> = _duration.asStateFlow()

    @Volatile private var paused = false
    @Volatile private var stopRequested = false
    private val stopping = AtomicBoolean(false)
    private val result = CompletableDeferred<File?>()

    // Capture-thread state.
    private var converter: PcmConverter? = null
    @Volatile private var ring: ShortRing? = null
    @Volatile private var encoderThread: Thread? = null

    /** Set by the engine: stops PCM delivery. */
    @Volatile var detachFromEngine: (() -> Unit)? = null

    override fun onFormat(format: PcmFormat) {
        val existing = converter
        if (existing != null) {
            existing.setSource(format.sampleRate, format.channels)
            return
        }
        if (stopRequested) return
        converter = PcmConverter(format.sampleRate, format.channels)
        ring = ShortRing(format.sampleRate * format.channels * 4)
        encoderThread = Thread({ encodeLoop(format) }, "RavanGo-AacWriter").apply { start() }
    }

    override fun onPcm(samples: ShortArray, length: Int, presentationTimeNs: Long) {
        if (paused || stopRequested) return
        val conv = converter ?: return
        val r = ring ?: return
        if (conv.isPassthrough) {
            if (r.write(samples, 0, length) < length) overflowed = true
        } else {
            val n = conv.convert(samples, length)
            if (r.write(conv.output, 0, n) < n) overflowed = true
        }
        encoderThread?.let { LockSupport.unpark(it) }
    }

    @Volatile private var overflowed = false

    override fun pause() { paused = true }
    override fun resume() { paused = false }

    override suspend fun stop(): File? {
        if (stopping.compareAndSet(false, true)) {
            stopRequested = true
            detachFromEngine?.invoke()
            val t = encoderThread
            if (t == null) {
                // No audio ever arrived (capture never started).
                file.delete()
                result.complete(null)
            } else {
                LockSupport.unpark(t)
            }
        }
        return result.await()
    }

    private fun encodeLoop(format: PcmFormat) {
        val sampleRate = format.sampleRate
        val channels = format.channels
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var journal: AdtsJournal? = null
        var muxerStarted = false
        var track = -1
        var samplesWritten = 0L
        var ok = false
        try {
            file.parentFile?.mkdirs()
            val mediaFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, if (channels == 1) minOf(bitrate, 256_000) else bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            journal = AdtsJournal.open(AudioRecordingRecovery.journalFor(file), sampleRate, channels)

            val info = MediaCodec.BufferInfo()
            val ring = this.ring!!
            val framesPerInput = 1024
            var framesQueued = 0L
            var inputIndex = -1
            var eosQueued = false
            var outputDone = false
            var lastPublishedUs = -1L
            while (!outputDone) {
                var progressed = false
                if (!eosQueued) {
                    if (inputIndex < 0) inputIndex = codec.dequeueInputBuffer(0)
                    if (inputIndex >= 0) {
                        val available = ring.available
                        val stopping = stopRequested
                        if (available >= framesPerInput * channels || (stopping && available > 0)) {
                            val buffer = codec.getInputBuffer(inputIndex)!!
                            buffer.clear()
                            val maxSamples = minOf(framesPerInput * channels, buffer.remaining() / 2) / channels * channels
                            val n = ring.read(buffer, maxSamples)
                            val ptsUs = framesQueued * 1_000_000L / sampleRate
                            codec.queueInputBuffer(inputIndex, 0, n * 2, ptsUs, 0)
                            framesQueued += n / channels
                            inputIndex = -1
                            progressed = true
                        } else if (stopping && available == 0) {
                            val ptsUs = framesQueued * 1_000_000L / sampleRate
                            codec.queueInputBuffer(inputIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputIndex = -1
                            eosQueued = true
                            progressed = true
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, if (progressed) 0 else 2_000)
                when {
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    out >= 0 -> {
                        val data = codec.getOutputBuffer(out)
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (data != null && info.size > 0 && !isConfig && muxerStarted) {
                            data.position(info.offset)
                            data.limit(info.offset + info.size)
                            journal?.append(data, info.presentationTimeUs)
                            data.position(info.offset)
                            muxer.writeSampleData(track, data, info)
                            samplesWritten++
                        }
                        codec.releaseOutputBuffer(out, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        progressed = true
                    }
                }
                val durationUs = framesQueued * 1_000_000L / sampleRate
                if (durationUs - lastPublishedUs >= 100_000) {
                    _duration.value = durationUs
                    lastPublishedUs = durationUs
                }
                if (!progressed && !stopRequested && ring.available < framesPerInput * channels) {
                    LockSupport.parkNanos(5_000_000L)
                }
            }
            _duration.value = framesQueued * 1_000_000L / sampleRate
            if (muxerStarted && samplesWritten > 0) {
                muxer.stop()
                ok = true
            }
            if (overflowed) RgLog.w(TAG, "Encoder fell behind; some audio was dropped")
        } catch (e: Exception) {
            RgLog.e(TAG, "Audio-only recording failed", e)
            stopRequested = true
            runCatching { detachFromEngine?.invoke() }
            onError(AudioEngineError.RecordingFailed(file, e.message))
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { muxer?.release() }
            runCatching { journal?.close() }
        }
        val journalFile = AudioRecordingRecovery.journalFor(file)
        val final = if (ok) {
            journalFile.delete()
            file
        } else {
            // Last resort: rebuild the .m4a from the journal.
            runCatching { AudioRecordingRecovery.recover(journalFile) }.getOrNull() ?: run {
                file.delete(); journalFile.delete(); null
            }
        }
        result.complete(final)
    }

    private companion object { const val TAG = "AacRecorder" }
}

/** Append-only ADTS stream of the encoded frames (crash journal). */
internal class AdtsJournal private constructor(
    private val stream: FileOutputStream,
    private val frequencyIndex: Int,
    private val channels: Int,
) {
    private val out = BufferedOutputStream(stream, 64 * 1024)
    private val header = ByteArray(Adts.HEADER_SIZE)
    private var scratch = ByteArray(2048)
    private var lastFlushUs = 0L
    private var lastSyncUs = 0L

    fun append(data: ByteBuffer, ptsUs: Long) {
        val size = data.remaining()
        if (size > scratch.size) scratch = ByteArray(size)
        data.get(scratch, 0, size)
        Adts.writeHeader(header, frequencyIndex, channels, size)
        out.write(header)
        out.write(scratch, 0, size)
        if (ptsUs - lastFlushUs >= 1_000_000) {
            out.flush()
            lastFlushUs = ptsUs
            if (ptsUs - lastSyncUs >= 5_000_000) {
                runCatching { stream.fd.sync() }
                lastSyncUs = ptsUs
            }
        }
    }

    fun close() {
        runCatching { out.flush() }
        out.close()
    }

    companion object {
        fun open(file: File, sampleRate: Int, channels: Int): AdtsJournal? {
            val index = Adts.frequencyIndex(sampleRate)
            if (index < 0 || channels !in 1..7) return null
            return runCatching { AdtsJournal(FileOutputStream(file, false), index, channels) }.getOrNull()
        }
    }
}
