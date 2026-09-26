package com.ravango.engine.camera.muxer

/**
 * Decides which segment file each encoded sample goes to. Pure logic (unit tested), used under the muxer lock.
 *
 * - Segment 0 starts at the first video key frame (recording PTS anchor).
 * - When a segment reaches [segmentDurationUs], a key frame is requested; the next key frame starts a new segment
 *   whose local timeline begins at 0 on that key frame.
 * - Audio keeps flowing into the previous segment until its PTS reaches the cut point, so both tracks are cut at
 *   the same instant and no audio sample is lost. The previous segment is then closed.
 * - Non-increasing timestamps are dropped (MediaMuxer rejects them and would abort the track).
 *
 * Results are returned in a reused [Route] object to keep the per-sample path allocation-free.
 */
class SegmentPlanner(
    private val segmentDurationUs: Long,
    hasAudio: Boolean,
    private val audioCloseTimeoutUs: Long = 2_000_000,
) {
    class Route {
        /** Segment index to write to, or -1 to drop the sample. */
        var segment: Int = -1
        var localPtsUs: Long = 0
        /** Segment to open (before writing), or -1. */
        var open: Int = -1
        var openBaseUs: Long = 0
        /** Segment to close (after writing), or -1. */
        var close: Int = -1
        var requestKeyFrame: Boolean = false

        internal fun reset() {
            segment = -1; localPtsUs = 0; open = -1; openBaseUs = 0; close = -1; requestKeyFrame = false
        }
    }

    var hasAudio: Boolean = hasAudio
        private set

    private val route = Route()
    private var currentIndex = -1
    private var currentBaseUs = 0L
    private var previousIndex = -1
    private var previousBaseUs = 0L
    private var cutPtsUs = 0L
    private var keyFrameRequested = false
    private var lastVideoPtsUs = Long.MIN_VALUE
    private var lastAudioPtsUs = Long.MIN_VALUE

    val currentSegment: Int get() = currentIndex
    val currentSegmentBaseUs: Long get() = currentBaseUs
    val lastVideoUs: Long get() = lastVideoPtsUs

    /** Audio disappeared (e.g. the mic failed): stop waiting for it at cut points. */
    fun disableAudio(): Route {
        route.reset()
        hasAudio = false
        if (previousIndex >= 0) {
            route.close = previousIndex
            previousIndex = -1
        }
        return route
    }

    fun routeVideo(ptsUs: Long, keyFrame: Boolean): Route {
        route.reset()
        if (ptsUs <= lastVideoPtsUs) return route
        if (currentIndex < 0) {
            if (!keyFrame) return route
            currentIndex = 0
            currentBaseUs = ptsUs
            route.open = 0
            route.openBaseUs = ptsUs
        } else if (keyFrame && ptsUs - currentBaseUs >= segmentDurationUs) {
            if (previousIndex >= 0) route.close = previousIndex // audio never caught up: close it now
            previousIndex = currentIndex
            previousBaseUs = currentBaseUs
            cutPtsUs = ptsUs
            currentIndex += 1
            currentBaseUs = ptsUs
            keyFrameRequested = false
            route.open = currentIndex
            route.openBaseUs = ptsUs
            if (!hasAudio) {
                route.close = previousIndex
                previousIndex = -1
            }
        } else {
            if (!keyFrameRequested && ptsUs - currentBaseUs >= segmentDurationUs) {
                keyFrameRequested = true
                route.requestKeyFrame = true
            }
            if (previousIndex >= 0 && ptsUs - cutPtsUs > audioCloseTimeoutUs) {
                route.close = previousIndex
                previousIndex = -1
            }
        }
        lastVideoPtsUs = ptsUs
        route.segment = currentIndex
        route.localPtsUs = ptsUs - currentBaseUs
        return route
    }

    fun routeAudio(ptsUs: Long): Route {
        route.reset()
        if (currentIndex < 0 || ptsUs <= lastAudioPtsUs) return route
        if (previousIndex >= 0) {
            if (ptsUs < cutPtsUs) {
                lastAudioPtsUs = ptsUs
                route.segment = previousIndex
                route.localPtsUs = ptsUs - previousBaseUs
                return route
            }
            route.close = previousIndex
            previousIndex = -1
        }
        if (ptsUs < currentBaseUs) return route // captured before the anchor frame
        lastAudioPtsUs = ptsUs
        route.segment = currentIndex
        route.localPtsUs = ptsUs - currentBaseUs
        return route
    }

    /** Segments still open, in the order they must be closed. */
    fun openSegments(): List<Int> = buildList {
        if (previousIndex >= 0) add(previousIndex)
        if (currentIndex >= 0) add(currentIndex)
    }
}
