package com.ghosttyxr.app.terminal.spatial

/**
 * Maps a cockpit pane count (1..7) to a list of per-row pane counts for the adaptive grid.
 *
 * Balanced rows of at most 3, biased so the top row is never smaller than the bottom:
 *
 * ```
 *   1 -> [1]      4 -> [2, 2]
 *   2 -> [2]      5 -> [3, 2]
 *   3 -> [2, 1]   6 -> [3, 3]
 *                 7 -> [3, 2, 2]
 * ```
 *
 * Pure + host-JVM testable; the spatial and 2D grids both consume this so their geometry
 * stays in lockstep.
 */
object PaneGridLayout {
    const val MAX_PANES = 7

    fun rowsFor(count: Int): List<Int> {
        require(count in 1..MAX_PANES) { "pane count $count out of range 1..$MAX_PANES" }
        return when (count) {
            1 -> listOf(1)
            2 -> listOf(2)
            3 -> listOf(2, 1)
            4 -> listOf(2, 2)
            5 -> listOf(3, 2)
            6 -> listOf(3, 3)
            else -> listOf(3, 2, 2)
        }
    }

    /** True if any row holds 3 panes — callers shrink per-pane width when so. */
    fun hasWideRow(count: Int): Boolean = rowsFor(count).any { it >= 3 }
}
