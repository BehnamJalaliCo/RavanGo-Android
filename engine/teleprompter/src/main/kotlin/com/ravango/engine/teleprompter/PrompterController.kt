package com.ravango.engine.teleprompter

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import com.ravango.core.model.TeleprompterSettings
import kotlinx.coroutines.flow.StateFlow

/** Playback state of a prompter. */
enum class PrompterPhase { IDLE, COUNTDOWN, SCROLLING, PAUSED, FINISHED }

data class PrompterSnapshot(
    val phase: PrompterPhase = PrompterPhase.IDLE,
    val countdownRemaining: Int = 0,
    /** 0..1 progress through the script. */
    val progress: Float = 0f,
    val remainingMs: Long = 0,
    val elapsedMs: Long = 0,
    /** Character offset at the reading line (for "resume from here"). */
    val readingCharOffset: Int = 0,
    val currentSectionIndex: Int = -1,
    /** Live speed (settings value adjusted by [PrompterController.nudgeSpeed]). */
    val wordsPerMinute: Int = TeleprompterSettings().wordsPerMinute,
    /** Total reading time of the script at [wordsPerMinute] (notes excluded, pauses included). */
    val totalMs: Long = 0,
    /** True while the user holds/drags the text (scrolling is suspended, not paused). */
    val held: Boolean = false,
)

/** A `## Section` marker in a script. */
data class PrompterSection(val index: Int, val title: String, val charOffset: Int)

/**
 * CONTRACT — drives a teleprompter view. Commands are safe to call from any input source (touch, volume keys,
 * Bluetooth remotes, recording state).
 */
@Stable
interface PrompterController {
    val snapshot: StateFlow<PrompterSnapshot>
    val sections: StateFlow<List<PrompterSection>>

    fun play()          // starts countdown (if configured) then scrolls
    fun pause()
    fun toggle()
    fun stop()          // back to start (or the configured start offset)
    fun restart()
    /** Changes speed by [deltaWpm] (clamped). */
    fun nudgeSpeed(deltaWpm: Int)
    /** Scrolls by a fraction of the viewport (e.g. ±0.5 for page up/down). */
    fun scrollBy(viewportFraction: Float)
    fun jumpToChar(charOffset: Int)
    fun jumpToSection(index: Int)
    fun nextSection()
    fun previousSection()
}

/**
 * CONTRACT — the renderer used by the full-screen prompter, the camera overlay and the floating window.
 * Implementations scroll with frame-accurate timing (no recomposition per frame) and honour every field
 * of [settings] (mirroring, eye line, width, fonts, colors, transparency, direction).
 */
@Composable
fun TeleprompterView(
    text: String,
    settings: TeleprompterSettings,
    controller: PrompterController,
    modifier: Modifier = Modifier,
    startCharOffset: Int = 0,
    /** Enables tap-to-pause, drag-to-scrub and pinch-to-zoom-text gestures. */
    interactive: Boolean = true,
    onFontSizeChange: ((Float) -> Unit)? = null,
) {
    TeleprompterViewImpl(text, settings, controller, modifier, startCharOffset, interactive, onFontSizeChange)
}

/** CONTRACT — creates and remembers a controller bound to [text] and [settings]. */
@Composable
fun rememberPrompterController(text: String, settings: TeleprompterSettings, startCharOffset: Int = 0): PrompterController =
    rememberPrompterControllerImpl(text, settings, startCharOffset)
