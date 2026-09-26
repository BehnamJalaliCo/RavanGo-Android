package com.ravango.feature.projects.common

import android.icu.text.DateFormat
import android.icu.util.ULocale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.ravango.core.common.format.localizeDigits
import com.ravango.feature.projects.R
import java.util.Date
import java.util.Locale

/** Coarse "how long ago" bucket; pure so it can be unit-tested and shared by every list. */
sealed interface RelativeTime {
    data object JustNow : RelativeTime
    data class Minutes(val count: Int) : RelativeTime
    data class Hours(val count: Int) : RelativeTime
    data object Yesterday : RelativeTime
    data class Days(val count: Int) : RelativeTime
    data class Date(val epochMs: Long) : RelativeTime
}

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR

fun relativeTime(then: Long, now: Long): RelativeTime {
    val diff = now - then
    return when {
        diff < MINUTE -> RelativeTime.JustNow // also covers small clock skew into the future
        diff < HOUR -> RelativeTime.Minutes((diff / MINUTE).toInt())
        diff < DAY -> RelativeTime.Hours((diff / HOUR).toInt())
        diff < 2 * DAY -> RelativeTime.Yesterday
        diff < 7 * DAY -> RelativeTime.Days((diff / DAY).toInt())
        else -> RelativeTime.Date(then)
    }
}

/** The locale the UI is actually rendered in (per-app locale aware). */
@Composable
@ReadOnlyComposable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

/** Formats a calendar date; Persian uses the Solar Hijri calendar with Persian digits. */
fun formatCalendarDate(epochMs: Long, locale: Locale): String {
    val uLocale = if (locale.language == "fa") ULocale("fa_IR@calendar=persian") else ULocale.forLocale(locale)
    val format = DateFormat.getInstanceForSkeleton("yMMMd", uLocale)
    return format.format(Date(epochMs)).localizeDigits(locale)
}

@Composable
fun formatRelativeTime(then: Long, now: Long = System.currentTimeMillis()): String {
    val locale = currentLocale()
    return when (val r = relativeTime(then, now)) {
        RelativeTime.JustNow -> stringResource(R.string.projects_time_just_now)
        is RelativeTime.Minutes -> pluralStringResource(R.plurals.projects_time_minutes, r.count, r.count.toString().localizeDigits(locale))
        is RelativeTime.Hours -> pluralStringResource(R.plurals.projects_time_hours, r.count, r.count.toString().localizeDigits(locale))
        RelativeTime.Yesterday -> stringResource(R.string.projects_time_yesterday)
        is RelativeTime.Days -> pluralStringResource(R.plurals.projects_time_days, r.count, r.count.toString().localizeDigits(locale))
        is RelativeTime.Date -> formatCalendarDate(r.epochMs, locale)
    }
}
