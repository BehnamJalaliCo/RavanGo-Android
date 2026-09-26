package com.ravango.engine.camera.recorder

/**
 * Shared recording timeline for audio and video (System.nanoTime base).
 *
 * The first video frame sent to the encoder anchors PTS 0. Pauses are recorded as intervals; every later
 * timestamp is shifted back by the total paused time so the file has no gap. Samples captured before the anchor
 * or inside a pause are rejected (-1). Thread-safe (called from the GL and audio threads); allocation-free per call.
 */
class PtsClock {
    private var anchorNs = NOT_SET
    private val pauseStarts = LongArray(MAX_PAUSES)
    private val pauseEnds = LongArray(MAX_PAUSES)
    private var pauseCount = 0
    private var pausedOpen = false

    @get:Synchronized
    val isAnchored: Boolean get() = anchorNs != NOT_SET

    @get:Synchronized
    val isPaused: Boolean get() = pausedOpen

    /** Sets the anchor if not set yet. Returns the anchor in use. */
    @Synchronized
    fun anchor(rawNs: Long): Long {
        if (anchorNs == NOT_SET) anchorNs = rawNs
        return anchorNs
    }

    @Synchronized
    fun pause(nowNs: Long) {
        if (pausedOpen) return
        if (pauseCount == MAX_PAUSES) {
            // Merge the two oldest pauses' accounting into one to stay bounded (never happens in practice).
            compact()
        }
        pauseStarts[pauseCount] = nowNs
        pauseEnds[pauseCount] = Long.MAX_VALUE
        pauseCount++
        pausedOpen = true
    }

    @Synchronized
    fun resume(nowNs: Long) {
        if (!pausedOpen) return
        pauseEnds[pauseCount - 1] = maxOf(nowNs, pauseStarts[pauseCount - 1])
        pausedOpen = false
    }

    /** Total paused nanoseconds that end at or before [rawNs]. */
    @Synchronized
    fun pausedBefore(rawNs: Long): Long {
        var total = 0L
        for (i in 0 until pauseCount) {
            if (pauseEnds[i] != Long.MAX_VALUE && pauseEnds[i] <= rawNs) total += pauseEnds[i] - pauseStarts[i]
        }
        return total
    }

    /** Maps a raw capture timestamp to recording time in nanoseconds, or -1 when it must be dropped. */
    @Synchronized
    fun mapNs(rawNs: Long): Long {
        if (anchorNs == NOT_SET || rawNs < anchorNs) return -1
        var shift = 0L
        for (i in 0 until pauseCount) {
            val start = pauseStarts[i]
            val end = pauseEnds[i]
            if (rawNs >= start && rawNs < end) return -1
            if (end <= rawNs) shift += end - start
        }
        return rawNs - anchorNs - shift
    }

    fun mapUs(rawNs: Long): Long {
        val ns = mapNs(rawNs)
        return if (ns < 0) -1 else ns / 1000
    }

    private fun compact() {
        // Keep the accounting exact: fold pause 0 and 1 into a single interval-length record by shifting the anchor.
        val len0 = pauseEnds[0] - pauseStarts[0]
        anchorNs += len0
        for (i in 1 until pauseCount) {
            pauseStarts[i - 1] = pauseStarts[i]
            pauseEnds[i - 1] = pauseEnds[i]
        }
        pauseCount--
    }

    companion object {
        private const val NOT_SET = Long.MIN_VALUE
        private const val MAX_PAUSES = 256
    }
}
