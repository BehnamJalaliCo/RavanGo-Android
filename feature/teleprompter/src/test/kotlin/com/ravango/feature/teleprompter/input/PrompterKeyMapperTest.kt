package com.ravango.feature.teleprompter.input

import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PrompterKeyMapperTest {

    @Test
    fun `short volume press changes speed on key up`() {
        val mapper = PrompterKeyMapper()
        val down = mapper.onKey(KeyEvent.KEYCODE_VOLUME_UP, isDown = true, repeatCount = 0, isLongPress = false)
        assertThat(down.consumed).isTrue()
        assertThat(down.action).isNull()
        val up = mapper.onKey(KeyEvent.KEYCODE_VOLUME_UP, isDown = false, repeatCount = 0, isLongPress = false)
        assertThat(up.action).isEqualTo(PrompterKeyAction.Speed(PrompterKeyMapper.SPEED_STEP))
        mapper.onKey(KeyEvent.KEYCODE_VOLUME_DOWN, true, 0, false)
        assertThat(mapper.onKey(KeyEvent.KEYCODE_VOLUME_DOWN, false, 0, false).action)
            .isEqualTo(PrompterKeyAction.Speed(-PrompterKeyMapper.SPEED_STEP))
    }

    @Test
    fun `long volume press toggles once and suppresses the short action`() {
        val mapper = PrompterKeyMapper()
        mapper.onKey(KeyEvent.KEYCODE_VOLUME_DOWN, true, 0, false)
        assertThat(mapper.onKey(KeyEvent.KEYCODE_VOLUME_DOWN, true, 1, true).action).isEqualTo(PrompterKeyAction.Toggle)
        assertThat(mapper.onKey(KeyEvent.KEYCODE_VOLUME_DOWN, true, 2, false).action).isNull()
        val up = mapper.onKey(KeyEvent.KEYCODE_VOLUME_DOWN, false, 0, false)
        assertThat(up.consumed).isTrue()
        assertThat(up.action).isNull()
    }

    @Test
    fun `page mode maps volume up to page back`() {
        val mapper = PrompterKeyMapper(volumeMode = VolumeKeyMode.PAGE)
        mapper.onKey(KeyEvent.KEYCODE_VOLUME_UP, true, 0, false)
        assertThat(mapper.onKey(KeyEvent.KEYCODE_VOLUME_UP, false, 0, false).action)
            .isEqualTo(PrompterKeyAction.Page(-PrompterKeyMapper.PAGE_FRACTION))
    }

    @Test
    fun `disabled volume keys are not consumed so the system volume still works`() {
        val mapper = PrompterKeyMapper(volumeEnabled = false)
        assertThat(mapper.onKey(KeyEvent.KEYCODE_VOLUME_UP, true, 0, false).consumed).isFalse()
    }

    @Test
    fun `remote keys page and toggle on key down`() {
        val mapper = PrompterKeyMapper()
        assertThat(mapper.onKey(KeyEvent.KEYCODE_PAGE_DOWN, true, 0, false).action).isEqualTo(PrompterKeyAction.Page(0.5f))
        assertThat(mapper.onKey(KeyEvent.KEYCODE_DPAD_UP, true, 3, false).action).isEqualTo(PrompterKeyAction.Page(-0.5f))
        assertThat(mapper.onKey(KeyEvent.KEYCODE_SPACE, true, 0, false).action).isEqualTo(PrompterKeyAction.Toggle)
        // Auto-repeat of the toggle key must not flip play/pause repeatedly.
        assertThat(mapper.onKey(KeyEvent.KEYCODE_SPACE, true, 1, false).action).isNull()
        assertThat(mapper.onKey(KeyEvent.KEYCODE_SPACE, false, 0, false).consumed).isTrue()
        assertThat(mapper.onKey(KeyEvent.KEYCODE_A, true, 0, false).consumed).isFalse()
    }

    @Test
    fun `disabled remote leaves keys alone`() {
        val mapper = PrompterKeyMapper(remoteEnabled = false)
        assertThat(mapper.onKey(KeyEvent.KEYCODE_PAGE_DOWN, true, 0, false).consumed).isFalse()
    }
}
