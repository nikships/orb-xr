package com.ghosttyxr.app.terminal.input

import android.view.KeyEvent
import com.ghosttyxr.app.terminal.session.CockpitLifecycle

/**
 * Hardware-keyboard shortcuts for switching the focused pane: **Alt+1 .. Alt+6**.
 *
 * Intercepted at the Activity's `dispatchKeyEvent` (see `MainActivity`) so they work
 * regardless of which Compose/XR node holds focus, and **only while lock mode is on**.
 * Everything that isn't an exact Alt+digit(1..6) match is left untouched — in particular
 * `Ctrl+C` (and any `Ctrl+Alt+…` chord) deliberately does NOT match, so the focused
 * terminal still receives it through the normal `KeyMapper` path.
 *
 * Pure logic, fully unit-testable on the host JVM (mirrors [KeyMapper]'s int-arg style;
 * the project's `unitTests.isReturnDefaultValues = true` covers the `KeyEvent` constants).
 */
object PaneNavShortcuts {
    /** Hard ceiling on terminals — mirrors `CockpitLifecycle.MAX_PANES`. */
    const val MAX_PANES = CockpitLifecycle.MAX_PANES

    /**
     * True iff this is an Alt+digit(1..6) chord with **Alt set and Ctrl/Meta clear**. Used to
     * decide whether to consume the event (both down and up) in lock mode. Requiring Ctrl/Meta
     * to be clear is what keeps `Ctrl+C`, `Ctrl+Alt+…`, and `Meta+digit` flowing to the terminal.
     */
    fun isShortcutKey(keyCode: Int, metaState: Int): Boolean {
        if (metaState and KeyEvent.META_ALT_ON == 0) return false
        if (metaState and KeyEvent.META_CTRL_ON != 0) return false
        if (metaState and KeyEvent.META_META_ON != 0) return false
        return keyCode in KeyEvent.KEYCODE_1..(KeyEvent.KEYCODE_1 + MAX_PANES - 1)
    }

    /**
     * Resolve a key event to a target pane index `0..MAX_PANES-1`, or null if it isn't a
     * pane-switch press. Only fires on the initial key-down ([KeyEvent.ACTION_DOWN] with
     * `repeatCount == 0`) so auto-repeat and key-up don't re-trigger a switch.
     */
    fun match(keyCode: Int, metaState: Int, action: Int, repeatCount: Int): Int? {
        if (action != KeyEvent.ACTION_DOWN || repeatCount != 0) return null
        if (!isShortcutKey(keyCode, metaState)) return null
        return keyCode - KeyEvent.KEYCODE_1
    }

    fun isShortcutKey(event: KeyEvent): Boolean = isShortcutKey(event.keyCode, event.metaState)

    fun match(event: KeyEvent): Int? =
        match(event.keyCode, event.metaState, event.action, event.repeatCount)
}
