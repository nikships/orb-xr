package com.ghosttyxr.app.terminal.hands

import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Vector3
import kotlin.math.max

/**
 * Off-axis ("generalised") perspective projection for the stereo voice pet.
 *
 * The pet is drawn into a `SurfaceEntity` quad in `SIDE_BY_SIDE` stereo. Rendering both halves from
 * one fixed camera is what made the old pet read as a *picture* of a sphere: with no binocular
 * disparity and no head parallax the brain correctly classifies it as a flat card. Here each eye
 * instead gets its own asymmetric frustum whose image plane **is** the quad rectangle, so the quad
 * behaves like a window and the orb behind it resolves as a solid object sitting in the palm.
 *
 * Conventions match the quad's local space: the rectangle spans `[-halfWidth, halfWidth]` by
 * `[-halfHeight, halfHeight]` at `z = 0`, the viewer sits at positive Z, and the camera looks down
 * -Z (standard GL). No aspect correction is applied anywhere — the frustum is derived from real
 * metres, so squeezing each eye into a half-width viewport is exactly undone by the compositor when
 * it stretches that half back across the quad.
 *
 * Pure math (no Android, no GL) so the whole stereo derivation unit-tests on the host JVM.
 */
object OrbEyeProjection {

    /** Floats in a 4x4 column-major matrix, matching `android.opengl.Matrix`. */
    const val MATRIX_SIZE = 16

    /**
     * Eyes closer to the quad plane than this are pushed back to it. Hands can pass through the
     * head, and a viewer at or behind the window plane has no valid frustum at all.
     */
    const val MIN_EYE_DISTANCE_M = 0.05f

    private const val MIN_NEAR_M = 0.005f
    private const val MIN_DEPTH_RANGE_M = 0.01f
    private const val NEAR_PLANE_MARGIN = 0.999f
    private const val MIN_SCALE = 1e-4f
    private const val MIN_HALF_EXTENT_M = 1e-4f

    /**
     * An eye position in activity space, expressed in the quad's own **unscaled** local space.
     *
     * Dividing by [quadScale] matters: the entity is scaled during the reveal pop and the idle
     * breathing, while the quad's half-extents passed to [project] describe the unscaled shape. If
     * the two disagreed the stereo geometry would breathe along with the animation.
     */
    fun eyeInQuadLocal(quadPose: Pose, quadScale: Float, eyeInActivity: Vector3): Vector3 {
        val relative = eyeInActivity - quadPose.translation
        return (quadPose.rotation.inverse * relative) / max(quadScale, MIN_SCALE)
    }

    /**
     * Write the projection for an eye at [eye] (quad-local metres) into [out].
     *
     * The window edges always land exactly on the NDC edges, whatever the eye position — that
     * invariant is what makes the quad read as a hole in space rather than a textured card.
     */
    fun project(
        eye: Vector3,
        halfWidth: Float,
        halfHeight: Float,
        near: Float,
        far: Float,
        out: FloatArray,
    ) {
        require(out.size >= MATRIX_SIZE) { "out must hold at least $MATRIX_SIZE floats" }
        val halfW = max(halfWidth, MIN_HALF_EXTENT_M)
        val halfH = max(halfHeight, MIN_HALF_EXTENT_M)
        val distance = max(eye.z, MIN_EYE_DISTANCE_M)
        val nearPlane = near.coerceIn(MIN_NEAR_M, distance * NEAR_PLANE_MARGIN)
        val farPlane = max(far, nearPlane + MIN_DEPTH_RANGE_M)

        val scale = nearPlane / distance
        val left = (-halfW - eye.x) * scale
        val right = (halfW - eye.x) * scale
        val bottom = (-halfH - eye.y) * scale
        val top = (halfH - eye.y) * scale

        val width = right - left
        val height = top - bottom
        val depth = farPlane - nearPlane

        out.fill(0f, 0, MATRIX_SIZE)
        out[0] = 2f * nearPlane / width
        out[5] = 2f * nearPlane / height
        out[8] = (right + left) / width
        out[9] = (top + bottom) / height
        out[10] = -(farPlane + nearPlane) / depth
        out[11] = -1f
        out[14] = -2f * farPlane * nearPlane / depth

        // Fold the view translation (eye -> origin) into the last column. The screen is axis-aligned
        // in quad-local space, so the view rotation is identity and only the offset survives.
        out[12] = -out[0] * eye.x - out[8] * distance
        out[13] = -out[5] * eye.y - out[9] * distance
        out[14] += -out[10] * distance
        out[15] = distance
    }

    /**
     * Near plane hugging a sphere of [radius] sitting near the window plane. Tight planes keep
     * enough depth precision for the orbiting sparks to occlude cleanly against the orb.
     */
    fun nearPlaneFor(eyeDistance: Float, radius: Float): Float =
        max(max(eyeDistance, MIN_EYE_DISTANCE_M) - DEPTH_MARGIN_RADII * radius, MIN_NEAR_M)

    /** Companion of [nearPlaneFor]. [centerZ] is the sphere centre's quad-local Z (negative). */
    fun farPlaneFor(eyeDistance: Float, radius: Float, centerZ: Float = 0f): Float =
        max(eyeDistance, MIN_EYE_DISTANCE_M) - centerZ + DEPTH_MARGIN_RADII * radius

    private const val DEPTH_MARGIN_RADII = 3f
}
