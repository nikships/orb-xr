package com.ghosttyxr.app.intro

import com.ghosttyxr.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnboardingCoachStepTest {
    @Test
    fun `every step maps to its own bundled narration clip`() {
        assertEquals(R.raw.coach_first_terminal, OnboardingCoachStep.FIRST_TERMINAL.narrationRes)
        assertEquals(R.raw.coach_spawn_pane, OnboardingCoachStep.SPAWN_PANE.narrationRes)
        assertEquals(R.raw.coach_lock_layout, OnboardingCoachStep.LOCK_LAYOUT.narrationRes)
        assertEquals(R.raw.coach_summon_pet, OnboardingCoachStep.SUMMON_PET.narrationRes)
        assertEquals(R.raw.coach_ready, OnboardingCoachStep.READY.narrationRes)
        assertEquals(
            "narration clips must be distinct per step",
            OnboardingCoachStep.entries.size,
            OnboardingCoachStep.entries.map { it.narrationRes }.toSet().size,
        )
    }

    @Test
    fun `gesture lessons carry the illustrated pose art`() {
        // FIRST_TERMINAL points at the real terminal behind the coach — no illustration.
        assertNull(OnboardingCoachStep.FIRST_TERMINAL.illustrationRes)
        assertEquals(R.drawable.coach_spawn_pane, OnboardingCoachStep.SPAWN_PANE.illustrationRes)
        assertEquals(R.drawable.coach_prayer_hold, OnboardingCoachStep.LOCK_LAYOUT.illustrationRes)
        assertEquals(R.drawable.coach_palm_pet, OnboardingCoachStep.SUMMON_PET.illustrationRes)
        assertEquals(R.drawable.coach_ready_badge, OnboardingCoachStep.READY.illustrationRes)
    }
}
