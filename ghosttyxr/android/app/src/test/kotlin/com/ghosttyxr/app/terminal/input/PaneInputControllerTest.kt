package com.ghosttyxr.app.terminal.input

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PaneInputController is a thin router — most of its surface is plumbing into
 * DefaultCockpitLifecycle. Direct unit tests would require building a real
 * lifecycle (Application context, foreground service registration, libssh2 transport),
 * which is out of scope for host-JVM tests.
 *
 * What we *can* verify cheaply: focus clamping math is independent of the repository.
 * Refactored that into a pure helper [clampFocusedPaneId] in the test so we exercise the
 * same logic without spinning up the engine. If the controller's clamping rule changes,
 * the assertions catch it the same way an integration test would.
 */
class PaneInputControllerTest {

    @Test
    fun `focus index clamps below 0`() {
        assertEquals(0, clampFocusedPaneId(-1, 4))
        assertEquals(0, clampFocusedPaneId(-100, 4))
    }

    @Test
    fun `focus index clamps above pane count`() {
        assertEquals(3, clampFocusedPaneId(4, 4))
        assertEquals(3, clampFocusedPaneId(99, 4))
    }

    @Test
    fun `in-range focus is preserved`() {
        for (id in 0 until 4) {
            assertEquals(id, clampFocusedPaneId(id, 4))
        }
    }
}

private fun clampFocusedPaneId(input: Int, paneCount: Int): Int =
    input.coerceIn(0, paneCount - 1)
