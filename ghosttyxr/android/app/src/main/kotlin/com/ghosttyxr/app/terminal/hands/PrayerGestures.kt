package com.ghosttyxr.app.terminal.hands

import androidx.xr.runtime.math.Pose
import kotlin.math.abs

/**
 * Pure prayer-pose math for the layout-lock toggle. Host-JVM testable; no Android/ARCore deps.
 *
 * A prayer formation is both palms close together, roughly facing each other, and near the same
 * height. Palm outward normal is the pose [Pose.down] axis (ARCore hand convention).
 */
object PrayerGestures {
    /** Max palm-to-palm distance (meters) that still counts as hands-together. ~10 cm. */
    const val MAX_DISTANCE_M = 0.10f

    /**
     * Max allowed dot of the two palm outward normals. −1 = perfectly facing; −0.45 ≈ within
     * ~117° so a natural prayer (slightly open hands) still counts without matching flat palms
     * held side-by-side (dot ≈ +1) or both palm-up (dot ≈ +1).
     */
    const val FACING_DOT_MAX = -0.45f

    /** Max |Δy| between palms so one hand on a keyboard and one raised does not false-trigger. */
    const val MAX_HEIGHT_DELTA_M = 0.12f

    /**
     * Scalar form used by unit tests and by [isPrayerPose].
     *
     * @param palmDistanceM distance between palm positions in meters.
     * @param facingDot [left.outward · right.outward]; near −1 when palms face each other.
     * @param heightDeltaM absolute vertical separation of the palms.
     */
    fun isPrayer(
        palmDistanceM: Float,
        facingDot: Float,
        heightDeltaM: Float,
    ): Boolean {
        return palmDistanceM <= MAX_DISTANCE_M &&
            facingDot <= FACING_DOT_MAX &&
            heightDeltaM <= MAX_HEIGHT_DELTA_M
    }

    /**
     * True when both palm poses form a prayer-like hold.
     *
     * @param leftPalm left PALM joint in activity space.
     * @param rightPalm right PALM joint in activity space.
     */
    fun isPrayerPose(leftPalm: Pose, rightPalm: Pose): Boolean {
        val delta = leftPalm.translation - rightPalm.translation
        return isPrayer(
            palmDistanceM = delta.length,
            facingDot = leftPalm.down.dot(rightPalm.down),
            heightDeltaM = abs(leftPalm.translation.y - rightPalm.translation.y),
        )
    }
}
