package com.ravango.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class PrompterFont { VAZIRMATN, SAHEL, SAMIM, SYSTEM_SANS, SYSTEM_SERIF, SYSTEM_MONO }

@Serializable
enum class PrompterTextAlign { START, CENTER, END, JUSTIFY }

/** Where the prompter text block sits on screen; TOP places it closest to the front lens on most phones. */
@Serializable
enum class PrompterPlacement { TOP, CENTER, BOTTOM }

/**
 * Full teleprompter configuration. Speed is expressed in words-per-minute so it is independent of font size
 * and screen density; the engine converts it to pixels/second from the measured layout.
 */
@Serializable
data class TeleprompterSettings(
    val wordsPerMinute: Int = 140,
    val fontSizeSp: Float = 34f,
    val font: PrompterFont = PrompterFont.VAZIRMATN,
    val fontWeight: Int = 500,
    val textColor: ArgbColor = 0xFFFFFFFF,
    val highlightColor: ArgbColor = 0xFFFFD166,
    val backgroundColor: ArgbColor = 0xFF000000,
    /** 0 = fully transparent background, 1 = opaque. */
    val backgroundOpacity: Float = 0.72f,
    val lineSpacing: Float = 1.5f,
    val letterSpacingEm: Float = 0f,
    /** Fraction of the available width used by text (0.4 – 1.0). */
    val textWidthFraction: Float = 0.9f,
    val textAlign: PrompterTextAlign = PrompterTextAlign.START,
    /** Fraction of the prompter area height where the reading (eye) line sits. */
    val eyeLinePosition: Float = 0.28f,
    /** In camera mode: fraction of the screen height occupied by the prompter area. */
    val areaHeightFraction: Float = 0.42f,
    val placement: PrompterPlacement = PrompterPlacement.TOP,
    val mirrorHorizontal: Boolean = false,
    val mirrorVertical: Boolean = false,
    val countdownSeconds: Int = 3,
    val loop: Boolean = false,
    val showEyeLine: Boolean = true,
    val dimReadText: Boolean = true,
    val showRemainingTime: Boolean = true,
    val showProgress: Boolean = true,
    val horizontalPaddingDp: Float = 20f,
    val tapToPause: Boolean = true,
    val volumeKeysControl: Boolean = true,
    val remoteControl: Boolean = true,
    /** When recording in Camera Studio, start scrolling when recording starts and pause with it. */
    val syncWithRecording: Boolean = true,
    val direction: ContentDirection = ContentDirection.AUTO,
) {
    companion object {
        const val MIN_WPM = 40
        const val MAX_WPM = 400
        const val MIN_FONT_SP = 14f
        const val MAX_FONT_SP = 96f
    }
}

/** Named, reusable prompter configuration. Built-in presets have [builtIn] = true and cannot be deleted. */
@Serializable
data class TeleprompterPreset(
    val id: String = newId(),
    val name: String,
    val settings: TeleprompterSettings,
    val builtIn: Boolean = false,
    val updatedAt: Long = 0,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val deletedAt: Long? = null,
)
