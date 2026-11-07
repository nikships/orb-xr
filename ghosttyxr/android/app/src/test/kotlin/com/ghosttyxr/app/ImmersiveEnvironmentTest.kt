package com.ghosttyxr.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ImmersiveEnvironmentTest {

    @Test
    fun knownIdResolvesToThatEnvironment() {
        val env = environmentForId("dikhololo_night")
        assertEquals("dikhololo_night", env.id)
        assertEquals("environments/dikhololo_night.glb", env.assetPath)
    }

    @Test
    fun unknownIdFallsBackToDefault() {
        val env = environmentForId("does-not-exist")
        assertEquals(DEFAULT_ENVIRONMENT_ID, env.id)
    }

    @Test
    fun defaultIsTheFirstEntryAndHasNoGeometry() {
        val default = environmentForId(DEFAULT_ENVIRONMENT_ID)
        assertSame(TERMINAL_ENVIRONMENTS.first(), default)
        assertEquals(DEFAULT_ENVIRONMENT_ID, default.id)
        // The black void has no glb geometry.
        assertNull(default.assetPath)
    }

    @Test
    fun everyEnvironmentIdIsUnique() {
        val ids = TERMINAL_ENVIRONMENTS.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}
