package com.ghosttyxr.app.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntitlementTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `pro with future expiry is pro`() {
        val ent = Entitlement(Tier.PRO, expiryTimeMillis = now + 1_000)
        assertTrue(ent.isPro(now))
    }

    @Test
    fun `pro within the expiry slack still counts`() {
        // Expired an hour ago but inside the 1-day RTDN-lag slack.
        val ent = Entitlement(Tier.PRO, expiryTimeMillis = now - 60L * 60 * 1000)
        assertTrue(ent.isPro(now))
    }

    @Test
    fun `pro past the slack is free`() {
        val ent = Entitlement(Tier.PRO, expiryTimeMillis = now - Entitlement.EXPIRY_SLACK_MS - 1)
        assertFalse(ent.isPro(now))
    }

    @Test
    fun `free tier is never pro regardless of expiry`() {
        assertFalse(Entitlement(Tier.FREE, expiryTimeMillis = now + 1_000_000).isPro(now))
    }

    @Test
    fun `from parses server doc fields`() {
        assertEquals(Tier.PRO, Entitlement.from("pro", 123L).tier)
        assertEquals(123L, Entitlement.from("pro", 123L).expiryTimeMillis)
        assertEquals(Tier.FREE, Entitlement.from("free", null).tier)
        assertEquals(Tier.FREE, Entitlement.from(null, null).tier)
        assertEquals(Tier.FREE, Entitlement.from("garbage", null).tier)
        assertEquals(0L, Entitlement.from("pro", null).expiryTimeMillis)
    }
}
