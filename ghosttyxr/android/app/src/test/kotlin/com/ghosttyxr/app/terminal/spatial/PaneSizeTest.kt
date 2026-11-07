package com.ghosttyxr.app.terminal.spatial

import org.junit.Assert.assertEquals
import org.junit.Test

class PaneSizeTest {

    @Test
    fun `dimensions smaller than the minimum are constrained independently`() {
        assertEquals(
            PaneSize(widthPx = 320f, heightPx = 480f),
            PaneSize(widthPx = 0f, heightPx = 480f).constrained(
                minWidthPx = 320f,
                minHeightPx = 240f,
            ),
        )
        assertEquals(
            PaneSize(widthPx = 640f, heightPx = 240f),
            PaneSize(widthPx = 640f, heightPx = -1f).constrained(
                minWidthPx = 320f,
                minHeightPx = 240f,
            ),
        )
    }

    @Test
    fun `valid dimensions above the minimum are unchanged`() {
        assertEquals(
            PaneSize(widthPx = 800f, heightPx = 600f),
            PaneSize(widthPx = 800f, heightPx = 600f).constrained(
                minWidthPx = 320f,
                minHeightPx = 240f,
            ),
        )
    }

    @Test
    fun `non-finite dimensions fall back to the minimum`() {
        assertEquals(
            PaneSize(widthPx = 320f, heightPx = 240f),
            PaneSize(widthPx = Float.NaN, heightPx = Float.POSITIVE_INFINITY).constrained(
                minWidthPx = 320f,
                minHeightPx = 240f,
            ),
        )
    }
}
