package com.ravango.feature.scripts.common

import android.icu.text.DateFormat
import android.icu.util.ULocale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.ravango.core.model.ArgbColor
import java.util.Date
import java.util.Locale

/** Short date in the user's language; Persian uses the Solar Hijri calendar. */
fun formatShortDate(epochMs: Long, locale: Locale = Locale.getDefault()): String {
    val uLocale = if (locale.language == "fa") ULocale("fa_IR@calendar=persian") else ULocale.forLocale(locale)
    return DateFormat.getDateInstance(DateFormat.MEDIUM, uLocale).format(Date(epochMs))
}

/** [text] with [ranges] emphasised (search highlighting). */
fun highlight(text: String, ranges: List<IntRange>, background: Color, color: Color? = null): AnnotatedString = buildAnnotatedString {
    append(text)
    for (r in ranges) {
        val start = r.first.coerceIn(0, text.length)
        val end = (r.last + 1).coerceIn(start, text.length)
        if (end > start) addStyle(SpanStyle(background = background, color = color ?: Color.Unspecified, fontWeight = FontWeight.SemiBold), start, end)
    }
}

fun Color.toArgbLong(): ArgbColor = toArgb().toLong() and 0xFFFFFFFFL
