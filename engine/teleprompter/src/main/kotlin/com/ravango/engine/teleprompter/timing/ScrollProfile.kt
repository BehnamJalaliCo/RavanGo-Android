package com.ravango.engine.teleprompter.timing

import kotlin.math.abs
import kotlin.math.max

/**
 * Converts words-per-minute into a scroll velocity using the measured line geometry.
 *
 * Each laid-out line has a reading duration derived from the words (and pause cues) that start on it. The
 * velocity at the reading line is `height / duration`, averaged with a small triangular kernel over neighbouring
 * lines so short lines (e.g. the last word of a paragraph) do not cause visible speed jumps. Lines with no spoken
 * content (blank lines, director notes) scroll at twice the script's average speed so they neither stall nor jump.
 *
 * All values are pixels in text-layout coordinates; the profile is independent of font size because it is
 * rebuilt from the measured layout.
 */
class ScrollProfile(
    private val tops: FloatArray,
    private val bottoms: FloatArray,
    private val words: IntArray,
    private val pauses: IntArray,
    /** Display offset of the first character of each line. */
    val lineStarts: IntArray,
) {
    init {
        require(tops.size == bottoms.size && tops.size == words.size && tops.size == pauses.size && tops.size == lineStarts.size)
    }

    val lineCount: Int get() = tops.size
    val isEmpty: Boolean get() = tops.isEmpty()

    fun top(line: Int): Float = tops[line]
    fun bottom(line: Int): Float = bottoms[line]
    fun height(line: Int): Float = max(1f, bottoms[line] - tops[line])
    fun center(line: Int): Float = (tops[line] + bottoms[line]) / 2f

    /** Reading position of the first line (its center sits on the eye line). */
    val startY: Float get() = if (isEmpty) 0f else center(0)

    /** Reading position of the last line. */
    val endY: Float get() = if (isEmpty) 0f else center(lineCount - 1)

    private val contentLines: Int = words.indices.count { words[it] > 0 || pauses[it] > 0 }
    private val contentHeight: Float = words.indices.sumOf { if (words[it] > 0 || pauses[it] > 0) height(it).toDouble() else 0.0 }.toFloat()
    private val totalWords: Int = words.sum()
    private val totalPauses: Int = pauses.sum()

    /** Line whose vertical extent contains [y] (clamped to the text). */
    fun lineAt(y: Float): Int {
        if (isEmpty) return 0
        var lo = 0
        var hi = lineCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (tops[mid] <= y) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Fractional position within [line] for [y], 0 at the top, 1 at the bottom. */
    fun fractionIn(line: Int, y: Float): Float = ((y - tops[line]) / height(line)).coerceIn(0f, 1f)

    /** Average seconds per pixel for the spoken content of the script at [wpm]. */
    fun averageSecondsPerPixel(wpm: Int): Float {
        if (isEmpty) return 0.02f
        val avgLine = if (lineCount > 0) (bottoms[lineCount - 1] - tops[0]) / lineCount else 1f
        if (contentLines == 0 || contentHeight <= 0f) return 2f / max(1f, avgLine) // 2 s per line
        val seconds = PrompterTiming.durationMs(totalWords, totalPauses, wpm) / 1000f
        return max(1e-4f, seconds / contentHeight)
    }

    /** Seconds per pixel of [line] at [wpm], clamped around the average for smoothness. */
    fun secondsPerPixel(line: Int, wpm: Int, average: Float = averageSecondsPerPixel(wpm)): Float {
        val w = words[line]
        val p = pauses[line]
        if (w <= 0 && p <= 0) return average * EMPTY_LINE_FACTOR
        val spp = PrompterTiming.durationMs(w, p, wpm) / 1000f / height(line)
        return spp.coerceIn(average * MIN_FACTOR, average * MAX_FACTOR)
    }

    /** Target scroll velocity (px/s) with the reading line at [y]. */
    fun velocityAt(y: Float, wpm: Int): Float {
        if (isEmpty) return 0f
        val average = averageSecondsPerPixel(wpm)
        val line = lineAt(y)
        val position = line + fractionIn(line, y) // fractional line coordinate
        var weighted = 0f
        var weights = 0f
        for (i in (line - KERNEL_RADIUS).coerceAtLeast(0)..(line + KERNEL_RADIUS).coerceAtMost(lineCount - 1)) {
            val distance = abs(i + 0.5f - position)
            val weight = (KERNEL_RADIUS + 0.5f - distance).coerceAtLeast(0f)
            if (weight <= 0f) continue
            weighted += weight * secondsPerPixel(i, wpm, average)
            weights += weight
        }
        val spp = if (weights > 0f) weighted / weights else average
        return 1f / spp
    }

    companion object {
        const val KERNEL_RADIUS = 2
        const val EMPTY_LINE_FACTOR = 0.5f
        const val MIN_FACTOR = 0.4f
        const val MAX_FACTOR = 2.5f
    }
}
