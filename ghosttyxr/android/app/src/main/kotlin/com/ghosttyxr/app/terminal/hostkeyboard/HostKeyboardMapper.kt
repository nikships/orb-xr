package com.ghosttyxr.app.terminal.hostkeyboard

import com.ghosttyxr.app.terminal.input.GhosttyKey
import com.ghosttyxr.app.terminal.input.GhosttyKeyAction
import com.ghosttyxr.app.terminal.input.GhosttyKeyMods
import com.ghosttyxr.app.terminal.input.MappedKey

object HostKeyboardMapper {
    fun map(message: HostKeyboardMessage.Key): MappedKey? {
        val key = codeToGhosttyKey[message.code] ?: GhosttyKey.unidentified
        val codepoint = unshiftedCodePoint(message.code, message.text)
        if (key == GhosttyKey.unidentified && codepoint == 0 && message.text.isNullOrEmpty()) {
            return null
        }
        val utf8 = printableUtf8(message.text, message.mods, message.action)
        return MappedKey(
            key = key,
            codepoint = codepoint,
            mods = message.mods,
            action = message.action,
            utf8 = utf8,
        )
    }

    fun releaseFor(mapped: MappedKey): MappedKey =
        mapped.copy(action = GhosttyKeyAction.Release, utf8 = null)

    private fun firstCodePoint(text: String?): Int {
        if (text.isNullOrEmpty()) return 0
        val cp = text.codePointAt(0)
        return if (Character.isValidCodePoint(cp)) cp else 0
    }

    private fun unshiftedCodePoint(code: String, text: String?): Int {
        val textCodePoint = firstCodePoint(text)
        if (textCodePoint >= 0x20 &&
            textCodePoint != 0x7F &&
            textCodePoint !in 0xF700..0xF8FF
        ) {
            return textCodePoint
        }
        return codeToUnshiftedCodePoint[code] ?: textCodePoint
    }

    private fun printableUtf8(text: String?, mods: Int, action: Int): String? {
        if (text.isNullOrEmpty()) return null
        if (action != GhosttyKeyAction.Press && action != GhosttyKeyAction.Repeat) return null
        if (mods and GhosttyKeyMods.CTRL_ALT_SUPER != 0) return null
        return text
    }

    // Letter, digit, F-key, and numpad-digit codes are contiguous in [GhosttyKey],
    // so those blocks are generated arithmetically.
    private val codeToGhosttyKey: Map<String, Int> = buildMap {
        ('A'..'Z').forEach { letter ->
            put("Key$letter", GhosttyKey.keyA + (letter - 'A'))
        }
        ('0'..'9').forEach { digit ->
            put("Digit$digit", GhosttyKey.digit0 + (digit - '0'))
            put("Numpad$digit", GhosttyKey.numpad0 + (digit - '0'))
        }
        (1..20).forEach { n ->
            put("F$n", GhosttyKey.f1 + (n - 1))
        }
        put("Backquote", GhosttyKey.backquote)
        put("Backslash", GhosttyKey.backslash)
        put("BracketLeft", GhosttyKey.bracketLeft)
        put("BracketRight", GhosttyKey.bracketRight)
        put("Comma", GhosttyKey.comma)
        put("Equal", GhosttyKey.equal)
        put("IntlBackslash", GhosttyKey.intlBackslash)
        put("IntlRo", GhosttyKey.intlRo)
        put("IntlYen", GhosttyKey.intlYen)
        put("Minus", GhosttyKey.minus)
        put("Period", GhosttyKey.period)
        put("Quote", GhosttyKey.quote)
        put("Semicolon", GhosttyKey.semicolon)
        put("Slash", GhosttyKey.slash)
        put("AltLeft", GhosttyKey.altLeft)
        put("AltRight", GhosttyKey.altRight)
        put("Backspace", GhosttyKey.backspace)
        put("CapsLock", GhosttyKey.capsLock)
        put("ContextMenu", GhosttyKey.contextMenu)
        put("ControlLeft", GhosttyKey.controlLeft)
        put("ControlRight", GhosttyKey.controlRight)
        put("Enter", GhosttyKey.enter)
        put("MetaLeft", GhosttyKey.metaLeft)
        put("MetaRight", GhosttyKey.metaRight)
        put("ShiftLeft", GhosttyKey.shiftLeft)
        put("ShiftRight", GhosttyKey.shiftRight)
        put("Space", GhosttyKey.space)
        put("Tab", GhosttyKey.tab)
        put("Delete", GhosttyKey.delete)
        put("End", GhosttyKey.end)
        put("Help", GhosttyKey.help)
        put("Home", GhosttyKey.home)
        put("Insert", GhosttyKey.insert)
        put("PageDown", GhosttyKey.pageDown)
        put("PageUp", GhosttyKey.pageUp)
        put("ArrowDown", GhosttyKey.arrowDown)
        put("ArrowLeft", GhosttyKey.arrowLeft)
        put("ArrowRight", GhosttyKey.arrowRight)
        put("ArrowUp", GhosttyKey.arrowUp)
        put("NumLock", GhosttyKey.numLock)
        put("NumpadAdd", GhosttyKey.numpadAdd)
        put("NumpadBackspace", GhosttyKey.numpadBackspace)
        put("NumpadClear", GhosttyKey.numpadClear)
        put("NumpadComma", GhosttyKey.numpadComma)
        put("NumpadDecimal", GhosttyKey.numpadDecimal)
        put("NumpadDivide", GhosttyKey.numpadDivide)
        put("NumpadEnter", GhosttyKey.numpadEnter)
        put("NumpadEqual", GhosttyKey.numpadEqual)
        put("NumpadMultiply", GhosttyKey.numpadMultiply)
        put("NumpadSubtract", GhosttyKey.numpadSubtract)
        put("NumpadSeparator", GhosttyKey.numpadSeparator)
        put("NumpadUp", GhosttyKey.numpadUp)
        put("NumpadDown", GhosttyKey.numpadDown)
        put("NumpadRight", GhosttyKey.numpadRight)
        put("NumpadLeft", GhosttyKey.numpadLeft)
        put("NumpadHome", GhosttyKey.numpadHome)
        put("NumpadEnd", GhosttyKey.numpadEnd)
        put("NumpadInsert", GhosttyKey.numpadInsert)
        put("NumpadDelete", GhosttyKey.numpadDelete)
        put("NumpadPageUp", GhosttyKey.numpadPageUp)
        put("NumpadPageDown", GhosttyKey.numpadPageDown)
        put("Escape", GhosttyKey.escape)
        put("Fn", GhosttyKey.fn)
    }

    private val codeToUnshiftedCodePoint: Map<String, Int> = buildMap {
        ('A'..'Z').forEach { letter ->
            put("Key$letter", letter.lowercaseChar().code)
        }
        ('0'..'9').forEach { digit ->
            put("Digit$digit", digit.code)
        }
        put("Backquote", '`'.code)
        put("Backslash", '\\'.code)
        put("BracketLeft", '['.code)
        put("BracketRight", ']'.code)
        put("Comma", ','.code)
        put("Equal", '='.code)
        put("Minus", '-'.code)
        put("Period", '.'.code)
        put("Quote", '\''.code)
        put("Semicolon", ';'.code)
        put("Slash", '/'.code)
        put("Space", ' '.code)
    }
}
