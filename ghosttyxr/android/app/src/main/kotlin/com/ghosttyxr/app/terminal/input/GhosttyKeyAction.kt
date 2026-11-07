package com.ghosttyxr.app.terminal.input

import android.view.KeyEvent

/**
 * Ghostty `input.KeyAction` ordinal. Sent as the `action` arg to
 * `nativeEncodeKey`; the Zig side `intToEnum`s it back. Order must match
 * libghostty's `src/input/key.zig` `KeyAction` enum.
 */
object GhosttyKeyAction {
    const val Release = 0
    const val Press = 1
    const val Repeat = 2

    /**
     * Map an Android KeyEvent action + repeat count to a Ghostty
     * KeyAction. Returns null for ACTION_MULTIPLE and other actions we
     * don't translate — those should be handled at a higher level (e.g.
     * IME composing text dispatches via a separate write path).
     */
    fun fromAndroid(action: Int, repeatCount: Int = 0): Int? = when (action) {
        KeyEvent.ACTION_DOWN -> if (repeatCount > 0) Repeat else Press
        KeyEvent.ACTION_UP -> Release
        else -> null
    }
}
