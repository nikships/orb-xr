package com.ghosttyxr.app.ui.pointer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CockpitSpaceTest {
    @Test
    fun layoutTilesPanesPerGridRows() {
        // 2 terminals + mirror = 3 panes -> rows [2, 1]; panel strip on the right.
        val layout = CockpitSpace.layout(terminalPaneCount = 2, hasMirror = true)

        assertEquals(setOf("pane-0", "pane-1", CockpitSpace.MIRROR_TARGET, CockpitSpace.PANEL_TARGET), layout.keys)
        val pane0 = layout.getValue("pane-0")
        val pane1 = layout.getValue("pane-1")
        val mirror = layout.getValue(CockpitSpace.MIRROR_TARGET)
        // Top row: pane-0 then pane-1, adjacent.
        assertEquals(pane0.right, pane1.left, 1e-3f)
        assertEquals(pane0.top, pane1.top, 1e-3f)
        // Mirror is on the second row, centered under the two.
        assertEquals(pane0.bottom, mirror.top, 1e-3f)
        assertEquals((pane0.left + pane1.right) / 2f, (mirror.left + mirror.right) / 2f, 1e-3f)
    }

    @Test
    fun panelSitsRightOfGrid() {
        val layout = CockpitSpace.layout(terminalPaneCount = 1, hasMirror = true)
        val panel = layout.getValue(CockpitSpace.PANEL_TARGET)
        val gridRight = layout.filterKeys { it != CockpitSpace.PANEL_TARGET }.values.maxOf { it.right }

        assertEquals(gridRight, panel.left, 1e-3f)
        assertEquals(CockpitSpace.PANEL_WIDTH, panel.width, 1e-3f)
    }

    @Test
    fun hitTestResolvesTargetsAndGaps() {
        val layout = CockpitSpace.layout(terminalPaneCount = 2, hasMirror = true)
        val pane1 = layout.getValue("pane-1")

        assertEquals("pane-1", CockpitSpace.hitTest(layout, pane1.left + 1f, pane1.top + 1f))
        // Bottom row holds only the mirror (centered); its left flank is empty space.
        val mirror = layout.getValue(CockpitSpace.MIRROR_TARGET)
        assertNull(CockpitSpace.hitTest(layout, mirror.left - 10f, mirror.top + 10f))
    }

    @Test
    fun spawnPointSitsJustOutsideMirrorEdge() {
        val layout = CockpitSpace.layout(terminalPaneCount = 2, hasMirror = true)
        val mirror = layout.getValue(CockpitSpace.MIRROR_TARGET)

        val (rightX, rightY) = requireNotNull(CockpitSpace.spawnPoint(layout, edge = "right", yFraction = 0.5f))
        assertEquals(mirror.right + CockpitSpace.SPAWN_MARGIN, rightX, 1e-3f)
        assertEquals(mirror.top + mirror.height / 2f, rightY, 1e-3f)
        // Spawn point must not be inside the mirror rect (or it would bounce straight back).
        assertTrue(CockpitSpace.hitTest(layout, rightX, rightY) != CockpitSpace.MIRROR_TARGET)

        val (leftX, _) = requireNotNull(CockpitSpace.spawnPoint(layout, edge = "left", yFraction = 0f))
        assertEquals(mirror.left - CockpitSpace.SPAWN_MARGIN, leftX, 1e-3f)
    }

    @Test
    fun spawnPointNullWithoutMirror() {
        val layout = CockpitSpace.layout(terminalPaneCount = 2, hasMirror = false)
        assertNull(CockpitSpace.spawnPoint(layout, edge = "right", yFraction = 0.5f))
    }

    @Test
    fun centerSpawnTargetsFirstPane() {
        val layout = CockpitSpace.layout(terminalPaneCount = 2, hasMirror = true, hasPanel = false)
        val pane0 = layout.getValue("pane-0")
        val (x, y) = requireNotNull(CockpitSpace.spawnPoint(layout, edge = "center", yFraction = 0.5f))
        assertEquals(pane0.left + pane0.width / 2f, x, 1e-3f)
        assertEquals(pane0.top + pane0.height / 2f, y, 1e-3f)
        assertEquals("pane-0", CockpitSpace.hitTest(layout, x, y))
    }

    @Test
    fun fractionRoundTripsForPointerReturn() {
        val layout = CockpitSpace.layout(terminalPaneCount = 1, hasMirror = true)
        val mirror = layout.getValue(CockpitSpace.MIRROR_TARGET)
        val x = mirror.left + mirror.width * 0.25f
        val y = mirror.top + mirror.height * 0.75f

        val (fx, fy) = CockpitSpace.fractionIn(mirror, x, y)
        assertEquals(0.25f, fx, 1e-4f)
        assertEquals(0.75f, fy, 1e-4f)
    }

    @Test
    fun boundsCoverGridAndPanel() {
        val layout = CockpitSpace.layout(terminalPaneCount = 3, hasMirror = true)
        val bounds = CockpitSpace.bounds(layout)

        layout.values.forEach { rect ->
            assertTrue(rect.left >= bounds.left && rect.right <= bounds.right)
            assertTrue(rect.top >= bounds.top && rect.bottom <= bounds.bottom)
        }
        assertNotNull(CockpitSpace.hitTest(layout, bounds.left + 1f, bounds.top + 1f))
    }

    @Test
    fun sevenSlotLayoutIsSupported() {
        // 6 terminals + mirror = MAX_PANES; must not throw and must include all ids.
        val layout = CockpitSpace.layout(terminalPaneCount = 6, hasMirror = true)
        assertEquals(8, layout.size) // 6 panes + mirror + panel
        (0 until 6).forEach { assertNotNull(layout[CockpitSpace.paneTarget(it)]) }
        assertNotNull(layout[CockpitSpace.MIRROR_TARGET])
    }
}
