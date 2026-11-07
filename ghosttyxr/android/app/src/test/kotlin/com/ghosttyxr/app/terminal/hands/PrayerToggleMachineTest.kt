package com.ghosttyxr.app.terminal.hands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrayerToggleMachineTest {

    private fun machine() = PrayerToggleMachine(holdMs = 300L)

    @Test
    fun `holding prayer past threshold toggles once`() {
        val m = machine()
        assertNull(m.onFrame(prayerPose = true, nowMs = 0L))
        assertNull(m.onFrame(prayerPose = true, nowMs = 200L))
        assertEquals(PrayerToggleEvent.Toggle, m.onFrame(prayerPose = true, nowMs = 300L))
        // Still holding: no second fire.
        assertNull(m.onFrame(prayerPose = true, nowMs = 500L))
        assertNull(m.onFrame(prayerPose = true, nowMs = 900L))
    }

    @Test
    fun `brief prayer below threshold does not toggle`() {
        val m = machine()
        assertNull(m.onFrame(prayerPose = true, nowMs = 0L))
        assertNull(m.onFrame(prayerPose = true, nowMs = 100L))
        assertNull(m.onFrame(prayerPose = false, nowMs = 150L))
        assertNull(m.onFrame(prayerPose = true, nowMs = 200L))
        assertNull(m.onFrame(prayerPose = true, nowMs = 450L))
    }

    @Test
    fun `leaving pose re-arms for another toggle`() {
        val m = machine()
        m.onFrame(prayerPose = true, nowMs = 0L)
        assertEquals(PrayerToggleEvent.Toggle, m.onFrame(prayerPose = true, nowMs = 300L))
        assertNull(m.onFrame(prayerPose = false, nowMs = 350L))
        assertNull(m.onFrame(prayerPose = true, nowMs = 400L))
        assertEquals(PrayerToggleEvent.Toggle, m.onFrame(prayerPose = true, nowMs = 700L))
    }
}
