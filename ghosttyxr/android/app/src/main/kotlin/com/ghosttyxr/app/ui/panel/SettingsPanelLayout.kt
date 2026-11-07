package com.ghosttyxr.app.ui.panel

/** A named Compose Grid area using the API's one-based row/column indices. */
internal data class SettingsGridPlacement(
    val areaId: String,
    val row: Int,
    val column: Int,
)

/**
 * Pure breakpoint and named-area planning for the adaptive settings Grid.
 * Compose owns measurement and track sizing; this helper only maps stable card
 * IDs into explicit row-major areas so the behavior stays host-JVM testable.
 */
internal object SettingsPanelLayout {
    const val TWO_COLUMN_MIN_WIDTH_DP = 620f
    const val THREE_COLUMN_MIN_WIDTH_DP = 980f

    fun columnCount(widthDp: Float): Int = when {
        widthDp < TWO_COLUMN_MIN_WIDTH_DP -> 1
        widthDp < THREE_COLUMN_MIN_WIDTH_DP -> 2
        else -> 3
    }

    fun placements(areaIds: List<String>, columns: Int): List<SettingsGridPlacement> {
        require(columns in 1..3) { "columns must be 1..3, got $columns" }
        require(areaIds.distinct().size == areaIds.size) { "area IDs must be unique" }
        return areaIds.mapIndexed { index, id ->
            SettingsGridPlacement(
                areaId = id,
                row = index / columns + 1,
                column = index % columns + 1,
            )
        }
    }

    fun rowCount(itemCount: Int, columns: Int): Int {
        require(itemCount >= 0) { "itemCount must be non-negative" }
        require(columns in 1..3) { "columns must be 1..3, got $columns" }
        return if (itemCount == 0) 0 else (itemCount + columns - 1) / columns
    }
}
