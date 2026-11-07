package com.ghosttyxr.app.ui

import androidx.xr.compose.platform.SpaceRequestResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The restore latch is the part worth pinning down: getting it wrong either drops the user's saved
 * immersive mode silently (consume on failure) or re-requests Full Space forever on a device that
 * can never grant it (never consume).
 */
class SpaceTransitionsTest {

    @Test
    fun `success is the only outcome treated as applied`() {
        assertTrue(SpaceTransitions.succeeded(SpaceRequestResult.Success))
        assertFalse(SpaceTransitions.succeeded(SpaceRequestResult.Unsupported))
        assertFalse(SpaceTransitions.succeeded(SpaceRequestResult.Error(IllegalStateException("no"))))
    }

    @Test
    fun `failure is the complement of success`() {
        assertFalse(SpaceTransitions.isFailure(SpaceRequestResult.Success))
        assertTrue(SpaceTransitions.isFailure(SpaceRequestResult.Unsupported))
        assertTrue(SpaceTransitions.isFailure(SpaceRequestResult.Error(IllegalStateException("no"))))
    }

    @Test
    fun `restore is consumed once it has applied`() {
        assertTrue(SpaceTransitions.shouldConsumeRestore(SpaceRequestResult.Success))
    }

    @Test
    fun `restore is consumed on unsupported so it never retries forever`() {
        // Optical see-through glasses / phones can't enter Full Space at all. Retrying would
        // re-request on every recomposition for the life of the process.
        assertTrue(SpaceTransitions.shouldConsumeRestore(SpaceRequestResult.Unsupported))
    }

    @Test
    fun `restore stays armed after a transient error so the next ready frame retries`() {
        val transient = SpaceRequestResult.Error(IllegalStateException("scene not ready"))
        assertFalse(SpaceTransitions.shouldConsumeRestore(transient))
    }
}
