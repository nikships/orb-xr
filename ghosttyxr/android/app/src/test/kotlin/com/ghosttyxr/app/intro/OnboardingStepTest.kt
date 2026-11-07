package com.ghosttyxr.app.intro

import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingStepTest {
    @Test
    fun `next advances to pairing and clamps there`() {
        assertEquals(OnboardingStep.PAIR, OnboardingStep.WELCOME.next())
        assertEquals(OnboardingStep.PAIR, OnboardingStep.PAIR.next())
    }

    @Test
    fun `previous walks backward and clamps at welcome`() {
        assertEquals(OnboardingStep.WELCOME, OnboardingStep.WELCOME.previous())
        assertEquals(OnboardingStep.WELCOME, OnboardingStep.PAIR.previous())
    }
}
