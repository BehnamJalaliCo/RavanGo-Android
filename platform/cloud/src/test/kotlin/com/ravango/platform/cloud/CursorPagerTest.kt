package com.ravango.platform.cloud

import com.google.common.truth.Truth.assertThat
import com.ravango.platform.cloud.media.MediaQuota
import com.ravango.platform.cloud.merge.CursorPager
import com.ravango.platform.cloud.merge.PageCursor
import org.junit.Test

class CursorPagerTest {

    @Test
    fun `short page ends paging`() {
        assertThat(CursorPager.next(PageCursor(0), listOf(1L, 2L), limit = 3)).isNull()
        assertThat(CursorPager.next(PageCursor(0), emptyList(), limit = 3)).isNull()
    }

    @Test
    fun `full page advances to last timestamp skipping rows already seen at it`() {
        assertThat(CursorPager.next(PageCursor(0), listOf(1L, 2L, 5L), limit = 3)).isEqualTo(PageCursor(5, 1))
        assertThat(CursorPager.next(PageCursor(0), listOf(1L, 5L, 5L), limit = 3)).isEqualTo(PageCursor(5, 2))
    }

    @Test
    fun `page made of a single timestamp plateau advances the offset`() {
        val first = CursorPager.next(PageCursor(0), listOf(7L, 7L, 7L), limit = 3)!!
        assertThat(first).isEqualTo(PageCursor(7, 3))
        val second = CursorPager.next(first, listOf(7L, 7L, 7L), limit = 3)!!
        assertThat(second).isEqualTo(PageCursor(7, 6))
        assertThat(CursorPager.next(second, listOf(7L, 9L, 9L), limit = 3)).isEqualTo(PageCursor(9, 2))
    }

    /** Simulates a server with many rows sharing timestamps and checks every row is visited exactly once. */
    @Test
    fun `simulated paging visits every row exactly once`() {
        val rows = listOf(1L, 1L, 1L, 1L, 2L, 3L, 3L, 3L, 3L, 3L, 3L, 4L, 5L, 5L).mapIndexed { i, ts -> ts to "id$i" }
        val limit = 3
        val seen = mutableListOf<String>()
        var cursor = PageCursor(0)
        var persisted = 0L
        while (true) {
            val page = rows.filter { it.first >= cursor.sinceUs }.drop(cursor.offset).take(limit)
            seen += page.map { it.second }
            val timestamps = page.map { it.first }
            persisted = CursorPager.persisted(cursor, timestamps)
            cursor = CursorPager.next(cursor, timestamps, limit) ?: break
        }
        assertThat(seen).containsExactlyElementsIn(rows.map { it.second }).inOrder()
        assertThat(persisted).isEqualTo(5L)
    }

    @Test
    fun `timestamps round trip with microsecond precision`() {
        val us = CursorPager.parseTimestampUs("2026-09-26T10:11:12.123456+00:00")
        assertThat(CursorPager.formatTimestampUs(us)).isEqualTo("2026-09-26T10:11:12.123456Z")
        assertThat(CursorPager.parseTimestampUs("2026-09-26T13:41:12.123456+03:30")).isEqualTo(us)
        assertThat(CursorPager.parseTimestampUs("2026-09-26 10:11:12.123456+00")).isEqualTo(us)
        assertThat(CursorPager.formatTimestampUs(0)).isEqualTo("1970-01-01T00:00:00.000000Z")
    }

    @Test
    fun `media quota keeps upload order and stops at the limit`() {
        val (fit, over) = MediaQuota.fitting(listOf(40L, 30L, 50L, 10L), { it }, usedBytes = 20, quotaBytes = 100)
        assertThat(fit).containsExactly(40L, 30L, 10L).inOrder()
        assertThat(over).containsExactly(50L)
    }
}
