package com.ghosttyxr.app.terminal.hands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandMenuGestureMachineTest {

    private fun machine() = HandMenuGestureMachine(revealHoldMs = 300L)

    @Test
    fun `holding palm-up past the threshold reveals once`() {
        val m = machine()
        assertNull(m.onFrame(palmUp = true, palmDown = false, nowMs = 0L))
        assertNull(m.onFrame(palmUp = true, palmDown = false, nowMs = 200L))
        assertEquals(HandMenuTransition.Reveal, m.onFrame(palmUp = true, palmDown = false, nowMs = 300L))
        assertTrue(m.isVisible)
    }

    @Test
    fun `brief palm-up that drops before threshold does not reveal`() {
        val m = machine()
        assertNull(m.onFrame(palmUp = true, palmDown = false, nowMs = 0L))
        assertNull(m.onFrame(palmUp = true, palmDown = false, nowMs = 100L))
        assertNull(m.onFrame(palmUp = false, palmDown = false, nowMs = 150L))
        // Pose resumes but the hold timer restarted, so 150ms more isn't enough.
        assertNull(m.onFrame(palmUp = true, palmDown = false, nowMs = 200L))
        assertNull(m.onFrame(palmUp = true, palmDown = false, nowMs = 450L))
        assertFalse(m.isVisible)
    }

    @Test
    fun `menu stays visible while the hand is lowered or tilted (not flipped)`() {
        val m = machine()
        m.onFrame(palmUp = true, palmDown = false, nowMs = 0L)
        m.onFrame(palmUp = true, palmDown = false, nowMs = 300L)
        assertTrue(m.isVisible)
        // Neither clearly up nor clearly down (hand lowered / tilted): stays open.
        assertNull(m.onFrame(palmUp = false, palmDown = false, nowMs = 1000L))
        assertTrue(m.isVisible)
    }

    @Test
    fun `flipping the palm down dismisses the menu`() {
        val m = machine()
        m.onFrame(palmUp = true, palmDown = false, nowMs = 0L)
        m.onFrame(palmUp = true, palmDown = false, nowMs = 300L)
        assertTrue(m.isVisible)
        assertEquals(HandMenuTransition.Hide, m.onFrame(palmUp = false, palmDown = true, nowMs = 400L))
        assertFalse(m.isVisible)
    }

    @Test
    fun `re-reveals after a flip-dismiss when the palm is raised again`() {
        val m = machine()
        m.onFrame(palmUp = true, palmDown = false, nowMs = 0L)
        m.onFrame(palmUp = true, palmDown = false, nowMs = 300L)
        m.onFrame(palmUp = false, palmDown = true, nowMs = 400L) // flip to dismiss
        assertFalse(m.isVisible)
        // Raise and hold again.
        assertNull(m.onFrame(palmUp = true, palmDown = false, nowMs = 500L))
        assertEquals(HandMenuTransition.Reveal, m.onFrame(palmUp = true, palmDown = false, nowMs = 800L))
        assertTrue(m.isVisible)
    }

    @Test
    fun `dismiss hides the menu`() {
        val m = machine()
        m.onFrame(palmUp = true, palmDown = false, nowMs = 0L)
        m.onFrame(palmUp = true, palmDown = false, nowMs = 300L)
        assertEquals(HandMenuTransition.Hide, m.onDismiss())
        assertFalse(m.isVisible)
    }

    @Test
    fun `dismiss when already hidden is a no-op`() {
        val m = machine()
        assertNull(m.onDismiss())
    }
}
