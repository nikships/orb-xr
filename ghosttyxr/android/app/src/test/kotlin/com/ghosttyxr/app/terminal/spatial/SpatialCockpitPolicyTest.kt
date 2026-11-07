package com.ghosttyxr.app.terminal.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for the spatial-cockpit policy selectors. These stay free of XR
 * types so they pin behavior without an emulator: which move mode the grid uses, that
 * resize handles keep the panel's aspect ratio, and when the coach card may follow.
 */
class SpatialCockpitPolicyTest {

    @Test
    fun `locked layout is fixed regardless of the surface toggle`() {
        assertEquals(PaneMoveMode.FIXED, resolvePaneMoveMode(movable = false, snapToSurfaces = false))
        assertEquals(PaneMoveMode.FIXED, resolvePaneMoveMode(movable = false, snapToSurfaces = true))
    }

    @Test
    fun `unlocked panes move freely by default`() {
        assertEquals(PaneMoveMode.FREE, resolvePaneMoveMode(movable = true, snapToSurfaces = false))
    }

    @Test
    fun `opt-in surfaces switch unlocked panes to anchored moves`() {
        assertEquals(PaneMoveMode.ANCHORED, resolvePaneMoveMode(movable = true, snapToSurfaces = true))
    }

    @Test
    fun `spatial resize keeps the panel aspect ratio`() {
        assertTrue(PaneResizePolicy.MAINTAIN_ASPECT_RATIO)
    }

    @Test
    fun `coach follow needs a live XR session`() {
        assertTrue(coachFollowAvailable(sessionPresent = true))
        assertFalse(coachFollowAvailable(sessionPresent = false))
    }
}
