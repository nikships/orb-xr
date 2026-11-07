package com.ghosttyxr.app.terminal.input

import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollbackLineAccumulatorTest {

    @Test
    fun `small deltas accumulate until one line`() {
        val accumulator = ScrollbackLineAccumulator(cellHeightPx = 10f)

        assertEquals(0, accumulator.consume(4f))
        assertEquals(0, accumulator.consume(5f))
        assertEquals(-1, accumulator.consume(1f))
    }

    @Test
    fun `positive drag maps to older scrollback`() {
        val accumulator = ScrollbackLineAccumulator(cellHeightPx = 12f)

        assertEquals(-2, accumulator.consume(24f))
    }

    @Test
    fun `negative drag maps to newer scrollback`() {
        val accumulator = ScrollbackLineAccumulator(cellHeightPx = 12f)

        assertEquals(2, accumulator.consume(-24f))
    }

    @Test
    fun `remainder survives direction changes`() {
        val accumulator = ScrollbackLineAccumulator(cellHeightPx = 10f)

        assertEquals(-1, accumulator.consume(15f))
        assertEquals(0, accumulator.consume(-4f))
        assertEquals(1, accumulator.consume(-11f))
    }

    @Test
    fun `invalid input is ignored`() {
        val accumulator = ScrollbackLineAccumulator(cellHeightPx = Float.NaN)

        assertEquals(0, accumulator.consume(Float.NaN))
        assertEquals(-3, accumulator.consume(3f))
    }
}
