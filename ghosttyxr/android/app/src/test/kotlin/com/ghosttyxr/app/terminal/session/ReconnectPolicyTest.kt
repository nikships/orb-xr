package com.ghosttyxr.app.terminal.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for [ReconnectPolicy] — no Android framework, no JNI,
 * no XR AVD. Runs under `./gradlew :app:testDebugUnitTest`.
 */
class ReconnectPolicyTest {

    @Test
    fun deterministicBackoffDoublesAndClampsAtCeiling() {
        val policy = ReconnectPolicy(
            maxAttempts = 8,
            baseDelayMs = 1_000L,
            maxDelayMs = 60_000L,
            jitter = false,
        )
        assertEquals(1_000L, policy.delayMsFor(1))
        assertEquals(2_000L, policy.delayMsFor(2))
        assertEquals(4_000L, policy.delayMsFor(3))
        assertEquals(8_000L, policy.delayMsFor(4))
        assertEquals(16_000L, policy.delayMsFor(5))
        assertEquals(32_000L, policy.delayMsFor(6))
        // 64_000 would exceed the ceiling — clamp. The old inline shift
        // capped at 32s here and never reached the advertised 60s.
        assertEquals(60_000L, policy.delayMsFor(7))
        assertEquals(60_000L, policy.delayMsFor(8))
    }

    @Test
    fun shouldRetryUntilMaxAttempts() {
        val policy = ReconnectPolicy(maxAttempts = 3, jitter = false)
        assertTrue(policy.shouldRetry(1))
        assertTrue(policy.shouldRetry(2))
        assertFalse(policy.shouldRetry(3))
        assertFalse(policy.shouldRetry(4))
    }

    @Test
    fun equalJitterStaysWithinHalfToFullCeiling() {
        // random() == 0.0 -> exactly half the ceiling (the floor).
        val low = ReconnectPolicy(baseDelayMs = 1_000L, jitter = true, random = { 0.0 })
        assertEquals(500L, low.delayMsFor(1))
        assertEquals(1_000L, low.delayMsFor(2))

        // random() == 1.0 -> the full ceiling (the cap).
        val high = ReconnectPolicy(baseDelayMs = 1_000L, jitter = true, random = { 1.0 })
        assertEquals(1_000L, high.delayMsFor(1))
        assertEquals(2_000L, high.delayMsFor(2))
    }

    @Test
    fun jitterNeverFallsBelowHalfCeilingAcrossManyDraws() {
        var seed = 0.0
        // Sweep a range of pseudo-random values and confirm bounds hold.
        val policy = ReconnectPolicy(
            baseDelayMs = 1_000L,
            maxDelayMs = 60_000L,
            jitter = true,
            random = { seed.also { seed = (seed + 0.137) % 1.0 } },
        )
        for (attempt in 1..10) {
            val ceiling = minOf(60_000L, 1_000L shl minOf(attempt - 1, 32))
            repeat(50) {
                val delay = policy.delayMsFor(attempt)
                assertTrue(
                    "attempt=$attempt delay=$delay below half-ceiling ${ceiling / 2}",
                    delay >= ceiling / 2,
                )
                assertTrue(
                    "attempt=$attempt delay=$delay above ceiling $ceiling",
                    delay <= ceiling,
                )
            }
        }
    }

    @Test
    fun zeroBaseDelayProducesNoWait() {
        val policy = ReconnectPolicy(baseDelayMs = 0L, maxDelayMs = 0L, jitter = true)
        assertEquals(0L, policy.delayMsFor(1))
        assertEquals(0L, policy.delayMsFor(5))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAttemptBelowOne() {
        ReconnectPolicy(jitter = false).delayMsFor(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMaxBelowBase() {
        ReconnectPolicy(baseDelayMs = 10_000L, maxDelayMs = 5_000L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsZeroMaxAttempts() {
        ReconnectPolicy(maxAttempts = 0)
    }
}
