package com.ghosttyxr.app.terminal.hands

import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Vector3

/**
 * Pure pose math for the palm-following hand menu, kept ARCore-/Android-free so it unit-tests on the
 * host JVM (mirrors [HandGestures]). [HandMenuController] feeds it the palm pose (already transformed
 * into activity space) and the user's head position, and applies the returned [Pose] to the menu's
 * `PanelEntity` each frame.
 *
 * Conventions follow the ARCore hand docs: the palm's outward-facing normal is the pose's **down**
 * axis ("the down direction points in the same direction as the palm").
 */
object HandMenuPlacement {

    /**
     * Float the menu this far along world-up above the palm so it sits over, not inside, the hand.
     * High enough that the wrist doesn't intersect the bottom edge of the panel.
     */
    const val FLOAT_ABOVE_PALM_M = 0.18f

    /**
     * Compute the menu's pose in activity space: float above the palm and turn to face the user's
     * head (billboard).
     *
     * @param palmPose the PALM joint pose, already transformed into activity space.
     * @param headPosition the user's head position in activity space, or null if unavailable
     *   (head tracking off/denied).
     */
    fun place(palmPose: Pose, headPosition: Vector3?): Pose {
        val position = palmPose.translation + Vector3.Up * FLOAT_ABOVE_PALM_M
        // A PanelEntity's content faces along its +Z (**backward**) axis, so "facing the user" means
        // the pose's backward axis points at the head — equivalently, its forward axis points
        // directly away from the head.
        //
        // xr-runtime 1.0.0-beta01 fixed Pose.fromLookAt's forward direction: it now aims **forward**
        // at `target` (through alpha15 it aimed *backward* there, so passing the head worked by
        // accident). Passing the head unchanged under beta01 spins the panel 180° and shows the user
        // the back of the pet. So mirror the head through the panel's own position and look at that:
        // forward lands away from the user, backward (the content normal) lands on them.
        //
        // When the head is unknown (head tracking off/denied) we can't billboard, so fall back to the
        // palm's own rotation — well-defined and never singular, unlike a look-at whose direction
        // could be parallel to world-up (a flat palm), which yields a NaN pose.
        val rotation = if (headPosition != null) {
            val awayFromHead = position + (position - headPosition)
            Pose.fromLookAt(eye = position, target = awayFromHead, up = Vector3.Up).rotation
        } else {
            palmPose.rotation
        }
        return Pose(position, rotation)
    }
}
