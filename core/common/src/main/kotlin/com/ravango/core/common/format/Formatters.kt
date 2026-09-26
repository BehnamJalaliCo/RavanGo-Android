package com.ravango.core.common.format

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

private const val PERSIAN_DIGITS = "۰۱۲۳۴۵۶۷۸۹"

/** Converts ASCII digits to Persian digits when [locale] is Persian. */
fun String.localizeDigits(locale: Locale = Locale.getDefault()): String {
    if (locale.language != "fa") return this
    val sb = StringBuilder(length)
    for (ch in this) sb.append(if (ch in '0'..'9') PERSIAN_DIGITS[ch - '0'] else ch)
    return sb.toString()
}

/** Converts Persian/Arabic-Indic digits to ASCII (for parsing user input). */
fun String.normalizeDigits(): String {
    val sb = StringBuilder(length)
    for (ch in this) {
        sb.append(
            when (ch) {
                in '۰'..'۹' -> '0' + (ch - '۰')
                in '٠'..'٩' -> '0' + (ch - '٠')
                else -> ch
            },
        )
    }
    return sb.toString()
}

/** "1:05", "12:03:09" — timecode style, digits localized. */
fun formatDuration(micros: Long, locale: Locale = Locale.getDefault(), showTenths: Boolean = false): String {
    val totalMs = abs(micros) / 1000
    val h = totalMs / 3_600_000
    val m = (totalMs / 60_000) % 60
    val s = (totalMs / 1000) % 60
    val tenths = (totalMs / 100) % 10
    val base = if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    val text = if (showTenths) "$base.$tenths" else base
    return (if (micros < 0) "-$text" else text).localizeDigits(locale)
}

fun formatDurationMs(ms: Long, locale: Locale = Locale.getDefault()): String = formatDuration(ms * 1000, locale)

fun formatBytes(bytes: Long, locale: Locale = Locale.getDefault()): String {
    val units = if (locale.language == "fa") listOf("بایت", "کیلوبایت", "مگابایت", "گیگابایت", "ترابایت") else listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var i = 0
    while (value >= 1024 && i < units.lastIndex) { value /= 1024; i++ }
    val num = if (value >= 100 || i == 0) value.roundToLong().toString() else String.format(Locale.US, "%.1f", value)
    return "${num.localizeDigits(locale)} ${units[i]}"
}

fun formatNumber(n: Number, locale: Locale = Locale.getDefault()): String = n.toString().localizeDigits(locale)
