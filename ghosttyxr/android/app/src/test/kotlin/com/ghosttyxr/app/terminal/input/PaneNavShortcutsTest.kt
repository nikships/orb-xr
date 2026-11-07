package com.ghosttyxr.app.terminal.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the Alt+1..6 pane-switch shortcut matcher. The critical regression
 * guard is that **Ctrl+C never matches** — it must keep flowing to the focused terminal.
 */
class PaneNavShortcutsTest {

    @Test
    fun `alt digit 1 through 6 map to pane index 0 through 5`() {
        val expected = mapOf(
            KeyEvent.KEYCODE_1 to 0,
            KeyEvent.KEYCODE_2 to 1,
            KeyEvent.KEYCODE_3 to 2,
            KeyEvent.KEYCODE_4 to 3,
            KeyEvent.KEYCODE_5 to 4,
            KeyEvent.KEYCODE_6 to 5,
        )
        for ((keyCode, index) in expected) {
            val target = PaneNavShortcuts.match(
                keyCode = keyCode,
                metaState = KeyEvent.META_ALT_ON,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
            )
            assertEquals("Alt+${keyCode} should map to pane $index", index, target)
        }
    }

    @Test
    fun `ctrl-c does not match and is not a shortcut key`() {
        // The whole point: Ctrl+C must pass through to the terminal untouched.
        assertNull(
            PaneNavShortcuts.match(
                keyCode = KeyEvent.KEYCODE_C,
                metaState = KeyEvent.META_CTRL_ON,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
            ),
        )
        assertFalse(PaneNavShortcuts.isShortcutKey(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON))
    }

    @Test
    fun `plain digit without alt does not match`() {
        assertNull(
            PaneNavShortcuts.match(
                keyCode = KeyEvent.KEYCODE_1,
                metaState = 0,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
            ),
        )
        assertFalse(PaneNavShortcuts.isShortcutKey(KeyEvent.KEYCODE_1, 0))
    }

    @Test
    fun `ctrl-alt digit does not match`() {
        val meta = KeyEvent.META_ALT_ON or KeyEvent.META_CTRL_ON
        assertNull(
            PaneNavShortcuts.match(
                keyCode = KeyEvent.KEYCODE_1,
                metaState = meta,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
            ),
        )
        assertFalse(PaneNavShortcuts.isShortcutKey(KeyEvent.KEYCODE_1, meta))
    }

    @Test
    fun `meta-alt digit does not match`() {
        val meta = KeyEvent.META_ALT_ON or KeyEvent.META_META_ON
        assertFalse(PaneNavShortcuts.isShortcutKey(KeyEvent.KEYCODE_2, meta))
    }

    @Test
    fun `alt digit 7 is out of range`() {
        assertNull(
            PaneNavShortcuts.match(
                keyCode = KeyEvent.KEYCODE_7,
                metaState = KeyEvent.META_ALT_ON,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
            ),
        )
        assertFalse(PaneNavShortcuts.isShortcutKey(KeyEvent.KEYCODE_7, KeyEvent.META_ALT_ON))
    }

    @Test
    fun `key up of alt digit is still a shortcut key but does not match a switch`() {
        // isShortcutKey true so dispatchKeyEvent consumes the UP too (no stray key reaching
        // the terminal), but match() only fires the switch on the initial DOWN.
        assertTrue(PaneNavShortcuts.isShortcutKey(KeyEvent.KEYCODE_1, KeyEvent.META_ALT_ON))
        assertNull(
            PaneNavShortcuts.match(
                keyCode = KeyEvent.KEYCODE_1,
                metaState = KeyEvent.META_ALT_ON,
                action = KeyEvent.ACTION_UP,
                repeatCount = 0,
            ),
        )
    }

    @Test
    fun `auto-repeat does not retrigger a switch`() {
        assertNull(
            PaneNavShortcuts.match(
                keyCode = KeyEvent.KEYCODE_1,
                metaState = KeyEvent.META_ALT_ON,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 3,
            ),
        )
    }
}
