package com.ghosttyxr.app.terminal.input

/**
 * Ghostty `input.KeyMods` packed-struct backing-int layout.
 *
 * Keep this in sync with libghostty's `input.KeyMods`; it is shared by local
 * Android keyboard mapping, the Mac-host keyboard relay, and accessory keys.
 */
object GhosttyKeyMods {
    const val SHIFT = 1 shl 0
    const val CONTROL = 1 shl 1
    const val ALT = 1 shl 2
    const val SUPER = 1 shl 3
    const val CAPS_LOCK = 1 shl 4
    const val NUM_LOCK = 1 shl 5

    const val CTRL_ALT_SUPER = CONTROL or ALT or SUPER
}
