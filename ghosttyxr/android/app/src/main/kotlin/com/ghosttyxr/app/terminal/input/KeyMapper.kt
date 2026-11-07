package com.ghosttyxr.app.terminal.input

import android.view.KeyCharacterMap
import android.view.KeyEvent
import com.ghosttyxr.app.terminal.GhosttyBridge

/**
 * Pure result of mapping an Android [KeyEvent] to libghostty's
 * `KeyEvent` shape. Either [key] (physical key code) or [codepoint] (UTF-32
 * char from the IME) is non-zero; both may be set for printable keys so the
 * legacy encoding path can emit raw text when the host doesn't speak the
 * Kitty keyboard protocol.
 *
 * [action] is a [GhosttyKeyAction] ordinal. [utf8] is the printable text
 * passed straight to libghostty (used for IME composition results and
 * dead-key resolutions).
 */
data class MappedKey(
    val key: Int,
    val codepoint: Int,
    val mods: Int,
    val action: Int,
    val utf8: String? = null,
)

/**
 * Translates Android KeyEvents into libghostty key encodings.
 *
 * The mapper is split in two layers:
 *  1. [map] — pure logic, fully unit-testable on the host JVM with no .so.
 *     Turns a (keyCode, codepoint, metaState, action) into a [MappedKey].
 *  2. [encode] — calls into `bridge.nativeEncodeKey`, returning the
 *     escape-sequence bytes to write over SSH. Requires the .so loaded;
 *     gate tests on [GhosttyBridge.isLoaded].
 *
 * Ported from chuchu's `KeyMapper.kt`; chuchu's `TerminalSpecialKey` enum
 * is inlined here (we don't need its display labels for the bridge).
 */
object KeyMapper {

    /**
     * Convenience over [map] that takes a [KeyEvent] directly and pulls the
     * codepoint via [KeyEvent.getUnicodeChar] (masking off
     * `COMBINING_ACCENT` so the value fits a char codepoint). Returns null
     * if the event isn't a press/release/repeat (e.g. ACTION_MULTIPLE).
     */
    fun fromEvent(event: KeyEvent): MappedKey? {
        val action = GhosttyKeyAction.fromAndroid(event.action, event.repeatCount) ?: return null
        val codepoint = event.unicodeChar and KeyCharacterMap.COMBINING_ACCENT.inv()
        return map(
            keyCode = event.keyCode,
            codepoint = codepoint,
            metaState = event.metaState,
            action = action,
        )
    }

    fun map(
        keyCode: Int,
        codepoint: Int,
        metaState: Int,
        action: Int,
    ): MappedKey? {
        val mods = translateMods(metaState)
        val key = ghosttyKeyFor(keyCode)
        val mapped = when {
            key != null -> MappedKey(key, 0, mods, action)
            // Unknown physical key — fall back to the IME codepoint if
            // present so the legacy encoder can still emit text. Return
            // null only if we have nothing to send.
            codepoint != 0 -> MappedKey(0, codepoint, mods, action)
            else -> return null
        }

        // For mapped physical keys, preserve Android's resolved Unicode
        // character when available. This carries layout, shift, and dead-key
        // resolution. Fall back to an unshifted QWERTY-family codepoint for
        // tests and synthetic key events that do not provide unicodeChar.
        if (mapped.codepoint != 0) return mapped
        val resolved = if (codepoint > 0) codepoint else unshiftedCodepointFor(keyCode)
        val utf8 = printableUtf8(resolved, mods, action)
        return if (resolved != 0) mapped.copy(codepoint = resolved, utf8 = utf8) else mapped
    }

    /**
     * Physical [GhosttyKey] for an Android keycode, or null when we don't map the key.
     * Letter, digit, and function keycodes are contiguous in both keyspaces, so those
     * blocks translate arithmetically.
     */
    private fun ghosttyKeyFor(keyCode: Int): Int? = when (keyCode) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> GhosttyKey.keyA + (keyCode - KeyEvent.KEYCODE_A)
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> GhosttyKey.digit0 + (keyCode - KeyEvent.KEYCODE_0)
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> GhosttyKey.f1 + (keyCode - KeyEvent.KEYCODE_F1)

        // Navigation / editing.
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_VOLUME_UP -> GhosttyKey.arrowUp
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN -> GhosttyKey.arrowDown
        KeyEvent.KEYCODE_DPAD_LEFT -> GhosttyKey.arrowLeft
        KeyEvent.KEYCODE_DPAD_RIGHT -> GhosttyKey.arrowRight
        KeyEvent.KEYCODE_PAGE_UP -> GhosttyKey.pageUp
        KeyEvent.KEYCODE_PAGE_DOWN -> GhosttyKey.pageDown
        KeyEvent.KEYCODE_MOVE_HOME -> GhosttyKey.home
        KeyEvent.KEYCODE_MOVE_END -> GhosttyKey.end
        KeyEvent.KEYCODE_INSERT -> GhosttyKey.insert
        KeyEvent.KEYCODE_FORWARD_DEL -> GhosttyKey.delete
        KeyEvent.KEYCODE_DEL -> GhosttyKey.backspace
        KeyEvent.KEYCODE_TAB -> GhosttyKey.tab
        KeyEvent.KEYCODE_ESCAPE -> GhosttyKey.escape
        KeyEvent.KEYCODE_ENTER -> GhosttyKey.enter
        KeyEvent.KEYCODE_SPACE -> GhosttyKey.space

        // Punctuation.
        KeyEvent.KEYCODE_GRAVE -> GhosttyKey.backquote
        KeyEvent.KEYCODE_MINUS -> GhosttyKey.minus
        KeyEvent.KEYCODE_EQUALS -> GhosttyKey.equal
        KeyEvent.KEYCODE_LEFT_BRACKET -> GhosttyKey.bracketLeft
        KeyEvent.KEYCODE_RIGHT_BRACKET -> GhosttyKey.bracketRight
        KeyEvent.KEYCODE_BACKSLASH -> GhosttyKey.backslash
        KeyEvent.KEYCODE_SEMICOLON -> GhosttyKey.semicolon
        KeyEvent.KEYCODE_APOSTROPHE -> GhosttyKey.quote
        KeyEvent.KEYCODE_COMMA -> GhosttyKey.comma
        KeyEvent.KEYCODE_PERIOD -> GhosttyKey.period
        KeyEvent.KEYCODE_SLASH -> GhosttyKey.slash

        else -> null
    }

    private fun printableUtf8(codepoint: Int, mods: Int, action: Int): String? {
        if (codepoint < 0x20) return null
        if (action != GhosttyKeyAction.Press && action != GhosttyKeyAction.Repeat) return null
        if (mods and GhosttyKeyMods.CTRL_ALT_SUPER != 0) return null
        if (!Character.isValidCodePoint(codepoint)) return null
        return String(Character.toChars(codepoint))
    }

    /**
     * Map → encode in one call. Returns the byte sequence libghostty wants
     * written to the SSH PTY, or null if the event didn't translate, the
     * bridge isn't loaded, or libghostty had nothing to emit. Caller is
     * responsible for forwarding the bytes over the connection.
     */
    fun encode(bridge: GhosttyBridge, handle: Long, event: KeyEvent): ByteArray? {
        val mapped = fromEvent(event) ?: return null
        return encode(bridge, handle, mapped)
    }

    fun encode(bridge: GhosttyBridge, handle: Long, mapped: MappedKey): ByteArray? {
        if (!bridge.isLoaded() || handle == 0L) return null
        return bridge.nativeEncodeKey(
            handle,
            mapped.key,
            mapped.codepoint,
            mapped.mods,
            mapped.action,
            mapped.utf8,
        )
    }

    private fun unshiftedCodepointFor(keyCode: Int): Int = when (keyCode) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ->
            'a'.code + (keyCode - KeyEvent.KEYCODE_A)

        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
            '0'.code + (keyCode - KeyEvent.KEYCODE_0)

        KeyEvent.KEYCODE_SPACE -> ' '.code
        KeyEvent.KEYCODE_GRAVE -> '`'.code
        KeyEvent.KEYCODE_MINUS -> '-'.code
        KeyEvent.KEYCODE_EQUALS -> '='.code
        KeyEvent.KEYCODE_LEFT_BRACKET -> '['.code
        KeyEvent.KEYCODE_RIGHT_BRACKET -> ']'.code
        KeyEvent.KEYCODE_BACKSLASH -> '\\'.code
        KeyEvent.KEYCODE_SEMICOLON -> ';'.code
        KeyEvent.KEYCODE_APOSTROPHE -> '\''.code
        KeyEvent.KEYCODE_COMMA -> ','.code
        KeyEvent.KEYCODE_PERIOD -> '.'.code
        KeyEvent.KEYCODE_SLASH -> '/'.code
        else -> 0
    }

    // Higher bits (caps_lock, num_lock, sides) are unused by the Android
    // host — leave them zero. Adding them would require splitting Android's
    // single META_*_ON into the L/R variants, which Android can't actually
    // distinguish without per-keycode lookup.
    private fun translateMods(metaState: Int): Int {
        var mods = 0
        if (metaState and KeyEvent.META_SHIFT_ON != 0) mods = mods or GhosttyKeyMods.SHIFT
        if (metaState and KeyEvent.META_CTRL_ON != 0) mods = mods or GhosttyKeyMods.CONTROL
        if (metaState and KeyEvent.META_ALT_ON != 0) mods = mods or GhosttyKeyMods.ALT
        if (metaState and KeyEvent.META_META_ON != 0) mods = mods or GhosttyKeyMods.SUPER
        return mods
    }
}
