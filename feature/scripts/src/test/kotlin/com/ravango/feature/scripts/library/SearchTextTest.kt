package com.ravango.feature.scripts.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SearchTextTest {

    @Test
    fun `persian variants, zwnj and digits are normalised`() {
        val terms = SearchText.terms("كتاب  ميخواهم ۱۲")
        assertThat(SearchText.matchesAll(terms, "عنوان", "من کتاب می‌خواهم 12 تا")).isTrue()
        assertThat(SearchText.matchesAll(SearchText.terms("Hello"), "say HELLO")).isTrue()
        assertThat(SearchText.matchesAll(SearchText.terms("missing"), "say hello")).isFalse()
    }

    @Test
    fun `every term must match in some field`() {
        val terms = SearchText.terms("alpha beta")
        assertThat(SearchText.matchesAll(terms, "alpha", "beta gamma")).isTrue()
        assertThat(SearchText.matchesAll(terms, "alpha", "gamma")).isFalse()
    }

    @Test
    fun `match ranges map back to the original text across ignored characters`() {
        val text = "من می‌خواهم بروم"
        val ranges = SearchText.matchRanges(text, SearchText.terms("میخواهم"))
        assertThat(ranges).hasSize(1)
        assertThat(text.substring(ranges[0].first, ranges[0].last + 1)).isEqualTo("می‌خواهم")
    }

    @Test
    fun `overlapping ranges merge`() {
        val ranges = SearchText.matchRanges("abcabc", listOf("abc", "bca"))
        assertThat(ranges).containsExactly(0..5)
    }

    @Test
    fun `snippet centres on the first match`() {
        val text = "word ".repeat(60) + "needle " + "tail ".repeat(60)
        val snippet = SearchText.snippet(text, SearchText.terms("needle"), maxLength = 60)
        assertThat(snippet).contains("needle")
        assertThat(snippet).startsWith("…")
        assertThat(snippet).endsWith("…")
    }
}
