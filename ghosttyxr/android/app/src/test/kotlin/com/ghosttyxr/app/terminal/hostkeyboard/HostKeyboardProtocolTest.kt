package com.ghosttyxr.app.terminal.hostkeyboard

import com.ghosttyxr.app.terminal.input.AppShortcutAction
import com.ghosttyxr.app.terminal.input.AppShortcutRouter
import com.ghosttyxr.app.terminal.input.GhosttyKey
import com.ghosttyxr.app.terminal.input.GhosttyKeyAction
import com.ghosttyxr.app.terminal.input.GhosttyKeyMods
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostKeyboardProtocolTest {
    @Test
    fun parsesKeyMessage() {
        val message = HostKeyboardProtocol.parse(
            """{"v":1,"type":"key","seq":7,"code":"KeyA","text":"a","mods":0,"action":"press"}""",
        )

        val key = message as HostKeyboardMessage.Key
        assertEquals(7L, key.seq)
        assertEquals("KeyA", key.code)
        assertEquals("a", key.text)
        assertEquals(GhosttyKeyAction.Press, key.action)
    }

    @Test
    fun mapsPlainTextToUtf8() {
        val mapped = HostKeyboardMapper.map(
            HostKeyboardMessage.Key(
                seq = 1,
                code = "KeyE",
                text = "é",
                mods = 0,
                action = GhosttyKeyAction.Press,
            ),
        )

        requireNotNull(mapped)
        assertEquals(GhosttyKey.keyE, mapped.key)
        assertEquals("é", mapped.utf8)
        assertEquals("é".codePointAt(0), mapped.codepoint)
    }

    @Test
    fun controlChordKeepsKeyAndSuppressesUtf8FastPath() {
        val mapped = HostKeyboardMapper.map(
            HostKeyboardMessage.Key(
                seq = 1,
                code = "KeyC",
                text = "\u0003",
                mods = GhosttyKeyMods.CONTROL,
                action = GhosttyKeyAction.Press,
            ),
        )

        requireNotNull(mapped)
        assertEquals(GhosttyKey.keyC, mapped.key)
        assertEquals('c'.code, mapped.codepoint)
        assertNull(mapped.utf8)
    }

    @Test
    fun controlChordRecoversUnshiftedCodepointWhenMacOmitsText() {
        val mapped = HostKeyboardMapper.map(
            HostKeyboardMessage.Key(
                seq = 1,
                code = "KeyC",
                text = null,
                mods = GhosttyKeyMods.CONTROL,
                action = GhosttyKeyAction.Press,
            ),
        )

        requireNotNull(mapped)
        assertEquals(GhosttyKey.keyC, mapped.key)
        assertEquals('c'.code, mapped.codepoint)
        assertNull(mapped.utf8)
    }

    @Test
    fun paneShortcutOnlyConsumesWhenLayoutLocked() {
        val mods = GhosttyKeyMods.ALT

        assertFalse(AppShortcutRouter.shouldConsumeRemote("Digit2", mods, layoutLocked = false))
        assertTrue(AppShortcutRouter.shouldConsumeRemote("Digit2", mods, layoutLocked = true))

        val action = AppShortcutRouter.remote(
            code = "Digit2",
            mods = mods,
            action = GhosttyKeyAction.Press,
            layoutLocked = true,
        )
        assertEquals(AppShortcutAction.FocusPane(1), action)
    }

    @Test
    fun altSpaceConsumesAsVoiceToggle() {
        val mods = GhosttyKeyMods.ALT

        assertTrue(AppShortcutRouter.shouldConsumeRemote("Space", mods, layoutLocked = false))
        assertEquals(
            AppShortcutAction.ToggleVoiceCapture,
            AppShortcutRouter.remote("Space", mods, GhosttyKeyAction.Press, layoutLocked = false),
        )
    }
}
