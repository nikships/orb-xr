package com.ghosttyxr.app.terminal.hands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentOrbTest {

    private val config = AgentOrbConfig(
        seed = "ghosttyxr",
        palette = GhosttyBrandPalette,
        archetype = OrbArchetype.Spiral,
    )

    @Test
    fun hashSeedIsDeterministicAndSeedSpecific() {
        assertEquals(AgentOrb.hashSeed("ghosttyxr"), AgentOrb.hashSeed("ghosttyxr"))
        assertNotEquals(AgentOrb.hashSeed("ghosttyxr"), AgentOrb.hashSeed("agent-cockpit"))
    }

    @Test
    fun createOrbStateUsesSeedPhaseAndArchetypeId() {
        val state = AgentOrb.createOrbState(config)
        val expectedPhase = (AgentOrb.hashSeed("ghosttyxr").toUInt().toLong() % 6283L) / 1000.0

        assertEquals(expectedPhase, state.phase, 0.0)
        assertEquals(OrbArchetype.Spiral.id, state.archetype)
        assertEquals(0, state.archetype)
    }

    @Test
    fun idleMotionAdvancesSpinMonotonicallyOverFiveSeconds() {
        val state = AgentOrb.createOrbState(config)
        var previousSpin = state.spin

        for (tick in 0..50) {
            AgentOrb.updateMotion(state, tick / 10.0)
            assertTrue("spin regressed at ${tick / 10.0}s", state.spin >= previousSpin)
            previousSpin = state.spin
        }
    }

    @Test
    fun oscillatorSignFlipReversesIdleSpinDirection() {
        val state = AgentOrb.createOrbState(config)
        AgentOrb.updateMotion(state, 0.0)
        val direction = state.spinDirection

        AgentOrb.updateMotion(state, 3.0)

        assertEquals(-direction, state.spinDirection, 0.0)
    }

    @Test
    fun archetypeIdsFollowDeclaredOrder() {
        assertEquals(0, OrbArchetype.Spiral.id)
        assertEquals(1, OrbArchetype.Nebula.id)
        assertEquals(2, OrbArchetype.Core.id)
        assertEquals(3, OrbArchetype.Deep.id)
    }
}
