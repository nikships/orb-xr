package com.ghosttyxr.app.billing

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VerifyPurchaseClientTest {

    @Test
    fun `request json carries the purchase token`() {
        val json = VerifyPurchaseClient.buildRequestJson("token-abc/123")
        assertEquals("token-abc/123", JSONObject(json).getString("purchaseToken"))
    }

    @Test
    fun `parse maps a pro verdict`() {
        val ent = VerifyPurchaseClient.parseResponse(
            """{"tier":"pro","expiryTimeMillis":1700000000000}""",
        )
        assertEquals(Tier.PRO, ent.tier)
        assertEquals(1_700_000_000_000L, ent.expiryTimeMillis)
    }

    @Test
    fun `parse maps a free verdict`() {
        val ent = VerifyPurchaseClient.parseResponse("""{"tier":"free"}""")
        assertEquals(Tier.FREE, ent.tier)
        assertEquals(0L, ent.expiryTimeMillis)
    }

    @Test
    fun `malformed payloads throw`() {
        assertThrows(VerifyPurchaseException::class.java) {
            VerifyPurchaseClient.parseResponse("not json")
        }
    }

    @Test
    fun `missing tier maps to free`() {
        assertEquals(Tier.FREE, VerifyPurchaseClient.parseResponse("{}").tier)
    }
}
