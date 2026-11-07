package com.ghosttyxr.app.terminal.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalogScrollAccumulatorTest {

    @Test
    fun `dead zone ignores thumbstick drift`() {
        val accumulator = AnalogScrollAccumulator()

        assertFalse(accumulator.isActive(0.1f))
        assertEquals(0, accumulator.consume(0.1f, eventTimeMs = 10L))
    }

    @Test
    fun `held stick scrolls immediately and then at a bounded rate`() {
        val accumulator = AnalogScrollAccumulator(deadZone = 0.2f, linesPerSecond = 20f)

        assertEquals(1, accumulator.consume(1f, eventTimeMs = 1_000L))
        assertEquals(1, accumulator.consume(1f, eventTimeMs = 1_050L))
        assertEquals(1, accumulator.consume(1f, eventTimeMs = 1_100L))
    }

    @Test
    fun `direction reversal responds immediately`() {
        val accumulator = AnalogScrollAccumulator()

        assertEquals(1, accumulator.consume(1f, eventTimeMs = 1_000L))
        assertEquals(-1, accumulator.consume(-1f, eventTimeMs = 1_010L))
    }

    @Test
    fun `dominant vertical axis supports left and right sticks`() {
        assertEquals(0.7f, dominantVerticalControllerAxis(y = 0.7f, ry = 0.1f, rz = 0.2f))
        assertEquals(-0.8f, dominantVerticalControllerAxis(y = 0.1f, ry = -0.8f, rz = 0.2f))
        assertTrue(dominantVerticalControllerAxis(Float.NaN, Float.NaN, Float.NaN) == 0f)
    }
}
