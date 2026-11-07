package com.ghosttyxr.app.terminal.hands

import androidx.xr.arcore.HandSide
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [HandMenuController.onPalmFrame] — the extracted, Session-free heart of the controller —
 * with synthetic palm poses and a fake [VoicePetSurface], covering the reveal/follow/flip-dismiss
 * wiring and the surface contract on the host JVM (no emulator).
 */
class HandMenuControllerTest {

    /**
     * Records every call so the test can assert show/hide/pose ordering. Honors the surface
     * contract: a pending onRevealed is dropped by hide(), and fires only via [completeReveal]
     * (simulating the reveal animation finishing).
     */
    private class FakeSurface : VoicePetSurface {
        var shown = false
        var showCount = 0
        var hideCount = 0
        var lastPose: Pose? = null
        var poseCount = 0
        private var pendingRevealed: (() -> Unit)? = null

        override fun show(onRevealed: () -> Unit) {
            shown = true
            showCount++
            pendingRevealed = onRevealed
        }

        override fun hide() {
            shown = false
            hideCount++
            pendingRevealed = null
        }

        override fun setPose(pose: Pose) {
            lastPose = pose
            poseCount++
        }

        override fun dispose() = Unit

        /** Simulate the reveal animation finishing: fire the callback if still pending. */
        fun completeReveal() {
            pendingRevealed?.invoke()
            pendingRevealed = null
        }
    }

    // Palm rotated 180° about X so its `down` axis points world-up => "palm up" summon pose.
    private val palmUpPose = Pose(Vector3.Zero, Quaternion.fromAxisAngle(Vector3(1f, 0f, 0f), 180f))

    // Identity palm: `down` axis is world-down => "palm down" dismiss pose.
    private val palmDownPose = Pose(Vector3.Zero, Quaternion.Identity)

    private fun controller(
        clock: () -> Long,
        onPalmFlipDismiss: () -> Unit = {},
        onRevealComplete: () -> Unit = {},
        onLayoutLockToggle: () -> Unit = {},
    ) = HandMenuController(
        primaryHandSide = { HandSide.RIGHT },
        machine = HandMenuGestureMachine(revealHoldMs = 300L),
        prayerMachine = PrayerToggleMachine(holdMs = 300L),
        nowMs = clock,
        onPalmFlipDismiss = onPalmFlipDismiss,
        onRevealComplete = onRevealComplete,
        onLayoutLockToggle = onLayoutLockToggle,
    )

    @Test
    fun `reveal completion opens the mic exactly once`() {
        var t = 0L
        var revealCompleteCount = 0
        val controller = controller(clock = { t }, onRevealComplete = { revealCompleteCount++ })
        val surface = FakeSurface()

        controller.onPalmFrame(palmUpPose, head(), surface)
        t = 300L
        controller.onPalmFrame(palmUpPose, head(), surface)
        assertEquals(0, revealCompleteCount) // animation still running

        surface.completeReveal()
        assertEquals(1, revealCompleteCount)
        surface.completeReveal() // stale double-fire is dropped by the fake's contract
        assertEquals(1, revealCompleteCount)
    }

    @Test
    fun `palm flip during the reveal animation cancels the pending mic open`() {
        var t = 0L
        var revealCompleteCount = 0
        val controller = controller(clock = { t }, onRevealComplete = { revealCompleteCount++ })
        val surface = FakeSurface()

        controller.onPalmFrame(palmUpPose, head(), surface)
        t = 300L
        controller.onPalmFrame(palmUpPose, head(), surface)

        // Flip down before the reveal animation finishes: hide() drops the pending callback.
        t = 350L
        controller.onPalmFrame(palmDownPose, head(), surface)
        surface.completeReveal()

        assertFalse(controller.isMenuVisible.value)
        assertEquals(0, revealCompleteCount)
    }

    @Test
    fun `holding palm-up reveals and shows the surface with a pose`() {
        var t = 0L
        val controller = controller(clock = { t })
        val surface = FakeSurface()

        controller.onPalmFrame(palmUpPose, head(), surface)
        assertFalse(surface.shown)

        t = 300L
        controller.onPalmFrame(palmUpPose, head(), surface)

        assertTrue(surface.shown)
        assertEquals(1, surface.showCount)
        assertTrue(controller.isMenuVisible.value)
        assertEquals(
            palmUpPose.translation.y + HandMenuPlacement.FLOAT_ABOVE_PALM_M,
            surface.lastPose!!.translation.y,
            EPS,
        )
    }

    @Test
    fun `while visible the menu follows the hand each frame without re-showing`() {
        var t = 0L
        val controller = controller(clock = { t })
        val surface = FakeSurface()
        controller.onPalmFrame(palmUpPose, head(), surface)
        t = 300L
        controller.onPalmFrame(palmUpPose, head(), surface)
        val posesAfterReveal = surface.poseCount

        // Hand drifts but stays palm-up: pose updates, no extra show().
        t = 320L
        val moved = Pose(Vector3(0.1f, 0f, 0f), palmUpPose.rotation)
        controller.onPalmFrame(moved, head(), surface)

        assertEquals(1, surface.showCount)
        assertEquals(posesAfterReveal + 1, surface.poseCount)
        assertEquals(0.1f, surface.lastPose!!.translation.x, EPS)
    }

    @Test
    fun `flipping the palm down hides the surface`() {
        var t = 0L
        var flipDismissCount = 0
        val controller = controller(
            clock = { t },
            onPalmFlipDismiss = { flipDismissCount++ },
        )
        val surface = FakeSurface()
        controller.onPalmFrame(palmUpPose, head(), surface)
        t = 300L
        controller.onPalmFrame(palmUpPose, head(), surface)
        assertTrue(surface.shown)

        t = 400L
        controller.onPalmFrame(palmDownPose, head(), surface)

        assertFalse(surface.shown)
        assertEquals(1, surface.hideCount)
        assertFalse(controller.isMenuVisible.value)
        assertEquals(1, flipDismissCount)
    }

    @Test
    fun `explicit dismissal does not invoke the palm flip callback`() {
        var t = 0L
        var flipDismissCount = 0
        val controller = controller(
            clock = { t },
            onPalmFlipDismiss = { flipDismissCount++ },
        )
        val surface = FakeSurface()
        controller.onPalmFrame(palmUpPose, head(), surface)
        t = 300L
        controller.onPalmFrame(palmUpPose, head(), surface)

        controller.dismiss()

        assertEquals(0, flipDismissCount)
    }

    @Test
    fun `availability starts false and stop resets it`() {
        val controller = controller(clock = { 0L })
        assertFalse(controller.isAvailable.value)

        // Simulate a successful start() having configured hand tracking.
        controller._isAvailable.value = true
        assertTrue(controller.isAvailable.value)

        controller.stop()
        assertFalse(controller.isAvailable.value)
    }


    @Test
    fun `prayer hold toggles layout lock once then re-arms after release`() {
        var t = 0L
        var toggles = 0
        val controller = controller(clock = { t }, onLayoutLockToggle = { toggles++ })
        val surface = FakeSurface()
        val left = prayerLeft()
        val right = prayerRight()

        controller.onTrackingFrame(left, right, head(), surface)
        t = 300L
        controller.onTrackingFrame(left, right, head(), surface)
        assertEquals(1, toggles)
        // Still holding: no second toggle.
        t = 600L
        controller.onTrackingFrame(left, right, head(), surface)
        assertEquals(1, toggles)
        // Release and hold again.
        t = 650L
        controller.onTrackingFrame(null, null, head(), surface)
        t = 700L
        controller.onTrackingFrame(left, right, head(), surface)
        t = 1000L
        controller.onTrackingFrame(left, right, head(), surface)
        assertEquals(2, toggles)
    }

    @Test
    fun `suppressPalmUp prevents pet summon while prayer is active`() {
        var t = 0L
        val controller = controller(clock = { t })
        val surface = FakeSurface()

        controller.onPalmFrame(palmUpPose, head(), surface, suppressPalmUp = true)
        t = 300L
        controller.onPalmFrame(palmUpPose, head(), surface, suppressPalmUp = true)

        assertFalse(controller.isMenuVisible.value)
        assertEquals(0, surface.showCount)
    }

    private fun prayerLeft() = Pose(
        Vector3(-0.03f, 0f, 0f),
        Quaternion.fromAxisAngle(Vector3(0f, 0f, 1f), -90f),
    )

    private fun prayerRight() = Pose(
        Vector3(0.03f, 0f, 0f),
        Quaternion.fromAxisAngle(Vector3(0f, 0f, 1f), 90f),
    )

    private fun head() = Vector3(0f, HandMenuPlacement.FLOAT_ABOVE_PALM_M, -2f)

    private companion object {
        const val EPS = 1e-4f
    }
}
