package com.ghosttyxr.app.terminal.hands

import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrayerGesturesTest {

    @Test
    fun `close facing palms count as prayer`() {
        assertTrue(
            PrayerGestures.isPrayer(
                palmDistanceM = 0.05f,
                facingDot = -0.9f,
                heightDeltaM = 0.02f,
            ),
        )
    }

    @Test
    fun `far hands do not count`() {
        assertFalse(
            PrayerGestures.isPrayer(
                palmDistanceM = 0.25f,
                facingDot = -1f,
                heightDeltaM = 0f,
            ),
        )
    }

    @Test
    fun `palms not facing each other do not count`() {
        assertFalse(
            PrayerGestures.isPrayer(
                palmDistanceM = 0.05f,
                facingDot = 0.2f,
                heightDeltaM = 0f,
            ),
        )
    }

    @Test
    fun `large height delta does not count`() {
        assertFalse(
            PrayerGestures.isPrayer(
                palmDistanceM = 0.05f,
                facingDot = -1f,
                heightDeltaM = 0.3f,
            ),
        )
    }

    @Test
    fun `pose form matches scalar thresholds`() {
        // Identity palms: both down ≈ (0,-1,0) so facingDot ≈ +1 (not facing each other).
        val left = Pose(Vector3(-0.03f, 0f, 0f), Quaternion.Identity)
        val right = Pose(Vector3(0.03f, 0f, 0f), Quaternion.Identity)
        assertFalse(PrayerGestures.isPrayerPose(left, right))

        // Rotate each palm 90° about Z so outward normals face each other (±X).
        val leftFacing = Pose(
            Vector3(-0.03f, 0f, 0f),
            Quaternion.fromAxisAngle(Vector3(0f, 0f, 1f), -90f),
        )
        val rightFacing = Pose(
            Vector3(0.03f, 0f, 0f),
            Quaternion.fromAxisAngle(Vector3(0f, 0f, 1f), 90f),
        )
        assertTrue(PrayerGestures.isPrayerPose(leftFacing, rightFacing))
    }
}
