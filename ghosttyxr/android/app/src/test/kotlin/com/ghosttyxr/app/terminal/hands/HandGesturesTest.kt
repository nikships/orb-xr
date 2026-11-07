package com.ghosttyxr.app.terminal.hands

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandGesturesTest {

    @Test
    fun `palm facing up summons`() {
        assertTrue(HandGestures.isPalmUp(1.0f))
        assertTrue(HandGestures.isPalmUp(HandGestures.PALM_UP_DOT + 0.01f))
    }

    @Test
    fun `palm down does not summon`() {
        assertFalse(HandGestures.isPalmUp(-1.0f))
    }

    @Test
    fun `palm held flat (sideways normal) does not summon`() {
        assertFalse(HandGestures.isPalmUp(0.0f))
        assertFalse(HandGestures.isPalmUp(HandGestures.PALM_UP_DOT))
    }
}
