package com.ravango.engine.camera.muxer

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** One MP4 file being written. Abstracts MediaMuxer so the segmenting logic can be unit tested on the JVM. */
internal interface MuxerWriter {
    /** [format] is the encoder's output MediaFormat. */
    fun addTrack(format: Any): Int
    fun start()
    fun writeSample(track: Int, data: ByteBuffer, offset: Int, size: Int, ptsUs: Long, flags: Int)
    fun stop()
    fun release()
}

internal fun interface MuxerWriterFactory {
    fun create(file: File): MuxerWriter
}

internal object MediaMuxerWriterFactory : MuxerWriterFactory {
    override fun create(file: File): MuxerWriter = MediaMuxerWriter(file)
}

private class MediaMuxerWriter(file: File) : MuxerWriter {
    private val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()

    override fun addTrack(format: Any): Int = muxer.addTrack(format as MediaFormat)
    override fun start() = muxer.start()

    override fun writeSample(track: Int, data: ByteBuffer, offset: Int, size: Int, ptsUs: Long, flags: Int) {
        info.set(offset, size, ptsUs, flags)
        muxer.writeSampleData(track, data, info)
    }

    override fun stop() = muxer.stop()
    override fun release() = muxer.release()
}

/** What a recording's muxer received and wrote; logged when a take could not be (fully) saved. */
data class MuxSummary(
    val videoFormat: Boolean,
    val audioFormat: Boolean,
    val videoSamplesIn: Long,
    val audioSamplesIn: Long,
    val videoSamplesWritten: Long,
    val audioSamplesWritten: Long,
    val segmentsFinished: Int,
    val segmentsDiscarded: Int,
) {
    fun describe(): String =
        "video format=$videoFormat samples in/written=$videoSamplesIn/$videoSamplesWritten, " +
            "audio format=$audioFormat samples in/written=$audioSamplesIn/$audioSamplesWritten, " +
            "segments finished=$segmentsFinished discarded=$segmentsDiscarded"
}
