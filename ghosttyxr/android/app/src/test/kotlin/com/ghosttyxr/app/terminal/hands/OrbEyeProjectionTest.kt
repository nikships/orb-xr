package com.ghosttyxr.app.terminal.hands

import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stereo derivation for the voice pet. If any of these break the orb stops being an object and
 * goes back to being a picture, so they are pinned rather than left to on-device eyeballing.
 */
class OrbEyeProjectionTest {

    private val halfExtent = 0.095f
    private val matrix = FloatArray(OrbEyeProjection.MATRIX_SIZE)

    @Test
    fun quadCornersMapToNdcCornersForAnOffAxisEye() {
        val eye = Vector3(0.041f, -0.027f, 0.38f)
        OrbEyeProjection.project(eye, halfExtent, halfExtent, 0.3f, 0.6f, matrix)

        for (signX in listOf(-1f, 1f)) {
            for (signY in listOf(-1f, 1f)) {
                val ndc = transform(matrix, Vector3(signX * halfExtent, signY * halfExtent, 0f))
                assertEquals("corner x", signX, ndc[0], 1e-4f)
                assertEquals("corner y", signY, ndc[1], 1e-4f)
            }
        }
    }

    @Test
    fun windowCentreStaysAtScreenCentreWhereverTheEyeIs() {
        // The defining property of an off-axis window: content *on* the quad plane has zero
        // disparity, and only content behind it separates between the eyes.
        for (eye in listOf(Vector3(0f, 0f, 0.4f), Vector3(-0.09f, 0.06f, 0.22f))) {
            OrbEyeProjection.project(eye, halfExtent, halfExtent, 0.2f, 0.7f, matrix)
            val ndc = transform(matrix, Vector3.Zero)
            assertEquals(0f, ndc[0], 1e-5f)
            assertEquals(0f, ndc[1], 1e-5f)
        }
    }

    @Test
    fun onAxisEyeGivesTheTextbookSymmetricFrustum() {
        val distance = 0.45f
        OrbEyeProjection.project(Vector3(0f, 0f, distance), halfExtent, halfExtent, 0.3f, 0.6f, matrix)

        assertEquals(distance / halfExtent, matrix[0], 1e-5f)
        assertEquals(distance / halfExtent, matrix[5], 1e-5f)
        assertEquals("no shear on axis", 0f, matrix[8], 1e-6f)
        assertEquals("no shear on axis", 0f, matrix[9], 1e-6f)
    }

    @Test
    fun theTwoEyesShearInOppositeDirections() {
        val halfIpd = 0.0315f
        OrbEyeProjection.project(Vector3(-halfIpd, 0f, 0.4f), halfExtent, halfExtent, 0.3f, 0.6f, matrix)
        val leftShear = matrix[8]
        OrbEyeProjection.project(Vector3(halfIpd, 0f, 0.4f), halfExtent, halfExtent, 0.3f, 0.6f, matrix)
        val rightShear = matrix[8]

        assertTrue("shear must be non-trivial", abs(leftShear) > 0.1f)
        assertEquals(-leftShear, rightShear, 1e-6f)
    }

    @Test
    fun somethingBehindTheWindowSeparatesBetweenTheEyes() {
        val halfIpd = 0.0315f
        val behind = Vector3(0f, 0f, -0.04f)
        OrbEyeProjection.project(Vector3(-halfIpd, 0f, 0.4f), halfExtent, halfExtent, 0.3f, 0.6f, matrix)
        val left = transform(matrix, behind)
        OrbEyeProjection.project(Vector3(halfIpd, 0f, 0.4f), halfExtent, halfExtent, 0.3f, 0.6f, matrix)
        val right = transform(matrix, behind)

        // Nearer eye sees it displaced toward the far eye's side: that disparity *is* the depth.
        assertTrue("expected horizontal disparity", left[0] < right[0] - 1e-3f)
        assertEquals("no vertical disparity", left[1], right[1], 1e-5f)
    }

    @Test
    fun depthRangeMapsInsideTheClipVolume() {
        val eye = Vector3(0f, 0f, 0.45f)
        val near = OrbEyeProjection.nearPlaneFor(eye.z, 0.045f)
        val far = OrbEyeProjection.farPlaneFor(eye.z, 0.045f, -0.01f)
        OrbEyeProjection.project(eye, halfExtent, halfExtent, near, far, matrix)

        for (z in listOf(0.05f, 0f, -0.045f, -0.07f)) {
            val ndc = transform(matrix, Vector3(0f, 0f, z))
            assertTrue("z=$z mapped to ${ndc[2]}", ndc[2] > -1f && ndc[2] < 1f)
        }
    }

    @Test
    fun anEyeAtOrBehindTheWindowPlaneIsClampedAndStaysFinite() {
        for (z in listOf(0f, -0.5f, Float.NEGATIVE_INFINITY)) {
            OrbEyeProjection.project(Vector3(0f, 0f, z), halfExtent, halfExtent, 0.3f, 0.6f, matrix)
            assertTrue("matrix went non-finite for z=$z", matrix.all { it.isFinite() })
            assertEquals(OrbEyeProjection.MIN_EYE_DISTANCE_M, matrix[15], 1e-6f)
        }
    }

    @Test
    fun degenerateNearAndFarPlanesAreRepaired() {
        OrbEyeProjection.project(Vector3(0f, 0f, 0.4f), halfExtent, halfExtent, 0f, -3f, matrix)
        assertTrue(matrix.all { it.isFinite() })
        val ndc = transform(matrix, Vector3.Zero)
        assertTrue(ndc.all { it.isFinite() })
    }

    @Test
    fun eyeInQuadLocalUndoesThePosePlusScale() {
        val pose = Pose(
            Vector3(0.2f, -1.1f, -0.6f),
            Quaternion.fromAxisAngle(Vector3.Up, 41f) *
                Quaternion.fromAxisAngle(Vector3.Right, -17f),
        )
        val local = Vector3(0.03f, -0.02f, 0.4f)
        val scale = 0.85f
        val activity = pose.translation + pose.rotation * (local * scale)

        val recovered = OrbEyeProjection.eyeInQuadLocal(pose, scale, activity)

        assertEquals(local.x, recovered.x, 1e-4f)
        assertEquals(local.y, recovered.y, 1e-4f)
        assertEquals(local.z, recovered.z, 1e-4f)
    }

    @Test
    fun eyeInQuadLocalSurvivesTheRevealPopsCollapsedScale() {
        val pose = Pose(Vector3(0f, 0f, 0f), Quaternion.Identity)
        val recovered = OrbEyeProjection.eyeInQuadLocal(pose, 0f, Vector3(0f, 0f, 0.4f))
        assertTrue(recovered.z.isFinite() && recovered.z > 0f)
    }

    /** Column-major 4x4 times a point, then the perspective divide. */
    private fun transform(matrix: FloatArray, point: Vector3): FloatArray {
        val out = FloatArray(4)
        val input = floatArrayOf(point.x, point.y, point.z, 1f)
        for (row in 0..3) {
            var sum = 0f
            for (column in 0..3) {
                sum += matrix[column * 4 + row] * input[column]
            }
            out[row] = sum
        }
        val w = out[3]
        return floatArrayOf(out[0] / w, out[1] / w, out[2] / w, w)
    }
}
