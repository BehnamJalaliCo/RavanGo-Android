package com.ravango.feature.home.common

/**
 * Built-in template outlines are authored in English with `## Section` markers. These helpers extract the sections
 * and rewrite them in the user's language while keeping the teleprompter markup intact.
 */

/** Section titles in order, without the `##` marker. */
fun outlineHeadings(outline: String): List<String> =
    outline.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("##") }
        .map { it.removePrefix("##").trim() }
        .filter { it.isNotEmpty() }
        .toList()

/** Rewrites every `## Heading` line through [translate]; unknown headings and body lines are kept verbatim. */
fun localizeOutline(outline: String, translate: (String) -> String?): String =
    outline.lines().joinToString("\n") { line ->
        val trimmed = line.trim()
        if (trimmed.startsWith("##")) {
            val heading = trimmed.removePrefix("##").trim()
            if (heading.isEmpty()) line else "## ${translate(heading) ?: heading}"
        } else {
            line
        }
    }

/** A recognised built-in section heading. */
sealed interface OutlineHeading {
    data class Fixed(val id: String) : OutlineHeading
    data class MainPoint(val number: Int) : OutlineHeading
    data class Step(val number: Int) : OutlineHeading
}

private val FIXED_HEADINGS = mapOf(
    "hook" to "hook",
    "hook (3s)" to "hook_3s",
    "value" to "value",
    "call to action" to "cta",
    "intro" to "intro",
    "outro" to "outro",
    "payoff" to "payoff",
    "loop back" to "loop_back",
    "insight" to "insight",
    "story" to "story",
    "takeaway" to "takeaway",
    "problem" to "problem",
    "product" to "product",
    "benefits" to "benefits",
    "offer" to "offer",
    "what you'll learn" to "learn",
    "recap" to "recap",
    "opening" to "opening",
    "topic" to "topic",
    "guest questions" to "guest_questions",
    "closing" to "closing",
)

private val MAIN_POINT = Regex("^main point\\s+(\\d+)$")
private val STEP = Regex("^step\\s+(\\d+)$")

fun parseOutlineHeading(heading: String): OutlineHeading? {
    val key = heading.trim().lowercase()
    FIXED_HEADINGS[key]?.let { return OutlineHeading.Fixed(it) }
    MAIN_POINT.matchEntire(key)?.let { return OutlineHeading.MainPoint(it.groupValues[1].toInt()) }
    STEP.matchEntire(key)?.let { return OutlineHeading.Step(it.groupValues[1].toInt()) }
    return null
}
