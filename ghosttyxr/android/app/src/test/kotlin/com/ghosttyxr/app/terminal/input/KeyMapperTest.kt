package com.ghosttyxr.app.terminal.input

import android.view.KeyEvent
import com.ghosttyxr.app.terminal.GhosttyBridge
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Unit tests for [KeyMapper] split in two: pure mapping (always runs) and
 * the bridge-bound JNI roundtrip (skipped on host JVM where the .so won't
 * load — only meaningful when run as an instrumented test on a device).
 */
class KeyMapperTest {

    @Test
    fun `arrow up maps to ghostty arrowUp with no mods`() {
        val mapped = KeyMapper.map(
            keyCode = KeyEvent.KEYCODE_DPAD_UP,
            codepoint = 0,
            metaState = 0,
            action = GhosttyKeyAction.Press,
        )
        assertNotNull(mapped)
        assertEquals(GhosttyKey.arrowUp, mapped!!.key)
        assertEquals(0, mapped.codepoint)
        assertEquals(0, mapped.mods)
        assertEquals(GhosttyKeyAction.Press, mapped.action)
    }

    @Test
    fun `letter A gets unshifted codepoint when no mods`() {
        val mapped = KeyMapper.map(
            keyCode = KeyEvent.KEYCODE_A,
            codepoint = 0,
            metaState = 0,
            action = GhosttyKeyAction.Press,
        )
        assertNotNull(mapped)
        assertEquals(GhosttyKey.keyA, mapped!!.key)
        assertEquals('a'.code, mapped.codepoint)
        assertEquals("a", mapped.utf8)
    }

    @Test
    fun `letter preserves resolved shifted unicode text`() {
        val mapped = KeyMapper.map(
            keyCode = KeyEvent.KEYCODE_A,
            codepoint = 'A'.code,
            metaState = KeyEvent.META_SHIFT_ON,
            action = GhosttyKeyAction.Press,
        )
        assertNotNull(mapped)
        assertEquals(GhosttyKey.keyA, mapped!!.key)
        assertEquals('A'.code, mapped.codepoint)
        assertEquals("A", mapped.utf8)
    }

    @Test
    fun `ctrl-c sets ctrl mod bit`() {
        val mapped = KeyMapper.map(
            keyCode = KeyEvent.KEYCODE_C,
            codepoint = 0,
            metaState = KeyEvent.META_CTRL_ON,
            action = GhosttyKeyAction.Press,
        )
        assertNotNull(mapped)
        assertEquals(GhosttyKey.keyC, mapped!!.key)
        // bit 1 = ctrl in libghostty's KeyMods backing int
        assertEquals(1 shl 1, mapped.mods)
        assertNull(mapped.utf8)
    }

    @Test
    fun `shift-alt sets both mod bits`() {
        val mapped = KeyMapper.map(
            keyCode = KeyEvent.KEYCODE_TAB,
            codepoint = 0,
            metaState = KeyEvent.META_SHIFT_ON or KeyEvent.META_ALT_ON,
            action = GhosttyKeyAction.Press,
        )
        assertNotNull(mapped)
        assertEquals(GhosttyKey.tab, mapped!!.key)
        assertEquals((1 shl 0) or (1 shl 2), mapped.mods)
    }

    @Test
    fun `release action propagates`() {
        val mapped = KeyMapper.map(
            keyCode = KeyEvent.KEYCODE_ESCAPE,
            codepoint = 0,
            metaState = 0,
            action = GhosttyKeyAction.Release,
        )
        assertNotNull(mapped)
        assertEquals(GhosttyKey.escape, mapped!!.key)
        assertEquals(GhosttyKeyAction.Release, mapped.action)
    }

    @Test
    fun `unknown keycode with no codepoint returns null`() {
        val mapped = KeyMapper.map(
            keyCode = KeyEvent.KEYCODE_HEADSETHOOK,
            codepoint = 0,
            metaState = 0,
            action = GhosttyKeyAction.Press,
        )
        assertNull(mapped)
    }

    @Test
    fun `unknown keycode with codepoint falls back to codepoint path`() {
        // Hypothetical IME-typed character with no physical key match.
        val mapped = KeyMapper.map(
            keyCode = 0xFFFF,
            codepoint = 'é'.code,
            metaState = 0,
            action = GhosttyKeyAction.Press,
        )
        assertNotNull(mapped)
        assertEquals(0, mapped!!.key)
        assertEquals('é'.code, mapped.codepoint)
    }

    @Test
    fun `repeat action survives the mapping`() {
        val mapped = KeyMapper.map(
            keyCode = KeyEvent.KEYCODE_A,
            codepoint = 0,
            metaState = 0,
            action = GhosttyKeyAction.Repeat,
        )
        assertNotNull(mapped)
        assertEquals(GhosttyKeyAction.Repeat, mapped!!.action)
    }

    @Test
    fun `arrow up encodes to ESC bracket A via libghostty`() {
        // JNI-bound: only meaningful on a device where libghosttyxr.so
        // actually loads. The host JVM unit-test run won't have an
        // aarch64-linux-android .so available, so this assumes-out.
        val bridge = GhosttyBridge()
        assumeTrue("libghosttyxr.so not loaded on host: ${bridge.nativeStatus()}", bridge.isLoaded())

        val handle = bridge.nativeCreate(80, 25, 0)
        assumeTrue("nativeCreate returned 0", handle != 0L)
        try {
            val bytes = bridge.nativeEncodeKey(
                handle,
                GhosttyKey.arrowUp,
                0,
                0,
                GhosttyKeyAction.Press,
                null,
            )
            assertNotNull(bytes)
            // Default VT100 cursor-up: ESC [ A
            assertArrayEquals(byteArrayOf(0x1b, '['.code.toByte(), 'A'.code.toByte()), bytes)
        } finally {
            bridge.nativeDestroy(handle)
        }
    }
}
