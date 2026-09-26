package com.ravango.engine.ai.speech

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Streaming writer for 16-bit PCM mono WAV files; the header sizes are patched on [close]. */
class WavWriter(val file: File, private val sampleRate: Int) : AutoCloseable {
    private val out = BufferedOutputStream(FileOutputStream(file), 64 * 1024)
    private val scratch = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
    var samplesWritten: Long = 0
        private set

    init {
        out.write(header(sampleRate, 0))
    }

    fun write(samples: FloatArray, from: Int = 0, to: Int = samples.size) {
        var i = from
        while (i < to) {
            scratch.clear()
            while (i < to && scratch.remaining() >= 2) {
                val v = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                scratch.putShort(v)
                i++
            }
            out.write(scratch.array(), 0, scratch.position())
        }
        samplesWritten += (to - from)
    }

    override fun close() {
        out.flush()
        out.close()
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(header(sampleRate, samplesWritten * 2))
        }
    }

    companion object {
        const val HEADER_BYTES = 44

        fun header(sampleRate: Int, dataBytes: Long): ByteArray {
            val b = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray(Charsets.US_ASCII))
            b.putInt((36 + dataBytes).toInt())
            b.put("WAVE".toByteArray(Charsets.US_ASCII))
            b.put("fmt ".toByteArray(Charsets.US_ASCII))
            b.putInt(16) // PCM chunk size
            b.putShort(1) // PCM
            b.putShort(1) // mono
            b.putInt(sampleRate)
            b.putInt(sampleRate * 2) // byte rate
            b.putShort(2) // block align
            b.putShort(16) // bits per sample
            b.put("data".toByteArray(Charsets.US_ASCII))
            b.putInt(dataBytes.toInt())
            return b.array()
        }

        /** Reads the 16-bit samples of a WAV written by [WavWriter]. */
        fun readSamples(file: File): ShortArray {
            val bytes = file.readBytes()
            if (bytes.size <= HEADER_BYTES) return ShortArray(0)
            val bb = ByteBuffer.wrap(bytes, HEADER_BYTES, bytes.size - HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            return ShortArray(bb.remaining()).also { bb.get(it) }
        }
    }
}
