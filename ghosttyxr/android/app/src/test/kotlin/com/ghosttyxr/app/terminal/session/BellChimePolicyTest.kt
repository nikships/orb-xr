package com.ghosttyxr.app.terminal.session

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM coverage for [BellChimePolicy] — the per-tick coalescing that keeps a bell
 * loop from machine-gunning the spatial audio pool.
 */
class BellChimePolicyTest {

    @Test
    fun `zero bells emits zero chimes`() {
        assertEquals(0, BellChimePolicy.chimesToEmit(0))
    }

    @Test
    fun `a few bells pass through one-to-one`() {
        assertEquals(1, BellChimePolicy.chimesToEmit(1))
        assertEquals(2, BellChimePolicy.chimesToEmit(2))
        assertEquals(3, BellChimePolicy.chimesToEmit(3))
    }

    @Test
    fun `a bell burst is capped at the per-tick max`() {
        assertEquals(
            BellChimePolicy.MAX_CHIMES_PER_TICK,
            BellChimePolicy.chimesToEmit(64),
        )
        assertEquals(
            BellChimePolicy.MAX_CHIMES_PER_TICK,
            BellChimePolicy.chimesToEmit(Int.MAX_VALUE),
        )
    }

    @Test
    fun `negative drains clamp to zero`() {
        assertEquals(0, BellChimePolicy.chimesToEmit(-1))
    }
}
