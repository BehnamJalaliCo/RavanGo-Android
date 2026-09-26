package com.ravango.engine.ai.video

import com.ravango.core.media.dsp.TimeRange
import com.ravango.engine.ai.api.ShortSuggestion
import com.ravango.engine.ai.json.LlmJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** Parsing + validation of the JSON the LLM returns for highlight scoring and shorts proposals. */
object VideoLlmParsing {

    @Serializable
    data class LlmHighlight(val index: Int = -1, val score: Double = 0.0, val title: String = "", val reason: String = "")

    @Serializable
    private data class HighlightsEnvelope(val highlights: List<LlmHighlight> = emptyList())

    /** Scores by unit index (0..1), with titles/reasons. Invalid indices are dropped. */
    fun parseHighlights(text: String, unitCount: Int): List<LlmHighlight>? {
        val element = LlmJson.parseElement(text) ?: return null
        val list = when (element) {
            is JsonObject -> runCatching { LlmJson.json.decodeFromJsonElement(HighlightsEnvelope.serializer(), element).highlights }.getOrNull()
            is JsonArray -> runCatching { element.map { LlmJson.json.decodeFromJsonElement(LlmHighlight.serializer(), it) } }.getOrNull()
            else -> null
        } ?: return null
        return list
            .filter { it.index in 0 until unitCount }
            .map { it.copy(score = normalizeScore(it.score).toDouble(), title = it.title.trim(), reason = it.reason.trim()) }
            .distinctBy { it.index }
    }

    /**
     * Shorts: `{"shorts":[{"title","hook","caption","score","units":[[start,end],…]}]}` where units are inclusive
     * index ranges of [units]. Ranges are validated (bounds, order, no overlap), trimmed to at most
     * [maxDurationUs] (dropping trailing parts), and proposals shorter than [minDurationUs] are rejected.
     */
    fun parseShorts(text: String, units: List<TranscriptUnit>, maxDurationUs: Long, minDurationUs: Long = 5_000_000): List<ShortSuggestion>? {
        val element = LlmJson.parseElement(text) ?: return null
        val array = when (element) {
            is JsonObject -> element["shorts"] as? JsonArray
            is JsonArray -> element
            else -> null
        } ?: return null
        val out = mutableListOf<ShortSuggestion>()
        for (item in array) {
            val obj = item as? JsonObject ?: continue
            val pairs = (obj["units"] as? JsonArray ?: obj["segments"] as? JsonArray ?: continue).mapNotNull { r -> indexPair(r) }
            val ranges = mutableListOf<TimeRange>()
            var total = 0L
            for ((a, b) in pairs) {
                if (a !in units.indices || b !in units.indices) continue
                val (s, e) = if (a <= b) a to b else b to a
                var range = TimeRange(units[s].startUs, units[e].endUs)
                if (ranges.any { it.startUs < range.endUs && range.startUs < it.endUs }) continue
                if (total + range.durationUs > maxDurationUs) {
                    // Keep whole units that still fit.
                    var endIdx = e
                    while (endIdx >= s && total + (units[endIdx].endUs - units[s].startUs) > maxDurationUs) endIdx--
                    if (endIdx < s) break
                    range = TimeRange(units[s].startUs, units[endIdx].endUs)
                }
                ranges += range
                total += range.durationUs
                if (total >= maxDurationUs) break
            }
            if (ranges.isEmpty() || total < minDurationUs) continue
            out += ShortSuggestion(
                title = obj.string("title"),
                hook = obj.string("hook"),
                ranges = mergeAdjacent(ranges),
                score = normalizeScore((obj["score"] as? JsonPrimitive)?.doubleOrNull ?: 0.5),
                caption = obj.string("caption"),
            )
        }
        return out
    }

    private fun indexPair(e: kotlinx.serialization.json.JsonElement): Pair<Int, Int>? = when (e) {
        is JsonArray -> {
            val a = (e.getOrNull(0) as? JsonPrimitive)?.intOrNull
            val b = (e.getOrNull(1) as? JsonPrimitive)?.intOrNull ?: a
            if (a != null && b != null) a to b else null
        }
        is JsonPrimitive -> e.intOrNull?.let { it to it }
        is JsonObject -> {
            val a = (e["start"] as? JsonPrimitive)?.intOrNull
            val b = (e["end"] as? JsonPrimitive)?.intOrNull ?: a
            if (a != null && b != null) a to b else null
        }
    }

    /** Joins consecutive ranges that touch (keeps hook-first ordering otherwise). */
    private fun mergeAdjacent(ranges: List<TimeRange>): List<TimeRange> {
        val out = mutableListOf<TimeRange>()
        for (r in ranges) {
            val last = out.lastOrNull()
            if (last != null && r.startUs >= last.endUs && r.startUs - last.endUs < 300_000) {
                out[out.lastIndex] = TimeRange(last.startUs, r.endUs)
            } else {
                out += r
            }
        }
        return out
    }

    /** Accepts 0..1, 0..10 or 0..100 scales. */
    fun normalizeScore(v: Double): Float = when {
        v.isNaN() || v < 0 -> 0f
        v <= 1.0 -> v.toFloat()
        v <= 10.0 -> (v / 10).toFloat()
        else -> (v / 100).coerceAtMost(1.0).toFloat()
    }

    private fun JsonObject.string(name: String): String = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
}
