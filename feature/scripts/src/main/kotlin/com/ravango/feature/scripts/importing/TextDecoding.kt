package com.ravango.feature.scripts.importing

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Decodes text files of unknown encoding: honours UTF-8 / UTF-16 byte-order marks, detects BOM-less UTF-16 from
 * NUL patterns, accepts valid UTF-8 and otherwise falls back to Windows-1256 — the legacy encoding of most older
 * Persian documents — so imported scripts never turn into mojibake.
 */
object TextDecoding {
    private val WINDOWS_1256: Charset? = runCatching { Charset.forName("windows-1256") }.getOrNull()

    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        // Byte-order marks
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        guessUtf16(bytes)?.let { return String(bytes, it) }
        strictDecode(bytes, Charsets.UTF_8)?.let { return it }
        return WINDOWS_1256?.let { String(bytes, it) } ?: String(bytes, Charsets.ISO_8859_1)
    }

    /** BOM-less UTF-16: many NULs at even (BE) or odd (LE) positions. */
    private fun guessUtf16(bytes: ByteArray): Charset? {
        val sample = minOf(bytes.size, 4096) and 0x7FFFFFFE
        if (sample < 4) return null
        var evenZero = 0
        var oddZero = 0
        for (i in 0 until sample) if (bytes[i] == 0.toByte()) { if (i % 2 == 0) evenZero++ else oddZero++ }
        val pairs = sample / 2
        return when {
            oddZero > pairs * 0.3 && evenZero < pairs * 0.05 -> Charsets.UTF_16LE
            evenZero > pairs * 0.3 && oddZero < pairs * 0.05 -> Charsets.UTF_16BE
            else -> null
        }
    }

    private fun strictDecode(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /** Normalises line endings and trims trailing whitespace on each line; collapses 3+ blank lines. */
    fun tidy(text: String): String = text
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace('\u000B', '\n')
        .lines()
        .joinToString("\n") { it.trimEnd() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim('\n', ' ', '\t', '﻿')
}
