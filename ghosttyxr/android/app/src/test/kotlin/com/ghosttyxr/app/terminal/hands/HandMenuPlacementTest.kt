package com.ghosttyxr.app.terminal.hands

import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandMenuPlacementTest {

    private val palmAtOrigin = Pose(Vector3.Zero, Quaternion.Identity)

    @Test
    fun `float billboard floats above the palm along world-up`() {
        val pose = HandMenuPlacement.place(
            palmPose = palmAtOrigin,
            headPosition = Vector3(0f, 0f, -1f),
        )
        assertEquals(0f, pose.translation.x, EPS)
        assertEquals(HandMenuPlacement.FLOAT_ABOVE_PALM_M, pose.translation.y, EPS)
        assertEquals(0f, pose.translation.z, EPS)
    }

    @Test
    fun `float billboard faces toward the head position`() {
        // Head straight in front (−Z) and level with the floated panel. A PanelEntity's content
        // faces along its +Z (backward) axis, so "facing the head" means the panel's backward axis
        // points at the head (roughly along −Z), and forward points away (+Z).
        val floatY = HandMenuPlacement.FLOAT_ABOVE_PALM_M
        val pose = HandMenuPlacement.place(
            palmPose = palmAtOrigin,
            headPosition = Vector3(0f, floatY, -2f),
        )
        val backward = pose.backward
        assertEquals(0f, backward.x, 1e-3f)
        assertEquals(0f, backward.y, 1e-3f)
        assertEquals(-1f, backward.z, 1e-3f)
    }

    /**
     * Regression guard for the xr-runtime 1.0.0-beta01 `Pose.fromLookAt` fix, which reversed which
     * axis is aimed at `target` (forward now; backward through alpha15). The original call passed
     * the head position straight in and depended on the pre-fix behavior, so the upgrade silently
     * rotated the pet 180° — the user saw the back of the orb, and nothing failed to compile.
     *
     * Sweeping several head directions is the point: a 180° yaw error is invisible when the head
     * lies on the panel's own axis, which is exactly what the "head straight in front" case above
     * exercises.
     */
    @Test
    fun `float billboard faces the head from every direction`() {
        val floatY = HandMenuPlacement.FLOAT_ABOVE_PALM_M
        val heads = listOf(
            Vector3(0f, floatY, -2f), // ahead
            Vector3(0f, floatY, 2f), // behind
            Vector3(-2f, floatY, 0f), // left
            Vector3(2f, floatY, 0f), // right
            Vector3(1.5f, 1.2f, -1f), // above-right, in front
            Vector3(-0.4f, -0.9f, -1.3f), // below-left, in front
        )
        heads.forEach { head ->
            val pose = HandMenuPlacement.place(palmPose = palmAtOrigin, headPosition = head)
            // Panel content faces along +Z (backward), so backward must point AT the head.
            val toHead = (head - pose.translation).toNormalized()
            assertEquals("panel should face head at $head", 1f, pose.backward.dot(toHead), 1e-3f)
        }
    }

    @Test
    fun `float billboard stays finite when the head is directly overhead`() {
        // A look-at direction parallel to the world-up reference is the singular case; it must not
        // leak NaN into the panel's pose.
        val pose = HandMenuPlacement.place(
            palmPose = palmAtOrigin,
            headPosition = Vector3(0f, 5f, 0f),
        )
        listOf(pose.translation.x, pose.translation.y, pose.translation.z).forEach {
            assertTrue("translation must be finite, was $it", it.isFinite())
        }
        val rotation = pose.rotation
        listOf(rotation.x, rotation.y, rotation.z, rotation.w).forEach {
            assertTrue("rotation must be finite, was $it", it.isFinite())
        }
    }

    @Test
    fun `float billboard falls back to the palm rotation when head is unknown`() {
        // No head pose: can't billboard, so adopt the palm's own (well-defined, non-singular)
        // rotation. A flat palm-up hand parallel to world-up must not produce a NaN pose.
        val pose = HandMenuPlacement.place(
            palmPose = palmAtOrigin,
            headPosition = null,
        )
        assertEquals(palmAtOrigin.rotation, pose.rotation)
        assertEquals(1f, pose.forward.length, 1e-3f)
    }

    private companion object {
        const val EPS = 1e-4f
    }
}
