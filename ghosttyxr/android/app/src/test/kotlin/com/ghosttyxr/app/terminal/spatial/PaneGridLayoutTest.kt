package com.ghosttyxr.app.terminal.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the adaptive grid row split. Each layout's row counts must sum back
 * to the pane count, and the specific shapes are pinned so the spatial and 2D grids agree.
 */
class PaneGridLayoutTest {

    @Test
    fun `row shapes match the spec table`() {
        assertEquals(listOf(1), PaneGridLayout.rowsFor(1))
        assertEquals(listOf(2), PaneGridLayout.rowsFor(2))
        assertEquals(listOf(2, 1), PaneGridLayout.rowsFor(3))
        assertEquals(listOf(2, 2), PaneGridLayout.rowsFor(4))
        assertEquals(listOf(3, 2), PaneGridLayout.rowsFor(5))
        assertEquals(listOf(3, 3), PaneGridLayout.rowsFor(6))
        assertEquals(listOf(3, 2, 2), PaneGridLayout.rowsFor(7))
    }

    @Test
    fun `row counts always sum to the pane count`() {
        for (count in 1..PaneGridLayout.MAX_PANES) {
            assertEquals(count, PaneGridLayout.rowsFor(count).sum())
        }
    }

    @Test
    fun `hasWideRow true when any row has three panes`() {
        assertFalse(PaneGridLayout.hasWideRow(1))
        assertFalse(PaneGridLayout.hasWideRow(4))
        assertTrue(PaneGridLayout.hasWideRow(5))
        assertTrue(PaneGridLayout.hasWideRow(6))
        assertTrue(PaneGridLayout.hasWideRow(7))
    }

    @Test
    fun `slot assignments preserve surviving pane positions across mutations`() {
        val assignments = PaneSlotAssignments(slotCount = 7)

        val initial = assignments.assign(listOf("one", "two"))
        val afterAdd = assignments.assign(listOf("one", "two", "three"))
        val afterRemove = assignments.assign(listOf("one", "three"))

        assertEquals(initial.getValue(0), afterAdd.getValue(0))
        assertEquals(initial.getValue(1), afterAdd.getValue(1))
        assertEquals(initial.getValue(0), afterRemove.getValue(0))
        assertEquals(afterAdd.getValue(2), afterRemove.getValue(1))
    }

    @Test
    fun `only populated slots create input surfaces`() {
        assertFalse(paneRequiresInputSurface(panePresent = false))
        assertTrue(paneRequiresInputSurface(panePresent = true))
    }


    @Test
    fun `pane offsets apply independent of move interaction`() {
        // Lock freezes interaction but must keep rendering stored offsets.
        assertTrue(paneOffsetApplied(hasPaneKey = true))
        assertFalse(paneOffsetApplied(hasPaneKey = false))
        assertFalse(paneInteractionEnabled(interactionRequested = false))
        assertTrue(paneInteractionEnabled(interactionRequested = true))
    }

    @Test
    fun `pane interactions honor the requested mode`() {
        assertFalse(paneInteractionEnabled(interactionRequested = false))
        assertTrue(paneInteractionEnabled(interactionRequested = true))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `duplicate pane keys are rejected`() {
        PaneSlotAssignments(slotCount = 7).assign(listOf("one", "one"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero panes is rejected`() {
        PaneGridLayout.rowsFor(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `eight panes is rejected`() {
        PaneGridLayout.rowsFor(8)
    }
}
