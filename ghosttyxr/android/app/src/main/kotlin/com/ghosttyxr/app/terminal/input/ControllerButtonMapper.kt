package com.ghosttyxr.app.terminal.input

import android.view.KeyEvent

/**
 * Maps Galaxy XR / generic Android controller button KeyEvents to terminal-relevant
 * [MappedKey]s.
 *
 * The XR design reserves the menu and thumbstick clicks for **spatial** navigation actions
 * (toggle passthrough, toggle bed mode). This mapper therefore only covers buttons that are
 * safe to consume for terminal input when a pane has focus. The controller layout uses the
 * trigger to grab + move panels; we let the host XR runtime see the trigger event first,
 * but if it propagates to us we treat it as Enter so a focused pane is still typeable with
 * controller-only.
 *
 * Mapping (rationale):
 *  - Trigger → Enter   (mirrors "tap to commit" semantics)
 *  - A       → Enter   (alias; A is the primary action button on Galaxy XR pads)
 *  - B       → Escape  (B is universally "cancel")
 *  - X       → Tab     (handy in a terminal — completion / autocomplete)
 *  - Y       → Backspace
 *  - L1 / L2 → Page Up
 *  - R1 / R2 → Page Down
 *  - Start   → Ctrl+C  (interrupt — by far the most-requested keyboard-free shortcut)
 *  - Select  → Ctrl+D  (EOF / shell logout)
 *
 * All mappings emit Press/Release/Repeat actions translated from the Android KeyEvent
 * action so libghostty sees proper transitions.
 */
class ControllerButtonMapper(
    private val mappings: Map<Int, ControllerKey> = DEFAULT_MAPPINGS,
) {
    /**
     * Returns null when [event] is not a controller event we handle, so the caller can
     * fall back to the regular [KeyMapper].
     */
    fun map(event: KeyEvent): MappedKey? =
        map(keyCode = event.keyCode, androidAction = event.action, repeatCount = event.repeatCount)

    /**
     * Pure overload that doesn't dereference `android.view.KeyEvent` accessors — useful from
     * unit tests where `unitTests.isReturnDefaultValues = true` stubs them all to zero.
     */
    fun map(keyCode: Int, androidAction: Int, repeatCount: Int): MappedKey? {
        val controllerKey = mappings[keyCode] ?: return null
        val action = GhosttyKeyAction.fromAndroid(androidAction, repeatCount) ?: return null
        return controllerKey.toMappedKey(action)
    }

    companion object {
        // Declared before [Default] / [SpatialFriendly] so its value is
        // available when those companion-object properties initialize —
        // Kotlin runs companion inits top-to-bottom and forward refs to
        // uninitialized companion vals are a compile error.
        private val DEFAULT_MAPPINGS: Map<Int, ControllerKey> = mapOf(
            // Triggers and bumpers.
            KeyEvent.KEYCODE_BUTTON_R2 to ControllerKey(GhosttyKey.enter, 0, '\n'.code),
            KeyEvent.KEYCODE_BUTTON_L1 to ControllerKey(GhosttyKey.pageUp, 0, 0),
            KeyEvent.KEYCODE_BUTTON_L2 to ControllerKey(GhosttyKey.pageUp, 0, 0),
            KeyEvent.KEYCODE_BUTTON_R1 to ControllerKey(GhosttyKey.pageDown, 0, 0),

            // Face buttons.
            KeyEvent.KEYCODE_BUTTON_A to ControllerKey(GhosttyKey.enter, 0, '\n'.code),
            KeyEvent.KEYCODE_BUTTON_B to ControllerKey(GhosttyKey.escape, 0, 0),
            KeyEvent.KEYCODE_BUTTON_X to ControllerKey(GhosttyKey.tab, 0, '\t'.code),
            KeyEvent.KEYCODE_BUTTON_Y to ControllerKey(GhosttyKey.backspace, 0, 0),

            // Start / Select → common interrupt / EOF shortcuts.
            KeyEvent.KEYCODE_BUTTON_START to ControllerKey(GhosttyKey.keyC, GhosttyKeyMods.CONTROL, 'c'.code),
            KeyEvent.KEYCODE_BUTTON_SELECT to ControllerKey(GhosttyKey.keyD, GhosttyKeyMods.CONTROL, 'd'.code),
        )

        val Default: ControllerButtonMapper = ControllerButtonMapper()

        /**
         * Same as [Default] but with the trigger button left unmapped so the XR runtime
         * can claim it for grab-and-move on a SpatialPanel. Useful when the user already
         * has a Bluetooth keyboard attached and only wants the controller for spatial UI.
         */
        val SpatialFriendly: ControllerButtonMapper = ControllerButtonMapper(
            DEFAULT_MAPPINGS - KeyEvent.KEYCODE_BUTTON_R2,
        )
    }
}

/**
 * Internal representation of one controller-button-to-terminal mapping. [codepoint] is the
 * legacy codepoint libghostty uses when the remote shell hasn't negotiated the Kitty
 * keyboard protocol — zero means "no legacy payload."
 */
data class ControllerKey(
    val key: Int,
    val mods: Int,
    val codepoint: Int,
) {
    fun toMappedKey(action: Int): MappedKey =
        MappedKey(key = key, codepoint = codepoint, mods = mods, action = action)
}
