package com.ghosttyxr.app.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OnboardingAccessClientTest {
    @Test
    fun `parse maps onboarding pro access`() {
        val entitlement = OnboardingAccessClient.parseResponse(
            """{"tier":"pro","expiryTimeMillis":1700000000000,"source":"onboarding"}""",
        )
        assertEquals(Tier.PRO, entitlement.tier)
        assertEquals(1_700_000_000_000L, entitlement.expiryTimeMillis)
    }

    @Test
    fun `parse maps completed access to free`() {
        assertEquals(
            Tier.FREE,
            OnboardingAccessClient.parseResponse("""{"tier":"free","expiryTimeMillis":0}""").tier,
        )
    }

    @Test
    fun `malformed response throws`() {
        assertThrows(OnboardingAccessException::class.java) {
            OnboardingAccessClient.parseResponse("not json")
        }
    }
}
