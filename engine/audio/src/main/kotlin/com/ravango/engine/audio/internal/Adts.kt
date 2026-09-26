package com.ravango.engine.audio.internal

/** ADTS (AAC transport) helpers — pure, JVM-testable. */
internal object Adts {
    const val HEADER_SIZE = 7
    private val SAMPLE_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    fun frequencyIndex(sampleRate: Int): Int = SAMPLE_RATES.indexOf(sampleRate)

    fun sampleRate(frequencyIndex: Int): Int = SAMPLE_RATES.getOrElse(frequencyIndex) { 0 }

    /** Writes a 7-byte AAC-LC ADTS header (no CRC) for a raw frame of [payloadSize] bytes. */
    fun writeHeader(out: ByteArray, frequencyIndex: Int, channels: Int, payloadSize: Int) {
        val frameLength = payloadSize + HEADER_SIZE
        val profile = 1 // AAC LC (object type 2) − 1
        out[0] = 0xFF.toByte()
        out[1] = 0xF1.toByte() // MPEG-4, layer 0, no CRC
        out[2] = ((profile shl 6) or (frequencyIndex shl 2) or ((channels shr 2) and 0x1)).toByte()
        out[3] = (((channels and 0x3) shl 6) or ((frameLength shr 11) and 0x3)).toByte()
        out[4] = ((frameLength shr 3) and 0xFF).toByte()
        out[5] = (((frameLength and 0x7) shl 5) or 0x1F).toByte()
        out[6] = 0xFC.toByte()
    }

    class Header(val frequencyIndex: Int, val channels: Int, val frameLength: Int, val headerSize: Int)

    /** Parses a header at [offset]; null if the bytes are not an ADTS sync word. */
    fun parseHeader(b: ByteArray, offset: Int = 0): Header? {
        val b0 = b[offset].toInt() and 0xFF
        val b1 = b[offset + 1].toInt() and 0xFF
        if (b0 != 0xFF || (b1 and 0xF0) != 0xF0) return null
        val protectionAbsent = b1 and 0x1
        val b2 = b[offset + 2].toInt() and 0xFF
        val b3 = b[offset + 3].toInt() and 0xFF
        val b4 = b[offset + 4].toInt() and 0xFF
        val b5 = b[offset + 5].toInt() and 0xFF
        val freq = (b2 shr 2) and 0xF
        val channels = ((b2 and 0x1) shl 2) or ((b3 shr 6) and 0x3)
        val length = ((b3 and 0x3) shl 11) or (b4 shl 3) or ((b5 shr 5) and 0x7)
        val headerSize = if (protectionAbsent == 1) 7 else 9
        if (length < headerSize) return null
        return Header(freq, channels, length, headerSize)
    }

    /** AudioSpecificConfig (csd-0) for AAC-LC. */
    fun audioSpecificConfig(frequencyIndex: Int, channels: Int): ByteArray = byteArrayOf(
        ((2 shl 3) or (frequencyIndex shr 1)).toByte(),
        (((frequencyIndex and 0x1) shl 7) or (channels shl 3)).toByte(),
    )
}
