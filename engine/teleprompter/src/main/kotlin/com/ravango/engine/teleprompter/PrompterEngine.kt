package com.ravango.engine.teleprompter

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.FloatExponentialDecaySpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.text.TextLayoutResult
import com.ravango.core.model.TeleprompterSettings
import com.ravango.engine.teleprompter.markup.ParsedScript
import com.ravango.engine.teleprompter.markup.ScriptMarkup
import com.ravango.engine.teleprompter.timing.PrompterTiming
import com.ravango.engine.teleprompter.timing.ScrollProfile
import com.ravango.engine.teleprompter.timing.SectionNavigator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp

/**
 * Time-based, frame-accurate teleprompter engine.
 *
 * The reading position ([readingY], text-layout pixels at the eye line) lives in a snapshot float state that is
 * only read inside `graphicsLayer {}` by the renderer, so scrolling never recomposes. Each frame integrates the
 * velocity from [ScrollProfile] (words-per-minute → px/s from the measured layout) with the exact frame delta
 * from `withFrameNanos`, using fractional pixels and a short low-pass on velocity changes (speed nudges,
 * short lines) so motion is perfectly continuous.
 *
 * Public char offsets (snapshot, [jumpToChar], sections) are **source** offsets into the raw script body.
 */
@Stable
internal class PrompterEngine(private val scope: CoroutineScope) : PrompterController {

    private val _snapshot = MutableStateFlow(PrompterSnapshot())
    override val snapshot: StateFlow<PrompterSnapshot> = _snapshot.asStateFlow()

    private val _sections = MutableStateFlow<List<PrompterSection>>(emptyList())
    override val sections: StateFlow<List<PrompterSection>> = _sections.asStateFlow()

    /** Parsed script currently displayed (observed by the renderer). */
    var parsed: ParsedScript by mutableStateOf(ParsedScript.Empty)
        private set

    /** Text-layout Y coordinate that sits on the eye line. Read only in draw/layout lambdas. */
    var readingY: Float by mutableFloatStateOf(0f)
        private set

    private var boundText: String? = null
    private var startSourceOffset = 0
    private var pendingSourceOffset: Int? = 0

    private var layout: TextLayoutResult? = null
    private var layoutText: String? = null
    private var profile: ScrollProfile? = null
    private var viewportHeight = 0f

    private var wpm = TeleprompterSettings().wordsPerMinute
    private var lastSettingsWpm = -1
    private var countdownSeconds = 3
    private var loop = false

    private var phase = PrompterPhase.IDLE
    private var countdownLeftSec = 0.0
    private var elapsedMs = 0.0
    private var velocity = 0f
    private var held = false
    private var resumeAfterHold = false

    private var ticker: Job? = null
    private var motionJob: Job? = null
    private var motionRemaining = 0f
    private var flingJob: Job? = null
    private var lastPublishNanos = 0L

    // ---------------------------------------------------------------- binding

    fun bind(text: String, startCharOffset: Int) {
        if (text != boundText) {
            val previous = boundText
            boundText = text
            if (previous != null && layout != null) {
                // Keep the reading position across edits.
                pendingSourceOffset = currentSourceOffset()
            }
            parsed = ScriptMarkup.parse(text)
            _sections.value = parsed.sections.map { PrompterSection(it.index, it.title.ifBlank { "#${it.index + 1}" }, it.sourceOffset) }
            if (previous == null) {
                startSourceOffset = startCharOffset.coerceIn(0, text.length)
                pendingSourceOffset = startSourceOffset
            }
            publish(force = true)
        }
        if (startCharOffset != startSourceOffset && phase == PrompterPhase.IDLE && boundText == text) {
            startSourceOffset = startCharOffset.coerceIn(0, text.length)
            snapToSource(startSourceOffset)
        }
    }

    fun applySettings(settings: TeleprompterSettings) {
        if (settings.wordsPerMinute != lastSettingsWpm) {
            lastSettingsWpm = settings.wordsPerMinute
            wpm = PrompterTiming.clampWpm(settings.wordsPerMinute)
        }
        countdownSeconds = settings.countdownSeconds.coerceIn(0, 30)
        loop = settings.loop
        publish(force = true)
    }

    fun setViewport(height: Float) {
        viewportHeight = height
    }

    fun onLayout(result: TextLayoutResult) {
        if (result === layout) return
        val text = result.layoutInput.text.text
        val old = layout
        val oldProfile = profile
        // Anchor: the character at the reading line and the fraction within its line.
        var anchorDisplay: Int? = null
        var anchorFraction = 0.5f
        if (old != null && oldProfile != null && !oldProfile.isEmpty && layoutText == text && pendingSourceOffset == null) {
            val line = oldProfile.lineAt(readingY)
            anchorDisplay = oldProfile.lineStarts[line]
            anchorFraction = oldProfile.fractionIn(line, readingY)
        }
        layout = result
        layoutText = text
        profile = buildProfile(result)
        val p = profile ?: return
        if (p.isEmpty) { readingY = 0f; publish(force = true); return }
        val pending = pendingSourceOffset
        if (pending != null) {
            pendingSourceOffset = null
            readingY = yForDisplay(parsed.sourceToDisplay(pending))
        } else if (anchorDisplay != null) {
            val line = result.getLineForOffset(anchorDisplay.coerceIn(0, text.length))
            readingY = p.top(line) + anchorFraction * p.height(line)
        }
        readingY = readingY.coerceIn(p.startY, p.endY)
        publish(force = true)
    }

    private fun buildProfile(result: TextLayoutResult): ScrollProfile {
        val n = result.lineCount
        val tops = FloatArray(n)
        val bottoms = FloatArray(n)
        val words = IntArray(n)
        val pauses = IntArray(n)
        val starts = IntArray(n)
        val index = parsed.words
        val textLength = result.layoutInput.text.length
        for (i in 0 until n) {
            tops[i] = result.getLineTop(i)
            bottoms[i] = result.getLineBottom(i)
            val start = result.getLineStart(i)
            val end = if (i + 1 < n) result.getLineStart(i + 1) else textLength
            starts[i] = start
            if (index.length == textLength) {
                words[i] = index.wordsIn(start, end)
                pauses[i] = index.pausesIn(start, end)
            }
        }
        return ScrollProfile(tops, bottoms, words, pauses, starts)
    }

    private fun yForDisplay(displayOffset: Int): Float {
        val l = layout ?: return 0f
        val p = profile ?: return 0f
        if (p.isEmpty) return 0f
        val line = l.getLineForOffset(displayOffset.coerceIn(0, l.layoutInput.text.length))
        return p.center(line)
    }

    private fun currentDisplayOffset(): Int {
        val p = profile ?: return parsed.sourceToDisplay(pendingSourceOffset ?: startSourceOffset)
        if (p.isEmpty) return 0
        val line = p.lineAt(readingY)
        return p.lineStarts[line]
    }

    private fun currentSourceOffset(): Int = pendingSourceOffset ?: parsed.displayToSource(currentDisplayOffset())

    // ---------------------------------------------------------------- commands

    override fun play() {
        when (phase) {
            PrompterPhase.SCROLLING, PrompterPhase.COUNTDOWN -> return
            PrompterPhase.PAUSED -> phase = PrompterPhase.SCROLLING
            PrompterPhase.FINISHED -> {
                snapToDisplay(0)
                elapsedMs = 0.0
                beginCountdownOrScroll()
            }
            PrompterPhase.IDLE -> beginCountdownOrScroll()
        }
        velocity = 0f
        publish(force = true)
        ensureTicker()
    }

    private fun beginCountdownOrScroll() {
        if (countdownSeconds > 0) {
            phase = PrompterPhase.COUNTDOWN
            countdownLeftSec = countdownSeconds.toDouble()
        } else {
            phase = PrompterPhase.SCROLLING
        }
    }

    override fun pause() {
        phase = when (phase) {
            PrompterPhase.SCROLLING -> PrompterPhase.PAUSED
            PrompterPhase.COUNTDOWN -> if (elapsedMs > 0) PrompterPhase.PAUSED else PrompterPhase.IDLE
            else -> return
        }
        velocity = 0f
        publish(force = true)
    }

    override fun toggle() {
        if (phase == PrompterPhase.SCROLLING || phase == PrompterPhase.COUNTDOWN) pause() else play()
    }

    override fun stop() {
        cancelMotion()
        phase = PrompterPhase.IDLE
        elapsedMs = 0.0
        velocity = 0f
        snapToSource(startSourceOffset)
        publish(force = true)
    }

    override fun restart() {
        cancelMotion()
        phase = PrompterPhase.IDLE
        elapsedMs = 0.0
        velocity = 0f
        snapToDisplay(0)
        play()
    }

    override fun nudgeSpeed(deltaWpm: Int) {
        wpm = PrompterTiming.clampWpm(wpm + deltaWpm)
        publish(force = true)
    }

    override fun scrollBy(viewportFraction: Float) {
        val page = if (viewportHeight > 0f) viewportHeight else 800f
        animateBy(viewportFraction * page)
    }

    override fun jumpToChar(charOffset: Int) {
        val p = profile
        if (p == null || layout == null) {
            pendingSourceOffset = charOffset.coerceAtLeast(0)
            return
        }
        val target = yForDisplay(parsed.sourceToDisplay(charOffset))
        animateTo(target)
    }

    override fun jumpToSection(index: Int) {
        val section = parsed.sections.getOrNull(index) ?: return
        jumpToChar(section.sourceOffset)
    }

    override fun nextSection() {
        val (positions, current) = sectionLinePositions() ?: return
        val next = SectionNavigator.nextIndex(positions, current) ?: return
        jumpToSection(next)
    }

    override fun previousSection() {
        val (positions, current) = sectionLinePositions() ?: return
        val previous = SectionNavigator.previousIndex(positions, current)
        if (previous == null) jumpToChar(0) else jumpToSection(previous)
    }

    /** Section start lines and the current reading line (after any in-flight motion). */
    private fun sectionLinePositions(): Pair<List<Int>, Int>? {
        val l = layout ?: return null
        val p = profile ?: return null
        if (p.isEmpty || parsed.sections.isEmpty()) return null
        val len = l.layoutInput.text.length
        val positions = parsed.sections.map { l.getLineForOffset(it.displayOffset.coerceIn(0, len)) }
        val current = p.lineAt(readingY + motionRemaining)
        return positions to current
    }

    // ---------------------------------------------------------------- gestures (renderer only)

    fun beginDrag() {
        cancelMotion()
        if (!held) {
            held = true
            resumeAfterHold = phase == PrompterPhase.SCROLLING
        }
        velocity = 0f
    }

    /** [contentDy] > 0 means the finger moved the text down (i.e. backwards). */
    fun dragBy(contentDy: Float) {
        moveBy(-contentDy)
        publish()
    }

    fun endDrag(contentVelocity: Float) {
        if (abs(contentVelocity) < 150f) {
            releaseHold()
            return
        }
        flingJob = scope.launch {
            var last = 0f
            try {
                animateDecay(0f, -contentVelocity, FloatExponentialDecaySpec(frictionMultiplier = 1.6f)) { value, _ ->
                    moveBy(value - last)
                    last = value
                    publish()
                }
            } finally {
                releaseHold()
            }
        }
    }

    private fun releaseHold() {
        if (!held) return
        held = false
        resumeAfterHold = false
        velocity = 0f
        publish(force = true)
        ensureTicker()
    }

    // ---------------------------------------------------------------- motion

    private fun moveBy(dy: Float) {
        val p = profile ?: return
        if (p.isEmpty) return
        val next = (readingY + dy).coerceIn(p.startY, p.endY)
        if (phase == PrompterPhase.FINISHED && next < readingY) phase = PrompterPhase.PAUSED
        readingY = next
    }

    private fun animateTo(targetY: Float) {
        cancelMotion()
        animateBy(targetY - readingY)
    }

    private fun animateBy(delta: Float) {
        val p = profile ?: return
        if (p.isEmpty) return
        // Accumulate with any motion still in flight so repeated page presses add up.
        val desired = (readingY + motionRemaining + delta).coerceIn(p.startY, p.endY)
        motionRemaining = desired - readingY
        motionJob?.cancel()
        val total = motionRemaining
        if (abs(total) < 0.5f) { motionRemaining = 0f; return }
        motionJob = scope.launch {
            var last = 0f
            animate(0f, total, animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing)) { value, _ ->
                val d = value - last
                last = value
                motionRemaining -= d
                moveBy(d)
                publish()
            }
            motionRemaining = 0f
            publish(force = true)
        }
    }

    private fun cancelMotion() {
        motionJob?.cancel()
        motionJob = null
        motionRemaining = 0f
        flingJob?.cancel()
        flingJob = null
    }

    private fun snapToSource(sourceOffset: Int) {
        if (layout == null || profile == null) {
            pendingSourceOffset = sourceOffset
            return
        }
        snapToDisplay(parsed.sourceToDisplay(sourceOffset))
    }

    private fun snapToDisplay(displayOffset: Int) {
        if (layout == null || profile == null) {
            pendingSourceOffset = parsed.displayToSource(displayOffset)
            return
        }
        cancelMotion()
        readingY = yForDisplay(displayOffset)
    }

    private fun needsFrames(): Boolean =
        phase == PrompterPhase.COUNTDOWN || (phase == PrompterPhase.SCROLLING && !held)

    private fun ensureTicker() {
        if (!needsFrames()) return
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            var last = -1L
            while (isActive && needsFrames()) {
                val now = withFrameNanos { it }
                val dt = if (last < 0) 0.0 else ((now - last) / 1e9).coerceIn(0.0, 0.1)
                last = now
                step(dt, now)
            }
        }
    }

    private fun step(dt: Double, frameNanos: Long) {
        when (phase) {
            PrompterPhase.COUNTDOWN -> {
                countdownLeftSec -= dt
                if (countdownLeftSec <= 0.0) {
                    phase = PrompterPhase.SCROLLING
                    velocity = 0f
                    publish(force = true)
                } else {
                    val shown = ceil(countdownLeftSec).toInt()
                    if (shown != _snapshot.value.countdownRemaining) publish(force = true)
                }
            }
            PrompterPhase.SCROLLING -> {
                if (held) return
                val p = profile
                if (p == null || p.isEmpty) return
                val target = p.velocityAt(readingY, wpm)
                // Low-pass the velocity (τ ≈ 0.28 s): soft start and seamless speed changes.
                val alpha = 1f - exp(-dt / VELOCITY_TAU).toFloat()
                velocity += (target - velocity) * alpha
                elapsedMs += dt * 1000.0
                val next = readingY + velocity * dt.toFloat()
                if (next >= p.endY) {
                    if (loop) {
                        readingY = p.startY
                        velocity = 0f
                    } else {
                        readingY = p.endY
                        phase = PrompterPhase.FINISHED
                        velocity = 0f
                    }
                    publish(force = true)
                } else {
                    readingY = next
                    publish(nowNanos = frameNanos)
                }
            }
            else -> Unit
        }
    }

    // ---------------------------------------------------------------- snapshot

    private fun publish(force: Boolean = false, nowNanos: Long = System.nanoTime()) {
        if (!force && nowNanos - lastPublishNanos < PUBLISH_INTERVAL_NS) return
        lastPublishNanos = nowNanos
        val script = parsed
        val p = profile
        val display: Int
        val progress: Float
        if (p != null && !p.isEmpty && layout != null) {
            val line = p.lineAt(readingY)
            val fraction = p.fractionIn(line, readingY)
            val lineStart = p.lineStarts[line]
            val lineEnd = if (line + 1 < p.lineCount) p.lineStarts[line + 1] else script.text.length
            display = (lineStart + ((lineEnd - lineStart) * fraction).toInt()).coerceIn(0, script.text.length)
            val span = p.endY - p.startY
            progress = if (span > 0f) ((readingY - p.startY) / span).coerceIn(0f, 1f) else 0f
        } else {
            display = script.sourceToDisplay(pendingSourceOffset ?: startSourceOffset)
            progress = 0f
        }
        val lineStartDisplay = if (p != null && !p.isEmpty && layout != null) p.lineStarts[p.lineAt(readingY)] else display
        val sectionIndex = SectionNavigator.currentIndex(script.sections.map { it.displayOffset }, display)
        val remaining = if (phase == PrompterPhase.FINISHED) 0L else PrompterTiming.remainingMs(script, display, wpm)
        _snapshot.value = PrompterSnapshot(
            phase = phase,
            countdownRemaining = if (phase == PrompterPhase.COUNTDOWN) ceil(countdownLeftSec).toInt().coerceAtLeast(1) else 0,
            progress = if (phase == PrompterPhase.FINISHED) 1f else progress,
            remainingMs = remaining,
            elapsedMs = elapsedMs.toLong(),
            readingCharOffset = script.displayToSource(lineStartDisplay),
            currentSectionIndex = sectionIndex,
            wordsPerMinute = wpm,
            totalMs = PrompterTiming.totalMs(script, wpm),
            held = held,
        )
    }

    fun dispose() {
        ticker?.cancel()
        cancelMotion()
    }

    private companion object {
        const val VELOCITY_TAU = 0.28
        const val PUBLISH_INTERVAL_NS = 100_000_000L
    }
}
