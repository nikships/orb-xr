package com.ghosttyxr.app.wiring

import androidx.xr.arcore.ArDevice
import androidx.xr.arcore.RenderViewpoint
import androidx.xr.runtime.Session
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.scene
import com.ghosttyxr.app.util.GxrLog

private const val TAG = "OrbEyes"

/** Where the user's two eyes are, in activity space. */
data class EyePair(val left: Vector3, val right: Vector3)

/**
 * The single ARCore-touching source of per-eye positions for the stereo voice pet.
 *
 * `RenderViewpoint.left/right` is the real thing: the runtime's own render viewpoints, which is
 * exactly what the compositor will use to present the two halves of the pet's side-by-side surface.
 * Feeding those into [com.ghosttyxr.app.terminal.hands.OrbEyeProjection] is what makes the orb hold
 * still in space when you move your head, instead of sliding with you like a decal.
 *
 * Degrades in two steps, because none of this is guaranteed:
 *  1. no stereo viewpoints (mono device, glasses, tracking disabled) -> synthesise a pair from the
 *     device pose and a nominal IPD, which still gives correct parallax and near-correct disparity;
 *  2. no device pose either (head tracking denied) -> `null`, and the renderer draws both halves
 *     identically, which reads as the old monoscopic pet rather than as breakage.
 *
 * Viewpoint lookups are cached: `RenderViewpoint.left(session)` walks the session's state extenders
 * and throws when unsupported, which is not something to do 72 times a second.
 */
class OrbEyeTracker(private val session: Session) {

    private var viewpoints: Pair<RenderViewpoint, RenderViewpoint>? = null
    private var stereoUnavailable = false
    private var arDevice: ArDevice? = null
    private var deviceUnavailable = false

    fun eyes(): EyePair? = stereoEyes() ?: syntheticEyes()

    private fun stereoEyes(): EyePair? {
        if (stereoUnavailable) return null
        val pair = viewpoints ?: runCatching {
            RenderViewpoint.left(session) to RenderViewpoint.right(session)
        }.onFailure {
            GxrLog.i(TAG, "stereo render viewpoints unavailable; orb falls back to a nominal IPD")
            stereoUnavailable = true
        }.getOrNull() ?: return null
        viewpoints = pair
        return runCatching {
            EyePair(
                left = inActivitySpace(pair.first.state.value.pose),
                right = inActivitySpace(pair.second.state.value.pose),
            )
        }.getOrNull()
    }

    private fun syntheticEyes(): EyePair? {
        if (deviceUnavailable) return null
        val device = arDevice ?: runCatching { ArDevice.getInstance(session) }
            .onFailure { deviceUnavailable = true }
            .getOrNull() ?: return null
        arDevice = device
        return runCatching {
            val head = inActivitySpacePose(device.state.value.devicePose)
            val offset = head.right * (NOMINAL_IPD_M / 2f)
            EyePair(left = head.translation - offset, right = head.translation + offset)
        }.getOrNull()
    }

    private fun inActivitySpace(perceptionPose: Pose): Vector3 =
        inActivitySpacePose(perceptionPose).translation

    private fun inActivitySpacePose(perceptionPose: Pose): Pose =
        session.scene.perceptionSpace.transformPoseTo(perceptionPose, session.scene.activitySpace)

    private companion object {
        /** Adult mean interpupillary distance; only used when the runtime won't tell us the truth. */
        const val NOMINAL_IPD_M = 0.063f
    }
}
