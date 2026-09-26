package com.ravango.engine.audio.internal

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import com.ravango.core.common.log.RgLog
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer

/** Rebuilds .m4a files from the ADTS crash journals written by [AacFileRecorder]. */
internal object AudioRecordingRecovery {
    const val JOURNAL_SUFFIX = ".journal.aac"
    private const val TAG = "AudioRecovery"

    fun journalFor(file: File): File = File(file.path + JOURNAL_SUFFIX)

    /** Recovers every journal in [directory]; returns the rebuilt files. */
    fun recoverAll(directory: File): List<File> {
        val journals = directory.listFiles { f -> f.isFile && f.name.endsWith(JOURNAL_SUFFIX) }.orEmpty()
        return journals.mapNotNull { journal ->
            runCatching { recover(journal) }.onFailure { RgLog.w(TAG, "Could not recover ${journal.name}", it) }.getOrNull()
        }
    }

    /** Remuxes [journal] into its target .m4a (replacing any unfinished file). Returns null if it held no audio. */
    fun recover(journal: File): File? {
        if (!journal.isFile) return null
        val target = File(journal.path.removeSuffix(JOURNAL_SUFFIX))
        val temp = File(target.path + ".recovering")
        temp.delete()
        var muxer: MediaMuxer? = null
        var frames = 0
        var started = false
        try {
            BufferedInputStream(FileInputStream(journal), 64 * 1024).use { input ->
                val header = ByteArray(9)
                var payload = ByteArray(4096)
                var track = -1
                var sampleRate = 0
                val info = MediaCodec.BufferInfo()
                while (true) {
                    if (!input.readFully(header, 0, Adts.HEADER_SIZE)) break
                    val h = Adts.parseHeader(header) ?: break
                    if (h.headerSize > Adts.HEADER_SIZE && !input.readFully(header, Adts.HEADER_SIZE, h.headerSize - Adts.HEADER_SIZE)) break
                    val size = h.frameLength - h.headerSize
                    if (size > payload.size) payload = ByteArray(size)
                    if (!input.readFully(payload, 0, size)) break // truncated tail
                    if (!started) {
                        sampleRate = Adts.sampleRate(h.frequencyIndex)
                        if (sampleRate == 0 || h.channels == 0) break
                        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, h.channels).apply {
                            setByteBuffer("csd-0", ByteBuffer.wrap(Adts.audioSpecificConfig(h.frequencyIndex, h.channels)))
                        }
                        muxer = MediaMuxer(temp.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                        track = muxer!!.addTrack(format)
                        muxer!!.start()
                        started = true
                    }
                    info.set(0, size, frames * 1024L * 1_000_000L / sampleRate, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    muxer!!.writeSampleData(track, ByteBuffer.wrap(payload, 0, size), info)
                    frames++
                }
            }
            if (started && frames > 0) muxer?.stop()
        } finally {
            runCatching { muxer?.release() }
        }
        if (!started || frames == 0) {
            temp.delete()
            journal.delete()
            return null
        }
        target.delete()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        journal.delete()
        RgLog.i(TAG, "Recovered ${target.name} ($frames AAC frames)")
        return target
    }

    private fun InputStream.readFully(buf: ByteArray, offset: Int, length: Int): Boolean {
        var read = 0
        while (read < length) {
            val n = read(buf, offset + read, length - read)
            if (n < 0) return false
            read += n
        }
        return true
    }
}
