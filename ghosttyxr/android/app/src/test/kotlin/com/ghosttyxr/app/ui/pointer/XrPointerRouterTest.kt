package com.ghosttyxr.app.ui.pointer

import com.ghosttyxr.app.terminal.windowmirror.SymbiosisPointerEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XrPointerRouterTest {
    private val returns = mutableListOf<Pair<Float, Float>>()
    private val clicks = mutableListOf<String>()
    private val scrolls = mutableListOf<Pair<String, Int>>()

    // Matches production during a display share: 2 terminals + mirror, no control panel.
    private val layout = CockpitSpace.layout(terminalPaneCount = 2, hasMirror = true, hasPanel = false)

    private fun newRouter() = XrPointerRouter(
        onPointerReturn = { x, y -> returns.add(x to y) },
        onTargetClicked = { clicks.add(it) },
        onTargetScrolled = { id, lines -> scrolls.add(id to lines) },
    ).also { it.updateLayout(layout) }

    @Test
    fun handoffSpawnsCursorOutsideMirror() {
        val router = newRouter()
        router.handle(SymbiosisPointerEvent.Handoff(seq = 1, edge = "right", y = 0.5f))

        val cursor = router.cursor.value
        assertTrue(cursor.active)
        assertTrue(cursor.targetId != CockpitSpace.MIRROR_TARGET)
    }

    @Test
    fun buttonDownFocusesResolvedPane() {
        val router = newRouter()
        router.handle(SymbiosisPointerEvent.Handoff(seq = 1, edge = "right", y = 0.5f))
        val pane0 = layout.getValue("pane-0")
        moveCursorTo(router, seq = 2, pane0.left + 10f, pane0.top + 10f)

        router.handle(SymbiosisPointerEvent.Button(seq = 3, button = "left", action = "down"))

        assertEquals(listOf("pane-0"), clicks)
    }

    @Test
    fun buttonDownOverEmptySpaceDoesNotClick() {
        val router = newRouter()
        // Spawn point sits just outside the mirror edge — empty space, no pane under it.
        router.handle(SymbiosisPointerEvent.Handoff(seq = 1, edge = "right", y = 0.5f))
        assertNull(router.cursor.value.targetId)
        router.handle(SymbiosisPointerEvent.Button(seq = 2, button = "left", action = "down"))
        assertTrue(clicks.isEmpty())
    }

    @Test
    fun scrollAccumulatesIntoWholeLinesAndInvertsMacWheel() {
        val router = newRouter()
        router.handle(SymbiosisPointerEvent.Handoff(seq = 1, edge = "right", y = 0.5f))
        val pane0 = layout.getValue("pane-0")
        moveCursorTo(router, seq = 2, pane0.left + 10f, pane0.top + 10f)

        // Fractional trackpad deltas accumulate; Mac dy is inverted so natural wheel
        // matches terminal scrollback direction.
        router.handle(SymbiosisPointerEvent.Scroll(seq = 3, dx = 0f, dy = 0.6f))
        assertTrue(scrolls.isEmpty())
        router.handle(SymbiosisPointerEvent.Scroll(seq = 4, dx = 0f, dy = 0.6f))
        assertEquals(listOf("pane-0" to -1), scrolls)
    }

    @Test
    fun reentryDoesNotReturnPointerToMac() {
        val router = newRouter()
        router.handle(SymbiosisPointerEvent.Handoff(seq = 1, edge = "right", y = 0.5f))
        val mirror = layout.getValue(CockpitSpace.MIRROR_TARGET)
        moveCursorTo(router, seq = 2, mirror.left + mirror.width / 2f, mirror.top + mirror.height / 2f)

        assertTrue(returns.isEmpty())
        assertTrue(router.cursor.value.active)
        assertEquals(CockpitSpace.MIRROR_TARGET, router.cursor.value.targetId)
    }

    @Test
    fun centerHandoffSpawnsOnFirstPane() {
        val router = newRouter()
        router.handle(SymbiosisPointerEvent.Handoff(seq = 1, edge = "center", y = 0.5f))

        val cursor = router.cursor.value
        assertTrue(cursor.active)
        assertEquals("pane-0", cursor.targetId)
        val pane0 = layout.getValue("pane-0")
        assertEquals(pane0.left + pane0.width / 2f, cursor.x, 1e-3f)
        assertEquals(pane0.top + pane0.height / 2f, cursor.y, 1e-3f)
    }

    @Test
    fun sequenceGapReleasesHeldPress() {
        val router = newRouter()
        router.handle(SymbiosisPointerEvent.Handoff(seq = 1, edge = "right", y = 0.5f))
        val pane0 = layout.getValue("pane-0")
        moveCursorTo(router, seq = 2, pane0.left + 10f, pane0.top + 10f)
        router.handle(SymbiosisPointerEvent.Button(seq = 3, button = "left", action = "down"))

        // Skip seq 4: a gap. The held press is released; no crash, cursor stays active.
        router.handle(SymbiosisPointerEvent.Scroll(seq = 5, dx = 0f, dy = 5f))
        assertTrue(router.cursor.value.active)
    }

    @Test
    fun deactivateClearsCursor() {
        val router = newRouter()
        router.handle(SymbiosisPointerEvent.Handoff(seq = 1, edge = "right", y = 0.5f))
        router.deactivate()
        assertFalse(router.cursor.value.active)
        assertNull(router.cursor.value.targetId)
    }

    /** Drive the cursor to an absolute cockpit point via a single delta from its current spot. */
    private fun moveCursorTo(router: XrPointerRouter, seq: Long, x: Float, y: Float) {
        val current = router.cursor.value
        // sensitivity is 1.6; invert it so the resulting cursor lands on (x, y).
        val dx = (x - current.x) / 1.6f
        val dy = (y - current.y) / 1.6f
        router.handle(SymbiosisPointerEvent.Delta(seq = seq, dx = dx, dy = dy))
    }
}
