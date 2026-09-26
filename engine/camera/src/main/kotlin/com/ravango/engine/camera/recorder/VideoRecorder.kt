package com.ravango.engine.camera.recorder

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.ravango.core.common.log.RgLog
import com.ravango.engine.audio.AudioEngine
import com.ravango.engine.audio.PcmSinkHandle
import com.ravango.engine.camera.CameraWarning
import com.ravango.engine.camera.encoder.AudioEncoder
import com.ravango.engine.camera.encoder.VideoEncoder
import com.ravango.engine.camera.encoder.VideoEncoderConfig
import com.ravango.engine.camera.muxer.ManifestCodec
import com.ravango.engine.camera.muxer.MuxSummary
import com.ravango.engine.camera.muxer.RecordingManifest
import com.ravango.engine.camera.muxer.SegmentEntry
import com.ravango.engine.camera.muxer.SegmentedMuxer
import java.io.File
import java.nio.ByteBuffer
import java.util.Collections

/** Recording ids currently being written by this process; crash recovery must never touch them. */
internal object ActiveRecordings {
    private val ids = Collections.synchronizedSet(HashSet<String>())
    fun add(id: String) = ids.add(id)
    fun remove(id: String) = ids.remove(id)
    fun contains(id: String): Boolean = ids.contains(id)
    fun isEmpty(): Boolean = ids.isEmpty()
}

/**
 * One video recording: surface-input video encoder + AAC encoder (fed by the audio engine) + segmented muxer
 * + crash-safe manifest. Created per recording.
 */
internal class VideoRecorder(
    val dir: File,
    manifest: RecordingManifest,
    private val videoConfig: VideoEncoderConfig,
    private val audioBitrate: Int,
    private val withAudio: Boolean,
    private val audioEngine: AudioEngine,
    private val events: Events,
    segmentDurationUs: Long = SEGMENT_DURATION_US,
) : SegmentedMuxer.Listener, VideoEncoder.Sink, AudioEncoder.Sink {

    interface Events {
        fun onRecorderFailure(error: Exception?)
        fun onRecorderWarning(warning: CameraWarning)
    }

    val clock = PtsClock()
    private val frameDurationUs = 1_000_000L / videoConfig.fps.coerceAtLeast(1)
    private val muxer = SegmentedMuxer(dir, withAudio, segmentDurationUs, frameDurationUs, this)
    private val manifestLock = Any()
    private var video: VideoEncoder? = null
    private var audio: AudioEncoder? = null
    private var sinkHandle: PcmSinkHandle? = null
    @Volatile private var failureReported = false

    @Volatile var manifest: RecordingManifest = manifest
        private set

    val durationUs: Long get() = muxer.lastVideoPtsUs.let { if (it < 0) 0 else it + frameDurationUs }
    val bytesWritten: Long get() = muxer.bytesWritten.get()
    val hasAudio: Boolean get() = muxer.hasAudioTrack

    @Volatile private var progressRefNs = System.nanoTime()

    @Volatile private var finalSummary: MuxSummary? = null

    /** What the muxer received/wrote so far (after [stop]: the final result). */
    val summary: MuxSummary get() = finalSummary ?: muxer.summary()

    /**
     * Milliseconds since the video encoder last produced a sample (or since start/resume, whichever is later).
     * A recording whose video stalls this long is stopped and saved rather than silently producing nothing.
     */
    fun videoStalledMs(nowNs: Long = System.nanoTime()): Long {
        val last = maxOf(muxer.lastVideoSampleNs, progressRefNs)
        return (nowNs - last).coerceAtLeast(0) / 1_000_000
    }

    /** Writes the manifest, starts the encoders and returns the surface the GL pipeline must render into. */
    fun start(): Surface {
        progressRefNs = System.nanoTime()
        ManifestCodec.write(dir, manifest)
        val encoder = VideoEncoder.create(videoConfig, this)
        video = encoder
        if (withAudio) {
            val aac = AudioEncoder(audioBitrate, this) { ns -> clock.mapUs(ns) }
            audio = aac
            sinkHandle = runCatching { audioEngine.attachSink(aac) }
                .onFailure {
                    RgLog.e(TAG, "audio sink attach failed; recording video only", it)
                    muxer.disableAudio()
                    events.onRecorderWarning(CameraWarning.AUDIO_UNAVAILABLE)
                }
                .getOrNull()
        }
        return encoder.inputSurface
    }

    fun pause() = clock.pause(System.nanoTime())

    fun resume() {
        progressRefNs = System.nanoTime()
        clock.resume(progressRefNs)
    }

    /**
     * Drains and closes everything; never throws. Blocking; call after the renderer stopped feeding the encoder
     * surface. Returns what was muxed (zero finished segments = nothing playable).
     */
    fun stop(): MuxSummary {
        runCatching { sinkHandle?.detach() }.onFailure { RgLog.w(TAG, "sink detach failed", it) }
        sinkHandle = null
        runCatching { audio?.finish() }.onFailure { RgLog.w(TAG, "audio finish failed", it) }
        runCatching { video?.finish() }.onFailure { RgLog.w(TAG, "video finish failed", it) }
        val result = runCatching { muxer.finish() }.onFailure { RgLog.e(TAG, "muxer finish failed", it) }.getOrElse { muxer.summary() }
        runCatching { video?.release() }
        video = null
        audio = null
        finalSummary = result
        return result
    }

    // --- SegmentedMuxer.Listener ---------------------------------------------------------------------------
    override fun onSegmentFinished(entry: SegmentEntry) {
        synchronized(manifestLock) {
            val segments = manifest.segments + entry
            manifest = manifest.copy(segments = segments, hasAudio = manifest.hasAudio && segments.any { it.firstAudioLocalUs >= 0 })
            runCatching { ManifestCodec.write(dir, manifest) }.onFailure { RgLog.e(TAG, "manifest write failed", it) }
        }
        RgLog.d(TAG, "segment ${entry.index} finished (${entry.durationUs / 1000}ms)")
    }

    override fun onRequestKeyFrame() {
        video?.requestKeyFrame()
    }

    override fun onMuxerError(error: Exception) = reportFailure(error)

    override fun onAudioDropped() = events.onRecorderWarning(CameraWarning.AUDIO_UNAVAILABLE)

    // --- VideoEncoder.Sink ----------------------------------------------------------------------------------
    override fun onVideoFormat(format: MediaFormat) = muxer.onVideoFormat(format)
    override fun onVideoSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo) = muxer.writeVideo(buffer, info)
    override fun onVideoError(error: Exception) = reportFailure(error)

    // --- AudioEncoder.Sink ----------------------------------------------------------------------------------
    override fun onAudioFormat(format: MediaFormat) = muxer.onAudioFormat(format)
    override fun onAudioSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo) = muxer.writeAudio(buffer, info)

    override fun onAudioError(error: Exception) {
        RgLog.e(TAG, "audio failed; continuing video only", error)
        muxer.disableAudio()
        events.onRecorderWarning(CameraWarning.AUDIO_UNAVAILABLE)
    }

    override fun onAudioFormatMismatch() {
        muxer.disableAudio()
        events.onRecorderWarning(CameraWarning.AUDIO_FORMAT_CHANGED)
    }

    private fun reportFailure(error: Exception?) {
        if (failureReported) return
        failureReported = true
        events.onRecorderFailure(error)
    }

    companion object {
        private const val TAG = "VideoRecorder"
        const val SEGMENT_DURATION_US = 60_000_000L
    }
}
