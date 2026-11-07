package com.ghosttyxr.app.terminal.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for the read loop's adaptive idle backoff ramp
 * ([nextIdlePollDelayMs]) — no Android framework, no JNI, no XR AVD.
 * Runs under `./gradlew :app:testDebugUnitTest`.
 */
class IdleBackoffTest {

    @Test
    fun staysAtFloorBeforeBackoffThreshold() {
        var delay = IDLE_POLL_FLOOR_MS
        for (empty in 1 until IDLE_BACKOFF_AFTER_EMPTY_READS) {
            delay = nextIdlePollDelayMs(empty, delay)
            assertEquals("empty=$empty must stay at floor", IDLE_POLL_FLOOR_MS, delay)
        }
    }

    @Test
    fun rampsByDoublingAfterThresholdAndClampsAtCap() {
        var delay = IDLE_POLL_FLOOR_MS
        // First several reads keep us at the floor.
        repeat(IDLE_BACKOFF_AFTER_EMPTY_READS - 1) { delay = nextIdlePollDelayMs(it + 1, delay) }
        assertEquals(IDLE_POLL_FLOOR_MS, delay)

        // From the threshold on, the interval doubles each idle read until
        // it saturates at the cap and never exceeds it.
        var empty = IDLE_BACKOFF_AFTER_EMPTY_READS
        var previous = delay
        var reachedCap = false
        repeat(20) {
            delay = nextIdlePollDelayMs(empty, delay)
            assertTrue("delay $delay above cap", delay <= IDLE_POLL_CAP_MS)
            assertTrue("delay $delay below floor", delay >= IDLE_POLL_FLOOR_MS)
            if (delay == IDLE_POLL_CAP_MS) reachedCap = true
            if (!reachedCap) assertEquals(previous * 2, delay)
            previous = delay
            empty += 1
        }
        assertTrue("ramp never reached the cap", reachedCap)
        assertEquals("must settle at the cap", IDLE_POLL_CAP_MS, delay)
    }

    @Test
    fun capIsAtLeastTenxFloorSoIdleWakeupsDropTenx() {
        // Acceptance criterion: idle wakeups per pane drop ~10x. At the cap
        // we poll cap/floor times slower than the 60fps idle floor.
        assertTrue(
            "cap/floor=${IDLE_POLL_CAP_MS / IDLE_POLL_FLOOR_MS} should be ~10x",
            IDLE_POLL_CAP_MS / IDLE_POLL_FLOOR_MS >= 10,
        )
    }
}
