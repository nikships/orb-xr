package com.ghosttyxr.app.terminal.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests go through the pure `map(keyCode, androidAction, repeatCount)` overload because the
 * KeyEvent overload requires real Android accessors — `KeyEvent.getKeyCode()` / `getAction()`
 * return 0 under the project's `unitTests.isReturnDefaultValues = true` flag.
 */
class ControllerButtonMapperTest {

    private val mapper = ControllerButtonMapper.Default

    @Test
    fun `trigger maps to enter press`() {
        val mapped = mapper.map(KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.ACTION_DOWN, 0)
        assertNotNull(mapped)
        assertEquals(GhosttyKey.enter, mapped!!.key)
        assertEquals(GhosttyKeyAction.Press, mapped.action)
    }

    @Test
    fun `B button maps to escape`() {
        val mapped = mapper.map(KeyEvent.KEYCODE_BUTTON_B, KeyEvent.ACTION_DOWN, 0)
        assertNotNull(mapped)
        assertEquals(GhosttyKey.escape, mapped!!.key)
    }

    @Test
    fun `X button maps to tab with codepoint`() {
        val mapped = mapper.map(KeyEvent.KEYCODE_BUTTON_X, KeyEvent.ACTION_DOWN, 0)
        assertNotNull(mapped)
        assertEquals(GhosttyKey.tab, mapped!!.key)
        assertEquals('\t'.code, mapped.codepoint)
    }

    @Test
    fun `start emits ctrl+C`() {
        val mapped = mapper.map(KeyEvent.KEYCODE_BUTTON_START, KeyEvent.ACTION_DOWN, 0)
        assertNotNull(mapped)
        assertEquals(GhosttyKey.keyC, mapped!!.key)
        assertEquals(1 shl 1, mapped.mods) // bit 1 = ctrl
        assertEquals('c'.code, mapped.codepoint)
    }

    @Test
    fun `select emits ctrl+D`() {
        val mapped = mapper.map(KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.ACTION_DOWN, 0)
        assertNotNull(mapped)
        assertEquals(GhosttyKey.keyD, mapped!!.key)
        assertEquals(1 shl 1, mapped.mods)
    }

    @Test
    fun `release action translates from android up`() {
        val mapped = mapper.map(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_UP, 0)
        assertNotNull(mapped)
        assertEquals(GhosttyKeyAction.Release, mapped!!.action)
    }

    @Test
    fun `repeat down emits repeat action`() {
        val mapped = mapper.map(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_DOWN, 3)
        assertNotNull(mapped)
        assertEquals(GhosttyKeyAction.Repeat, mapped!!.action)
    }

    @Test
    fun `unknown keycode returns null`() {
        assertNull(mapper.map(KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.ACTION_DOWN, 0))
    }

    @Test
    fun `letter keycode is not claimed by controller mapper`() {
        // Letter keys must fall through to KeyMapper so a physical keyboard works.
        assertNull(mapper.map(KeyEvent.KEYCODE_A, KeyEvent.ACTION_DOWN, 0))
    }

    @Test
    fun `spatial friendly mapper leaves trigger to the runtime`() {
        val spatial = ControllerButtonMapper.SpatialFriendly
        assertNull(spatial.map(KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.ACTION_DOWN, 0))
        // But A still maps so controller-only typing remains possible.
        assertNotNull(spatial.map(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_DOWN, 0))
    }
}
