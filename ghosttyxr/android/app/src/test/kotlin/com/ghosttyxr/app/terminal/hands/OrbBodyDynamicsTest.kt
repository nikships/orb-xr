package com.ghosttyxr.app.terminal.hands

import androidx.xr.runtime.math.Vector3
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbBodyDynamicsTest {

    @Test
    fun theFirstFrameMaterialisesTheOrbAtRestOnThePalm() {
        val body = OrbBodyDynamics().update(Vector3(0.3f, -0.2f, -0.5f), 1f / 90f)

        assertEquals(0f, body.offset.length, 1e-6f)
        assertEquals(0f, body.stretch, 1e-6f)
        assertEquals(0f, body.energy, 1e-6f)
    }

    @Test
    fun theOrbTrailsAMovingHandAndThenCatchesUp() {
        val dynamics = OrbBodyDynamics()
        val start = Vector3.Zero
        dynamics.update(start, 1f / 90f)

        var lagWhileMoving = 0f
        repeat(45) { step ->
            val body = dynamics.update(Vector3(0.3f * (step + 1) / 45f, 0f, 0f), 1f / 90f)
            lagWhileMoving = maxOf(lagWhileMoving, body.offset.length)
        }
        assertTrue("the orb should visibly trail the hand", lagWhileMoving > 5e-3f)

        val settled = Vector3(0.3f, 0f, 0f)
        var body = dynamics.update(settled, 1f / 90f)
        repeat(270) { body = dynamics.update(settled, 1f / 90f) }
        assertTrue("the orb must settle, not oscillate: ${body.offset.length}", body.offset.length < 1e-3f)
        assertEquals("and go still", 0f, body.stretch, 1e-3f)
    }

    @Test
    fun aTrackingJumpSnapsInsteadOfSlingingTheOrbAcrossTheRoom() {
        val dynamics = OrbBodyDynamics()
        dynamics.update(Vector3.Zero, 1f / 90f)
        repeat(10) { dynamics.update(Vector3.Zero, 1f / 90f) }

        // Hand re-acquired two metres away, the classic hand-tracking dropout.
        val body = dynamics.update(Vector3(2f, 0f, 0f), 1f / 90f)

        assertTrue("lag clamp breached: ${body.offset.length}", body.offset.length <= 0.0501f)
    }

    @Test
    fun steadyStateLagIsTheSameAtThirtySixtyAndOneHundredTwentyHertz() {
        val lags = listOf(30, 60, 120).map { hz ->
            val dynamics = OrbBodyDynamics()
            val delta = 1f / hz
            var position = 0f
            dynamics.update(Vector3.Zero, delta)
            var body = dynamics.update(Vector3.Zero, delta)
            repeat(hz * 3) {
                position += SWEEP_SPEED_MPS * delta
                body = dynamics.update(Vector3(position, 0f, 0f), delta)
            }
            body.offset.length
        }

        lags.forEach { assertTrue("expected a real steady-state lag, got $it", it > 1e-3f) }
        assertEquals("30 Hz vs 120 Hz", lags[0], lags[2], 2e-3f)
        assertEquals("60 Hz vs 120 Hz", lags[1], lags[2], 2e-3f)
    }

    @Test
    fun handSpeedRaisesEnergyAndThenItDecays() {
        val dynamics = OrbBodyDynamics()
        var position = 0f
        dynamics.update(Vector3.Zero, 1f / 90f)
        var body = dynamics.update(Vector3.Zero, 1f / 90f)
        repeat(45) {
            position += 1.2f / 90f
            body = dynamics.update(Vector3(position, 0f, 0f), 1f / 90f)
        }
        val excited = body.energy
        assertTrue("a fast flick should excite the orb, got $excited", excited > 0.4f)

        val resting = Vector3(position, 0f, 0f)
        repeat(180) { body = dynamics.update(resting, 1f / 90f) }
        assertTrue("energy should coast back down, got ${body.energy}", body.energy < excited * 0.3f)
    }

    @Test
    fun theBodyStretchesAlongTravelAndTheAxisIsAUnitVector() {
        val dynamics = OrbBodyDynamics()
        var position = 0f
        dynamics.update(Vector3.Zero, 1f / 90f)
        var body = dynamics.update(Vector3.Zero, 1f / 90f)
        repeat(30) {
            position += 0.9f / 90f
            body = dynamics.update(Vector3(0f, position, 0f), 1f / 90f)
        }

        assertTrue("expected squash-and-stretch, got ${body.stretch}", body.stretch > 0.01f)
        assertEquals(1f, body.stretchAxis.length, 1e-4f)
        assertTrue("stretch should follow travel", body.stretchAxis.y > 0.95f)
    }

    @Test
    fun stretchAndLeanAreBounded() {
        val dynamics = OrbBodyDynamics()
        var position = 0f
        dynamics.update(Vector3.Zero, 1f / 90f)
        var body = dynamics.update(Vector3.Zero, 1f / 90f)
        repeat(60) {
            position += 12f / 90f // absurd, but a tracking glitch can produce it
            body = dynamics.update(Vector3(position, 0f, 0f), 1f / 90f)
        }

        assertTrue("stretch unbounded: ${body.stretch}", body.stretch <= 0.2f + 1e-4f)
        assertTrue("energy unbounded: ${body.energy}", body.energy <= 1f + 1e-4f)
        assertTrue(abs(body.lean.w) <= 1f + 1e-4f)
    }

    @Test
    fun nonFiniteAndAbsurdTargetsAreIgnored() {
        val dynamics = OrbBodyDynamics()
        dynamics.update(Vector3(0.1f, 0.1f, 0.1f), 1f / 90f)
        dynamics.update(Vector3(0.1f, 0.1f, 0.1f), 1f / 90f)

        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, 1e9f)) {
            val body = dynamics.update(Vector3(bad, bad, bad), 1f / 90f)
            assertTrue("poisoned by $bad", body.offset.length.isFinite())
            assertTrue(body.energy.isFinite() && body.stretch.isFinite())
        }
    }

    @Test
    fun aStalledFrameDoesNotBlowUpTheSpring() {
        val dynamics = OrbBodyDynamics()
        dynamics.update(Vector3.Zero, 1f / 90f)
        dynamics.update(Vector3.Zero, 1f / 90f)

        for (delta in listOf(2f, Float.NaN, -1f, 0f)) {
            val body = dynamics.update(Vector3(0.05f, 0f, 0f), delta)
            assertTrue("delta=$delta produced ${body.offset}", body.offset.length.isFinite())
            assertTrue(body.offset.length <= 0.0501f)
        }
    }

    @Test
    fun resetForgetsEverything() {
        val dynamics = OrbBodyDynamics()
        var position = 0f
        repeat(60) {
            position += 0.8f / 90f
            dynamics.update(Vector3(position, 0f, 0f), 1f / 90f)
        }

        dynamics.reset()
        val body = dynamics.update(Vector3(5f, 5f, 5f), 1f / 90f)

        assertEquals(0f, body.offset.length, 1e-6f)
        assertEquals(0f, body.energy, 1e-6f)
    }

    private companion object {
        /** Slow enough that the steady-state spring lag stays inside the hard clamp. */
        const val SWEEP_SPEED_MPS = 0.2f
    }
}
