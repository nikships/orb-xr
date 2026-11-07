package com.ghosttyxr.app.terminal.input

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceHotkeyTest {

    @Test
    fun `alt+space is the voice toggle`() {
        assertTrue(VoiceHotkey.isVoiceToggle(KeyEvent.KEYCODE_SPACE, KeyEvent.META_ALT_ON))
    }

    @Test
    fun `plain space is not the toggle`() {
        assertFalse(VoiceHotkey.isVoiceToggle(KeyEvent.KEYCODE_SPACE, 0))
    }

    @Test
    fun `ctrl+alt+space is left for the terminal`() {
        assertFalse(
            VoiceHotkey.isVoiceToggle(
                KeyEvent.KEYCODE_SPACE,
                KeyEvent.META_ALT_ON or KeyEvent.META_CTRL_ON,
            ),
        )
    }

    @Test
    fun `meta+alt+space is not the toggle`() {
        assertFalse(
            VoiceHotkey.isVoiceToggle(
                KeyEvent.KEYCODE_SPACE,
                KeyEvent.META_ALT_ON or KeyEvent.META_META_ON,
            ),
        )
    }

    @Test
    fun `alt+other key is not the toggle`() {
        assertFalse(VoiceHotkey.isVoiceToggle(KeyEvent.KEYCODE_A, KeyEvent.META_ALT_ON))
    }

    @Test
    fun `toggle fires only on the initial key-down`() {
        assertTrue(
            VoiceHotkey.isTogglePress(
                KeyEvent.KEYCODE_SPACE,
                KeyEvent.META_ALT_ON,
                KeyEvent.ACTION_DOWN,
                repeatCount = 0,
            ),
        )
    }

    @Test
    fun `toggle ignores auto-repeat and key-up`() {
        assertFalse(
            VoiceHotkey.isTogglePress(
                KeyEvent.KEYCODE_SPACE,
                KeyEvent.META_ALT_ON,
                KeyEvent.ACTION_DOWN,
                repeatCount = 3,
            ),
        )
        assertFalse(
            VoiceHotkey.isTogglePress(
                KeyEvent.KEYCODE_SPACE,
                KeyEvent.META_ALT_ON,
                KeyEvent.ACTION_UP,
                repeatCount = 0,
            ),
        )
    }
}
