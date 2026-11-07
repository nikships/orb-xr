package com.ghosttyxr.app.ui.pointer

import com.ghosttyxr.app.terminal.spatial.PaneGridLayout

/** Axis-aligned rectangle in logical cockpit units. Pure Kotlin, host-JVM testable. */
data class CockpitRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun contains(x: Float, y: Float): Boolean =
        x >= left && x < right && y >= top && y < bottom
}

/**
 * The logical 2D space the Symbiosis XR cursor roams. Pane cells are tiled gaplessly
 * per [PaneGridLayout]'s rows (matching the visual grid's adjacency), with the control
 * panel as a strip on the grid's right edge. v1 limitation: this logical grid — not
 * user-dragged 3D pane positions — defines cursor adjacency.
 *
 * All functions are pure so layout, hit-testing, spawn, and return math run in plain
 * JVM unit tests.
 */
object CockpitSpace {
    const val MIRROR_TARGET = "mirror"
    const val PANEL_TARGET = "panel"

    fun paneTarget(paneId: Int): String = "pane-$paneId"

    /** Inverse of [paneTarget]: the pane index for a target id, or null if not a pane. */
    fun paneIndexOf(id: String): Int? =
        if (id.startsWith("pane-")) id.removePrefix("pane-").toIntOrNull() else null

    const val CELL_WIDTH = 1000f
    const val CELL_HEIGHT = 720f
    const val PANEL_WIDTH = 480f

    /** How far outside the mirror cell the cursor spawns on handoff, so it doesn't
     *  instantly hit-test back into the mirror and bounce to the Mac. */
    const val SPAWN_MARGIN = 4f

    /**
     * Build the logical layout for [terminalPaneCount] terminal panes plus (when
     * [hasMirror]) the mirror pane appended at the last grid slot, plus (when
     * [hasPanel]) the control panel strip to the right of the grid.
     */
    fun layout(
        terminalPaneCount: Int,
        hasMirror: Boolean,
        hasPanel: Boolean = true,
    ): Map<String, CockpitRect> {
        val total = terminalPaneCount + if (hasMirror) 1 else 0
        if (total == 0) return emptyMap()
        val rows = PaneGridLayout.rowsFor(total.coerceAtMost(PaneGridLayout.MAX_PANES))
        val gridWidth = rows.max() * CELL_WIDTH
        val out = LinkedHashMap<String, CockpitRect>()
        var index = 0
        rows.forEachIndexed { rowIndex, count ->
            val rowLeft = (gridWidth - count * CELL_WIDTH) / 2f
            for (column in 0 until count) {
                val id = if (hasMirror && index == terminalPaneCount) MIRROR_TARGET else paneTarget(index)
                val left = rowLeft + column * CELL_WIDTH
                val top = rowIndex * CELL_HEIGHT
                out[id] = CockpitRect(left, top, left + CELL_WIDTH, top + CELL_HEIGHT)
                index++
            }
        }
        if (hasPanel) {
            out[PANEL_TARGET] = CockpitRect(
                left = gridWidth,
                top = 0f,
                right = gridWidth + PANEL_WIDTH,
                bottom = rows.size * CELL_HEIGHT,
            )
        }
        return out
    }

    /** Union bounding box of every rect in [layout]. */
    fun bounds(layout: Map<String, CockpitRect>): CockpitRect {
        if (layout.isEmpty()) return CockpitRect(0f, 0f, 0f, 0f)
        return CockpitRect(
            left = layout.values.minOf { it.left },
            top = layout.values.minOf { it.top },
            right = layout.values.maxOf { it.right },
            bottom = layout.values.maxOf { it.bottom },
        )
    }

    /** Which target (if any) contains cockpit point (x, y). */
    fun hitTest(layout: Map<String, CockpitRect>, x: Float, y: Float): String? =
        layout.entries.firstOrNull { it.value.contains(x, y) }?.key

    /**
     * Where the XR cursor appears when the Mac hands the pointer off.
     *
     * - `"left"` / `"right"`: just outside that edge of the mirror cell (legacy edge handoff).
     * - `"center"` / `"toggle"`: center of the first terminal pane (keyboard toggle).
     */
    fun spawnPoint(layout: Map<String, CockpitRect>, edge: String, yFraction: Float): Pair<Float, Float>? {
        if (layout[MIRROR_TARGET] == null) return null
        if (edge == "center" || edge == "toggle") {
            val pane = layout.entries
                .firstOrNull { it.key.startsWith("pane-") }
                ?.value
                ?: layout.values.firstOrNull()
                ?: return null
            return (pane.left + pane.width / 2f) to (pane.top + pane.height / 2f)
        }
        val mirror = layout.getValue(MIRROR_TARGET)
        val x = if (edge == "left") mirror.left - SPAWN_MARGIN else mirror.right + SPAWN_MARGIN
        val y = mirror.top + yFraction.coerceIn(0f, 1f) * mirror.height
        return x to y
    }

    /**
     * Fractional (0..1) position of cockpit point (x, y) inside [rect] — the payload
     * for pointer-return (mirror cell) and for mapping onto a target's pixel bounds.
     */
    fun fractionIn(rect: CockpitRect, x: Float, y: Float): Pair<Float, Float> {
        val fx = if (rect.width > 0f) (x - rect.left) / rect.width else 0f
        val fy = if (rect.height > 0f) (y - rect.top) / rect.height else 0f
        return fx to fy
    }
}
