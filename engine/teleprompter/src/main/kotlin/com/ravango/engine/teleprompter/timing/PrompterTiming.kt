package com.ravango.engine.teleprompter.timing

import com.ravango.core.model.TeleprompterSettings
import com.ravango.engine.teleprompter.markup.ParsedScript
import kotlin.math.roundToLong

/** Reading-time math shared by the engine, the script library and the editor. */
object PrompterTiming {
    /** Time a `[pause]` cue adds to the reading. */
    const val PAUSE_MS: Long = 1_200L

    fun clampWpm(wpm: Int): Int = wpm.coerceIn(TeleprompterSettings.MIN_WPM, TeleprompterSettings.MAX_WPM)

    /** Duration of [words] spoken words plus [pauses] pause cues at [wpm]. */
    fun durationMs(words: Int, pauses: Int, wpm: Int): Long {
        if (words <= 0 && pauses <= 0) return 0
        val perWordMs = 60_000.0 / clampWpm(wpm)
        return (words * perWordMs).roundToLong() + pauses * PAUSE_MS
    }

    fun totalMs(script: ParsedScript, wpm: Int): Long = durationMs(script.totalWords, script.totalPauses, wpm)

    /** Remaining reading time from display offset [displayOffset] (inclusive) to the end. */
    fun remainingMs(script: ParsedScript, displayOffset: Int, wpm: Int): Long {
        val w = script.words
        return durationMs(w.totalWords - w.wordsBefore(displayOffset), w.totalPauses - w.pausesBefore(displayOffset), wpm)
    }

    /** Reading time up to [displayOffset]. */
    fun elapsedAtMs(script: ParsedScript, displayOffset: Int, wpm: Int): Long {
        val w = script.words
        return durationMs(w.wordsBefore(displayOffset), w.pausesBefore(displayOffset), wpm)
    }
}

/**
 * Section navigation over sorted positions (display offsets or line numbers — any monotonic coordinate).
 */
object SectionNavigator {
    /** Index of the section containing [position], or -1 when before the first section. */
    fun currentIndex(positions: List<Int>, position: Int): Int {
        var result = -1
        for (i in positions.indices) if (positions[i] <= position) result = i else break
        return result
    }

    /** The next section strictly after [position], or null. */
    fun nextIndex(positions: List<Int>, position: Int): Int? = positions.indexOfFirst { it > position }.takeIf { it >= 0 }

    /**
     * "Previous" behaves like a media player: when inside a section (past its start) it returns to that
     * section's start; when already at a section start it goes to the one before. Null means "the very beginning".
     */
    fun previousIndex(positions: List<Int>, position: Int): Int? {
        val current = currentIndex(positions, position)
        if (current < 0) return null
        if (position > positions[current]) return current
        return (current - 1).takeIf { it >= 0 }
    }
}
