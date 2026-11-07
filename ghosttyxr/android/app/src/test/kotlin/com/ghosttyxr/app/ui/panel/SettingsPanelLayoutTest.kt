package com.ghosttyxr.app.ui.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SettingsPanelLayoutTest {

    @Test
    fun `column count follows explicit width breakpoints`() {
        assertEquals(1, SettingsPanelLayout.columnCount(0f))
        assertEquals(1, SettingsPanelLayout.columnCount(SettingsPanelLayout.TWO_COLUMN_MIN_WIDTH_DP - 1f))
        assertEquals(2, SettingsPanelLayout.columnCount(SettingsPanelLayout.TWO_COLUMN_MIN_WIDTH_DP))
        assertEquals(2, SettingsPanelLayout.columnCount(SettingsPanelLayout.THREE_COLUMN_MIN_WIDTH_DP - 1f))
        assertEquals(3, SettingsPanelLayout.columnCount(SettingsPanelLayout.THREE_COLUMN_MIN_WIDTH_DP))
    }

    @Test
    fun `named areas fill rows left to right`() {
        assertEquals(
            listOf(
                SettingsGridPlacement("terminals", 1, 1),
                SettingsGridPlacement("appearance", 1, 2),
                SettingsGridPlacement("text", 1, 3),
                SettingsGridPlacement("pro", 2, 1),
                SettingsGridPlacement("voice", 2, 2),
            ),
            SettingsPanelLayout.placements(
                listOf("terminals", "appearance", "text", "pro", "voice"),
                columns = 3,
            ),
        )
    }

    @Test
    fun `single column preserves card order`() {
        assertEquals(
            listOf(
                SettingsGridPlacement("a", 1, 1),
                SettingsGridPlacement("b", 2, 1),
                SettingsGridPlacement("c", 3, 1),
            ),
            SettingsPanelLayout.placements(listOf("a", "b", "c"), columns = 1),
        )
    }

    @Test
    fun `row count rounds up partial final row`() {
        assertEquals(0, SettingsPanelLayout.rowCount(0, 3))
        assertEquals(1, SettingsPanelLayout.rowCount(1, 3))
        assertEquals(2, SettingsPanelLayout.rowCount(4, 3))
        assertEquals(3, SettingsPanelLayout.rowCount(5, 2))
    }

    @Test
    fun `duplicate area IDs are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            SettingsPanelLayout.placements(listOf("pro", "pro"), columns = 2)
        }
    }
}
