package com.ravango.feature.home

import com.ravango.core.model.Draft
import com.ravango.core.model.Project
import com.ravango.core.model.ProjectTemplate
import com.ravango.core.model.UserAccount
import kotlin.math.ceil

/** Part of the day used for the greeting. Boundaries follow common usage in both Persian and English. */
enum class DayPart { MORNING, AFTERNOON, EVENING, NIGHT }

fun dayPartFor(hourOfDay: Int): DayPart = when (hourOfDay.mod(24)) {
    in 5..11 -> DayPart.MORNING
    in 12..16 -> DayPart.AFTERNOON
    in 17..20 -> DayPart.EVENING
    else -> DayPart.NIGHT
}

/** Friendly first name for the greeting: first word of the display name, else the e-mail's local part. */
fun greetingNameFor(user: UserAccount?): String? {
    if (user == null) return null
    user.displayName?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
    return user.email?.substringBefore('@')?.takeIf { it.isNotBlank() }
}

/** Reading time in whole seconds at [wordsPerMinute] (at least 1 s for non-empty scripts). */
fun readTimeSeconds(words: Int, wordsPerMinute: Int): Int {
    if (words <= 0) return 0
    val wpm = wordsPerMinute.coerceAtLeast(40)
    return ceil(words * 60.0 / wpm).toInt().coerceAtLeast(1)
}

/** Onboarding focus keys → template ids they favour, most relevant first. */
private val FOCUS_TEMPLATES: Map<String, List<String>> = mapOf(
    "reels" to listOf("tpl-reel", "tpl-short"),
    "youtube" to listOf("tpl-youtube", "tpl-short", "tpl-tutorial"),
    "podcast" to listOf("tpl-podcast"),
    "business" to listOf("tpl-linkedin", "tpl-product"),
    "education" to listOf("tpl-tutorial", "tpl-youtube"),
    "vlog" to listOf("tpl-reel", "tpl-youtube"),
)

fun parseFocus(raw: String?): List<String> =
    raw.orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()

/**
 * Moves templates matching the user's focus to the front (in focus order), keeping the built-in order otherwise.
 * Unknown keys are ignored, so the list never loses templates.
 */
fun orderTemplatesByFocus(templates: List<ProjectTemplate>, focus: List<String>): List<ProjectTemplate> {
    if (focus.isEmpty()) return templates
    val preferred = focus.flatMap { FOCUS_TEMPLATES[it].orEmpty() }.distinct()
    if (preferred.isEmpty()) return templates
    val rank = preferred.withIndex().associate { (i, id) -> id to i }
    return templates.withIndex()
        .sortedWith(compareBy({ rank[it.value.id] ?: Int.MAX_VALUE }, { it.index }))
        .map { it.value }
}

/** Offer "continue where you left off" for the draft edited most recently within [windowMs]. */
fun continueCandidate(
    drafts: List<Draft>,
    projects: Map<String, Project>,
    now: Long,
    windowMs: Long = 3L * 24 * 60 * 60 * 1000,
): Pair<Project, Draft>? =
    drafts.asSequence()
        .filter { now - it.updatedAt in 0..windowMs }
        .sortedByDescending { it.updatedAt }
        .mapNotNull { d -> projects[d.projectId]?.takeIf { it.deletedAt == null }?.let { it to d } }
        .firstOrNull()
