package com.ravango.engine.editor.composition

import com.ravango.core.model.EditorDocument
import com.ravango.core.model.OverlayAnimation
import com.ravango.core.model.OverlayItem
import com.ravango.core.model.SubtitleAnimation
import com.ravango.core.model.SubtitleCue
import com.ravango.core.model.TransitionType
import com.ravango.engine.editor.timeline.TimelineMath
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/*
 * Pure time mapping for everything that animates on the output timeline: transitions, clip fades, overlay
 * in/out animations and subtitle animation states. Effects evaluate these per frame with the frame's
 * presentation time (composition-level effects receive output-timeline timestamps).
 */

/** A time window on the output timeline during which a visual transition is active. */
data class TransitionWindow(
    val type: TransitionType,
    val startUs: Long,
    val endUs: Long,
    /** The cut point between the two clips (the peak of the effect). For fades this equals the clip edge. */
    val peakUs: Long,
) {
    val durationUs: Long get() = endUs - startUs
}

/** Evaluated transition at a frame. [amount] is 0 at the window edges and 1 at the cut. */
data class TransitionState(val type: TransitionType, val amount: Float, val incoming: Boolean)

object TransitionTiming {
    /** Transitions never take more than this fraction of either neighbouring clip. */
    private const val MAX_CLIP_FRACTION = 0.45

    fun windows(doc: EditorDocument): List<TransitionWindow> {
        val placements = TimelineMath.placements(doc)
        val out = ArrayList<TransitionWindow>()
        placements.forEachIndexed { i, p ->
            val c = p.clip
            // Clip video fades are "fade to black" windows anchored at the clip edges.
            if (c.videoFadeInUs > 0) {
                val d = minOf(c.videoFadeInUs, p.durationUs)
                out += TransitionWindow(TransitionType.FADE_BLACK, p.startUs, p.startUs + d, p.startUs)
            }
            if (c.videoFadeOutUs > 0) {
                val d = minOf(c.videoFadeOutUs, p.durationUs)
                out += TransitionWindow(TransitionType.FADE_BLACK, p.endUs - d, p.endUs, p.endUs)
            }
            val next = placements.getOrNull(i + 1) ?: return@forEachIndexed
            val t = c.transitionOut
            if (t.type == TransitionType.NONE || t.durationUs <= 0) return@forEachIndexed
            val half = minOf(t.durationUs / 2, (p.durationUs * MAX_CLIP_FRACTION).toLong(), (next.durationUs * MAX_CLIP_FRACTION).toLong())
            if (half <= 0) return@forEachIndexed
            out += TransitionWindow(t.type, p.endUs - half, p.endUs + half, p.endUs)
        }
        return out.sortedBy { it.startUs }
    }

    /** State at [timeUs] or null when no window is active. When windows overlap the strongest one wins. */
    fun stateAt(windows: List<TransitionWindow>, timeUs: Long): TransitionState? {
        var best: TransitionState? = null
        for (w in windows) {
            if (w.startUs > timeUs) break
            if (timeUs >= w.endUs) continue
            val s = evaluate(w, timeUs)
            if (best == null || s.amount > best.amount) best = s
        }
        return best
    }

    fun evaluate(w: TransitionWindow, timeUs: Long): TransitionState {
        val incoming = timeUs >= w.peakUs
        val amount = when {
            // Fades anchored at the start of a clip (peak == start): 1 → 0.
            w.peakUs <= w.startUs -> 1f - ((timeUs - w.startUs).toFloat() / w.durationUs.coerceAtLeast(1))
            // Anchored at the end: 0 → 1.
            w.peakUs >= w.endUs -> (timeUs - w.startUs).toFloat() / w.durationUs.coerceAtLeast(1)
            !incoming -> (timeUs - w.startUs).toFloat() / (w.peakUs - w.startUs).coerceAtLeast(1)
            else -> 1f - (timeUs - w.peakUs).toFloat() / (w.endUs - w.peakUs).coerceAtLeast(1)
        }.coerceIn(0f, 1f)
        return TransitionState(w.type, Easing.smooth(amount), incoming)
    }
}

object Easing {
    fun smooth(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3f - 2f * t) }
    fun outCubic(x: Float): Float { val t = 1f - x.coerceIn(0f, 1f); return 1f - t * t * t }
    fun inCubic(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * t }

    /** Overshooting ease for "pop". */
    fun outBack(x: Float): Float {
        val t = x.coerceIn(0f, 1f) - 1f
        val c = 1.70158f
        return 1f + (c + 1f) * t * t * t + c * t * t
    }

    /** Damped bounce settling at 1. */
    fun outBounce(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return (1f - exp(-6f * t) * cos(t * 3f * PI.toFloat())).coerceIn(0f, 1.2f)
    }
}

/** Visual modifiers for an overlay at one frame. Offsets are fractions of the canvas size (y grows downwards). */
data class OverlayAnimState(
    val visible: Boolean,
    val alpha: Float = 1f,
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val rotationDegrees: Float = 0f,
    /** 0..1 fraction of characters revealed (typewriter). */
    val reveal: Float = 1f,
) {
    companion object {
        val Hidden = OverlayAnimState(visible = false, alpha = 0f)
    }
}

object OverlayTiming {
    const val ANIMATION_US = 350_000L

    fun state(item: OverlayItem, timeUs: Long): OverlayAnimState = state(item.startUs, item.endUs, item.animationIn, item.animationOut, timeUs)

    fun state(startUs: Long, endUs: Long, animIn: OverlayAnimation, animOut: OverlayAnimation, timeUs: Long): OverlayAnimState {
        if (timeUs < startUs || timeUs >= endUs) return OverlayAnimState.Hidden
        val length = endUs - startUs
        val animLen = minOf(ANIMATION_US, length / 2).coerceAtLeast(1)
        val inP = ((timeUs - startUs).toFloat() / animLen).coerceIn(0f, 1f)
        val outP = ((endUs - timeUs).toFloat() / animLen).coerceIn(0f, 1f)
        var s = OverlayAnimState(visible = true)
        if (inP < 1f) s = apply(s, animIn, inP, entering = true)
        if (outP < 1f) s = apply(s, animOut, outP, entering = false)
        if (animIn == OverlayAnimation.TYPEWRITER) {
            // Typewriter reveals over up to 60% of the item (max 2 s).
            val revealLen = minOf(length * 6 / 10, 2_000_000L).coerceAtLeast(1)
            s = s.copy(reveal = ((timeUs - startUs).toFloat() / revealLen).coerceIn(0f, 1f))
        }
        return s
    }

    /** [p] goes 0 → 1 as the item becomes fully present (for both entering and leaving). */
    private fun apply(s: OverlayAnimState, anim: OverlayAnimation, p: Float, entering: Boolean): OverlayAnimState = when (anim) {
        OverlayAnimation.NONE, OverlayAnimation.TYPEWRITER -> s
        OverlayAnimation.FADE -> s.copy(alpha = s.alpha * Easing.smooth(p))
        OverlayAnimation.POP -> s.copy(alpha = s.alpha * Easing.smooth(minOf(1f, p * 2f)), scale = s.scale * (if (entering) Easing.outBack(p) else 0.6f + 0.4f * Easing.smooth(p)).coerceAtLeast(0.01f))
        OverlayAnimation.SLIDE_UP -> s.copy(alpha = s.alpha * Easing.smooth(p), offsetY = s.offsetY + (if (entering) 0.08f else -0.08f) * (1f - Easing.outCubic(p)))
        OverlayAnimation.SLIDE_DOWN -> s.copy(alpha = s.alpha * Easing.smooth(p), offsetY = s.offsetY + (if (entering) -0.08f else 0.08f) * (1f - Easing.outCubic(p)))
        OverlayAnimation.BOUNCE -> s.copy(alpha = s.alpha * Easing.smooth(minOf(1f, p * 3f)), offsetY = s.offsetY + 0.06f * (1f - Easing.outBounce(p)))
        OverlayAnimation.ZOOM -> s.copy(alpha = s.alpha * Easing.smooth(p), scale = s.scale * (if (entering) 1.6f - 0.6f * Easing.outCubic(p) else 0.4f + 0.6f * Easing.smooth(p)))
    }
}

/** Subtitle rendering state at one frame. */
data class SubtitleFrameState(
    val cue: SubtitleCue,
    /** Index of the word being spoken (karaoke), -1 when none. */
    val activeWord: Int,
    /** Number of words visible (word-by-word). */
    val visibleWords: Int,
    val alpha: Float,
    val scale: Float,
    val offsetY: Float,
)

object SubtitleTiming {
    const val ANIMATION_US = 180_000L

    fun cueAt(cues: List<SubtitleCue>, timeUs: Long): SubtitleCue? {
        // Cues are sorted by start; binary search the last cue starting at/before t.
        var lo = 0
        var hi = cues.lastIndex
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startUs <= timeUs) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        var i = found
        while (i >= 0) {
            val c = cues[i]
            if (timeUs < c.endUs) return c
            // Overlapping cues are rare; look back a little.
            if (found - i > 3) break
            i--
        }
        return null
    }

    /** Word timings for [cue], synthesised evenly from the text when the cue has none. */
    fun words(cue: SubtitleCue): List<com.ravango.core.model.WordTiming> {
        if (cue.words.isNotEmpty()) return cue.words
        val tokens = cue.text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        // Distribute by character count so long words get more time.
        val total = tokens.sumOf { it.length }.coerceAtLeast(1)
        var t = cue.startUs
        val span = cue.endUs - cue.startUs
        return tokens.map { w ->
            val d = span * w.length / total
            com.ravango.core.model.WordTiming(w, t, t + d).also { t += d }
        }
    }

    fun state(cue: SubtitleCue, animation: SubtitleAnimation, timeUs: Long): SubtitleFrameState {
        val words = words(cue)
        val active = words.indexOfLast { it.startUs <= timeUs }
        val visible = if (animation == SubtitleAnimation.WORD_BY_WORD) (active + 1).coerceIn(1, words.size.coerceAtLeast(1)) else words.size
        val inP = ((timeUs - cue.startUs).toFloat() / ANIMATION_US).coerceIn(0f, 1f)
        val outP = ((cue.endUs - timeUs).toFloat() / ANIMATION_US).coerceIn(0f, 1f)
        var alpha = 1f
        var scale = 1f
        var offsetY = 0f
        when (animation) {
            SubtitleAnimation.FADE, SubtitleAnimation.KARAOKE, SubtitleAnimation.WORD_BY_WORD -> alpha = Easing.smooth(minOf(inP, outP))
            SubtitleAnimation.POP -> {
                alpha = Easing.smooth(minOf(inP * 2f, outP, 1f))
                scale = if (inP < 1f) 0.7f + 0.3f * Easing.outBack(inP) else 1f
            }
            SubtitleAnimation.SLIDE_UP -> {
                alpha = Easing.smooth(minOf(inP, outP))
                offsetY = 0.04f * (1f - Easing.outCubic(inP))
            }
            SubtitleAnimation.NONE -> Unit
        }
        return SubtitleFrameState(cue, active, visible, alpha, scale, offsetY)
    }
}

/** Small math helpers shared by effects. */
internal object Geometry {
    fun rotate(x: Float, y: Float, degrees: Float): Pair<Float, Float> {
        val r = Math.toRadians(degrees.toDouble())
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        return (x * c - y * s) to (x * s + y * c)
    }

    fun approxEquals(a: Float, b: Float, eps: Float = 1e-4f) = abs(a - b) <= eps
}
