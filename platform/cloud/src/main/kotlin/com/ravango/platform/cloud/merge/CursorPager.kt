package com.ravango.platform.cloud.merge

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Position of an incremental pull: rows with `server_updated_at >= sinceUs`, skipping the first [offset] rows
 * that share exactly `sinceUs` (already seen on the previous page).
 */
data class PageCursor(val sinceUs: Long, val offset: Int = 0)

/**
 * Keyset-style paging over `server_updated_at` (µs precision, set by `clock_timestamp()` on the server).
 *
 * Queries use `gte` + `order=server_updated_at.asc,id.asc` so rows that share a timestamp can never be skipped
 * between pages: when a page ends on timestamp T, the next page starts at T and skips the rows at T already seen.
 * A page consisting entirely of one timestamp just advances the offset. Re-fetching boundary rows on the next
 * sync is harmless (the merge policy treats them as echoes).
 */
object CursorPager {

    /** The next page to request, or null when [pageTimestamps] (ascending) was the last page. */
    fun next(current: PageCursor, pageTimestamps: List<Long>, limit: Int): PageCursor? {
        if (pageTimestamps.size < limit) return null
        val last = pageTimestamps.last()
        return if (last == current.sinceUs) {
            PageCursor(current.sinceUs, current.offset + pageTimestamps.size)
        } else {
            PageCursor(last, pageTimestamps.count { it == last })
        }
    }

    /** Cursor to persist after processing a page (never moves backwards). */
    fun persisted(current: PageCursor, pageTimestamps: List<Long>): Long =
        maxOf(current.sinceUs, pageTimestamps.maxOrNull() ?: current.sinceUs)

    /** Parses a PostgREST timestamptz (e.g. `2026-09-26T10:11:12.123456+00:00`) to epoch µs. */
    fun parseTimestampUs(value: String): Long {
        val instant = runCatching { OffsetDateTime.parse(value).toInstant() }
            .getOrElse { OffsetDateTime.parse(value.replace(' ', 'T').let { if (it.length > 19 && it[it.length - 3] == '+') "$it:00" else it }).toInstant() }
        return ChronoUnit.MICROS.between(Instant.EPOCH, instant)
    }

    /** Formats epoch µs as an ISO-8601 UTC timestamp with microseconds (URL-safe, no '+'). */
    fun formatTimestampUs(us: Long): String {
        val instant = Instant.EPOCH.plus(us, ChronoUnit.MICROS)
        return ISO_MICROS.format(instant.atOffset(ZoneOffset.UTC))
    }

    private val ISO_MICROS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
}
