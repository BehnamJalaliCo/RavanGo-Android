package com.ravango.engine.ai.video

/** Normalization used to compare spoken words across Persian/Arabic spelling variants and punctuation. */
internal object TextNormalizer {
    private val DIACRITICS = Regex("[\u064B-\u065F\u0670\u06D6-\u06ED]")
    private val PUNCT = Regex("[\\p{P}\\p{S}&&[^'\\-]]|[«»“”\"]")

    fun word(raw: String): String = raw
        .replace(DIACRITICS, "")
        .replace('\u064A', 'ی') // Arabic yeh
        .replace('\u0649', 'ی') // alef maksura
        .replace('\u0643', 'ک') // Arabic kaf
        .replace('\u0629', 'ه')
        .replace("\u200C", "")
        .replace("\u200F", "")
        .replace("\u200E", "")
        .replace(PUNCT, "")
        .trim('-', '—', '–', '\'', ' ')
        .lowercase()

    fun phrase(raw: String): String = raw.split(Regex("\\s+")).map { word(it) }.filter { it.isNotEmpty() }.joinToString(" ")
}
