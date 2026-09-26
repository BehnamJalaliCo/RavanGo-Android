package com.ravango.engine.ai.json

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Robust extraction of JSON from model output: tolerates ```json fences, leading/trailing prose, smart quotes around
 * the payload and trailing commas. Returns null when nothing parseable is found (callers then fail with
 * [com.ravango.engine.ai.api.AiErrors.INVALID_OUTPUT] rather than guessing).
 */
object LlmJson {

    @OptIn(ExperimentalSerializationApi::class)
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        allowTrailingComma = true
    }

    private val FENCE = Regex("""```(?:json|JSON)?\s*\n?(.*?)```""", RegexOption.DOT_MATCHES_ALL)

    /** The most plausible JSON object/array substring of [text], or null. */
    fun extract(text: String): String? {
        val candidates = buildList {
            FENCE.findAll(text).forEach { add(it.groupValues[1].trim()) }
            add(text.trim())
        }
        for (c in candidates) {
            balanced(c)?.let { return it }
        }
        return null
    }

    fun parseElement(text: String): JsonElement? {
        val raw = extract(text) ?: return null
        return runCatching { json.parseToJsonElement(raw) }.getOrNull()
    }

    fun <T> decode(text: String, strategy: DeserializationStrategy<T>): T? {
        val raw = extract(text) ?: return null
        return runCatching { json.decodeFromString(strategy, raw) }.getOrNull()
    }

    /** First balanced {...} or [...] block, respecting strings and escapes. */
    internal fun balanced(s: String): String? {
        val start = s.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) return null
        val stack = ArrayDeque<Char>()
        var inString = false
        var escaped = false
        for (i in start until s.length) {
            val c = s[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> stack.addLast(c)
                '}', ']' -> {
                    val open = stack.removeLastOrNull() ?: return null
                    if ((open == '{' && c != '}') || (open == '[' && c != ']')) return null
                    if (stack.isEmpty()) return s.substring(start, i + 1)
                }
            }
        }
        return null
    }
}
