package com.ghosttyxr.app.intro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureCoachMachineTest {

    @Test
    fun `initial state counts nothing as practiced`() {
        val m = GestureCoachMachine()
        assertFalse(m.progress.lockPracticed)
        assertFalse(m.progress.petPracticed)
        assertEquals(0, m.progress.lockToggles)
    }

    @Test
    fun `unchanged frames never accrue progress`() {
        val m = GestureCoachMachine(initialLayoutLocked = true, initialPetVisible = false)
        repeat(5) { m.onFrame(layoutLocked = true, petVisible = false) }
        assertFalse(m.progress.lockPracticed)
        assertFalse(m.progress.petSummoned)
        assertTrue(m.progress.layoutLocked)
    }

    @Test
    fun `one lock flip completes the practice and echoes the live state`() {
        val m = GestureCoachMachine(initialLayoutLocked = false)
        val progress = m.onFrame(layoutLocked = true, petVisible = false)
        assertTrue(progress.lockPracticed)
        assertTrue(progress.layoutLocked)
        assertEquals(1, progress.lockToggles)
    }

    @Test
    fun `every subsequent flip keeps counting and tracks the flag`() {
        val m = GestureCoachMachine(initialLayoutLocked = false)
        m.onFrame(layoutLocked = true, petVisible = false)
        m.onFrame(layoutLocked = false, petVisible = false)
        val progress = m.onFrame(layoutLocked = true, petVisible = false)
        assertEquals(3, progress.lockToggles)
        assertTrue(progress.layoutLocked)
        assertTrue(progress.lockPracticed)
    }

    @Test
    fun `starting locked does not count until the flag actually flips`() {
        val m = GestureCoachMachine(initialLayoutLocked = true)
        assertFalse(m.onFrame(layoutLocked = true, petVisible = false).lockPracticed)
        assertTrue(m.onFrame(layoutLocked = false, petVisible = false).lockPracticed)
    }

    @Test
    fun `pet practice requires summon then dismiss in order`() {
        val m = GestureCoachMachine()
        val summoned = m.onFrame(layoutLocked = false, petVisible = true)
        assertTrue(summoned.petSummoned)
        assertFalse(summoned.petPracticed)
        val dismissed = m.onFrame(layoutLocked = false, petVisible = false)
        assertTrue(dismissed.petDismissed)
        assertTrue(dismissed.petPracticed)
    }

    @Test
    fun `pet already visible at start must be re-summoned to count`() {
        val m = GestureCoachMachine(initialPetVisible = true)
        // Hiding the pre-existing pet is not a tracked round trip.
        val hidden = m.onFrame(layoutLocked = false, petVisible = false)
        assertFalse(hidden.petSummoned)
        assertFalse(hidden.petPracticed)
        // A fresh summon + dismiss is.
        m.onFrame(layoutLocked = false, petVisible = true)
        assertTrue(m.onFrame(layoutLocked = false, petVisible = false).petPracticed)
    }

    @Test
    fun `practice latches once earned`() {
        val m = GestureCoachMachine()
        m.onFrame(layoutLocked = true, petVisible = true)
        m.onFrame(layoutLocked = true, petVisible = false)
        // Later frames (re-summon without dismissal, more lock flips) never lose credit.
        val progress = m.onFrame(layoutLocked = false, petVisible = true)
        assertTrue(progress.lockPracticed)
        assertTrue(progress.petPracticed)
    }

    @Test
    fun `both signals progress independently in the same frame`() {
        val m = GestureCoachMachine()
        val progress = m.onFrame(layoutLocked = true, petVisible = true)
        assertTrue(progress.lockPracticed)
        assertTrue(progress.petSummoned)
        assertFalse(progress.petPracticed)
    }
}
