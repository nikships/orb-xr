package com.ghosttyxr.app.wiring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the rounded, feathered orb-quad spec from the task: the pet's window must
 * keep a positive corner radius inside scenecore's bound and symmetric edge
 * feathering inside the 0..0.5 canvas-fraction range. The GL behind the quad
 * (AgentOrbRenderer) is unchanged — single-pass opaque black body, side-by-side
 * stereo — so these are the only orb-surface values with something to assert.
 */
class VoicePetSurfaceShapeTest {

    @Test
    fun cornerRadiusIsPositiveAndFarInsideScenecoreBound() {
        assertTrue(PET_CORNER_RADIUS_M > 0f)
        // Quad side for a 220dp legacy panel at any sane density stays well above 2x radius.
        val smallestPlausibleQuadSideM = 0.1f
        assertTrue(PET_CORNER_RADIUS_M <= smallestPlausibleQuadSideM / 2f)
    }

    @Test
    fun quadShapeCarriesThePinnedRadiusWithinBounds() {
        val quadSideM = 0.19f
        val shape = petQuadShape(quadSideM)
        assertEquals(quadSideM, shape.extents.width, 0f)
        assertEquals(quadSideM, shape.extents.height, 0f)
        assertEquals(PET_CORNER_RADIUS_M, shape.cornerRadius, 0f)
    }

    @Test
    fun quadShapeClampsRadiusForTinyQuadsInsteadOfThrowing() {
        val tinySideM = 0.01f
        val shape = petQuadShape(tinySideM)
        assertEquals(tinySideM / 2f, shape.cornerRadius, 0f)
    }

    @Test
    fun edgeFeatheringIsSymmetricAndInsideCanvasFractionRange() {
        val feather = petEdgeFeathering()
        assertEquals(PET_FEATHER_LEFT_RIGHT, feather.leftRight, 0f)
        assertEquals(PET_FEATHER_TOP_BOTTOM, feather.topBottom, 0f)
        assertTrue(feather.leftRight in 0.0f..0.5f)
        assertTrue(feather.topBottom in 0.0f..0.5f)
    }
}
