package com.ghosttyxr.app.terminal.input

import android.view.KeyEvent

/**
 * Bluetooth-keyboard hotkey for toggling voice command capture: **Alt+Space**.
 *
 * Intercepted at the Activity's `dispatchKeyEvent` (see `MainActivity`) so it works regardless of
 * which Compose/XR node holds focus, and independent of lock mode (unlike [PaneNavShortcuts]).
 * Requiring Ctrl/Meta to be **clear** keeps terminal chords like `Ctrl+Space` flowing through to
 * the focused pane untouched; only the bare `Alt+Space` chord is consumed.
 *
 * Pure logic, host-JVM testable (mirrors [PaneNavShortcuts]; the project's
 * `unitTests.isReturnDefaultValues = true` covers the `KeyEvent` constants).
 */
object VoiceHotkey {

    /** True iff this is `Alt+Space` with Alt set and Ctrl/Meta clear. */
    fun isVoiceToggle(keyCode: Int, metaState: Int): Boolean {
        if (keyCode != KeyEvent.KEYCODE_SPACE) return false
        if (metaState and KeyEvent.META_ALT_ON == 0) return false
        if (metaState and KeyEvent.META_CTRL_ON != 0) return false
        if (metaState and KeyEvent.META_META_ON != 0) return false
        return true
    }

    /**
     * True only on the initial key-down ([KeyEvent.ACTION_DOWN], `repeatCount == 0`) of the
     * Alt+Space chord, so auto-repeat and key-up don't re-fire the toggle.
     */
    fun isTogglePress(keyCode: Int, metaState: Int, action: Int, repeatCount: Int): Boolean {
        if (action != KeyEvent.ACTION_DOWN || repeatCount != 0) return false
        return isVoiceToggle(keyCode, metaState)
    }

    fun isVoiceToggle(event: KeyEvent): Boolean = isVoiceToggle(event.keyCode, event.metaState)

    fun isTogglePress(event: KeyEvent): Boolean =
        isTogglePress(event.keyCode, event.metaState, event.action, event.repeatCount)
}
