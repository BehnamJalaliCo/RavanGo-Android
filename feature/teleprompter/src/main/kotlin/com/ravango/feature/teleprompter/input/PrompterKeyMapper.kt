package com.ravango.feature.teleprompter.input

import android.view.KeyEvent
import com.ravango.core.ui.HardwareKeys

/** What volume buttons do in the prompter. Long-pressing either button always toggles play/pause. */
enum class VolumeKeyMode {
    /** Volume up = faster, volume down = slower (default). */
    SPEED,
    /** Volume up = page back, volume down = page forward. */
    PAGE,
    ;

    companion object {
        const val PREF_KEY = "prompter_volume_key_mode"
        fun fromPref(value: String?): VolumeKeyMode = entries.firstOrNull { it.name == value } ?: SPEED
    }
}

sealed interface PrompterKeyAction {
    data object Toggle : PrompterKeyAction
    data class Speed(val deltaWpm: Int) : PrompterKeyAction
    data class Page(val viewportFraction: Float) : PrompterKeyAction
}

/**
 * Translates raw key events from volume buttons, Bluetooth remotes / page turners and keyboards into prompter
 * actions. Pure (no Android state besides key codes) so it is unit-testable.
 *
 * Volume keys: a short press acts on key-up (so a long press can be distinguished); holding either button past
 * the system long-press threshold toggles play/pause once. Remote keys act on key-down and auto-repeat for paging.
 */
class PrompterKeyMapper(
    var volumeEnabled: Boolean = true,
    var remoteEnabled: Boolean = true,
    var volumeMode: VolumeKeyMode = VolumeKeyMode.SPEED,
) {
    private var volumeDownCode = 0
    private var volumeLongHandled = false

    data class Result(val consumed: Boolean, val action: PrompterKeyAction? = null)

    fun onKey(keyCode: Int, isDown: Boolean, repeatCount: Int, isLongPress: Boolean): Result {
        if (keyCode in HardwareKeys.VOLUME) {
            if (!volumeEnabled) return Result(consumed = false)
            return onVolume(keyCode, isDown, repeatCount, isLongPress)
        }
        if (!remoteEnabled) return Result(consumed = false)
        return when (keyCode) {
            in HardwareKeys.REMOTE_TOGGLE -> Result(true, if (isDown && repeatCount == 0) PrompterKeyAction.Toggle else null)
            in HardwareKeys.REMOTE_FORWARD -> Result(true, if (isDown) PrompterKeyAction.Page(PAGE_FRACTION) else null)
            in HardwareKeys.REMOTE_BACKWARD -> Result(true, if (isDown) PrompterKeyAction.Page(-PAGE_FRACTION) else null)
            else -> Result(consumed = false)
        }
    }

    private fun onVolume(keyCode: Int, isDown: Boolean, repeatCount: Int, isLongPress: Boolean): Result {
        if (isDown) {
            if (repeatCount == 0) {
                volumeDownCode = keyCode
                volumeLongHandled = false
                return Result(true)
            }
            if ((isLongPress || repeatCount >= 1) && !volumeLongHandled) {
                volumeLongHandled = true
                return Result(true, PrompterKeyAction.Toggle)
            }
            return Result(true)
        }
        // Key up
        val wasLong = volumeLongHandled
        volumeLongHandled = false
        if (wasLong || volumeDownCode != keyCode) {
            volumeDownCode = 0
            return Result(true)
        }
        volumeDownCode = 0
        val up = keyCode == KeyEvent.KEYCODE_VOLUME_UP
        val action = when (volumeMode) {
            VolumeKeyMode.SPEED -> PrompterKeyAction.Speed(if (up) SPEED_STEP else -SPEED_STEP)
            VolumeKeyMode.PAGE -> PrompterKeyAction.Page(if (up) -PAGE_FRACTION else PAGE_FRACTION)
        }
        return Result(true, action)
    }

    companion object {
        const val SPEED_STEP = 10
        const val PAGE_FRACTION = 0.5f
    }
}
