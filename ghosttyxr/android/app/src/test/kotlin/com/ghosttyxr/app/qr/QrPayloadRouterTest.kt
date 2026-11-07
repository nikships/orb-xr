package com.ghosttyxr.app.qr

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class QrPayloadRouterTest {

    @Before
    fun setUp() {
        PendingPairing.clear()
    }

    @After
    fun tearDown() {
        PendingPairing.clear()
    }

    @Test
    fun `valid pairing payload is stored and delivered`() {
        val router = QrPayloadRouter()
        var delivered: PairingPayload? = null

        val result = router.route(
            raw = VALID_PAIRING,
            onRawQr = null,
            onPaired = { delivered = it },
        )

        assertTrue(result is QrRouteResult.Paired)
        assertEquals("example.com", delivered?.host)
        assertSame(delivered, PendingPairing.flow.value)
    }

    @Test
    fun `duplicate payload is ignored`() {
        val router = QrPayloadRouter()
        var deliveries = 0

        router.route(VALID_PAIRING, null) { deliveries++ }
        val result = router.route(VALID_PAIRING, null) { deliveries++ }

        assertSame(QrRouteResult.Duplicate, result)
        assertEquals(1, deliveries)
    }

    @Test
    fun `custom raw handler bypasses pairing parser`() {
        val router = QrPayloadRouter()
        var handledRaw: String? = null

        val result = router.route(
            raw = "phone-companion-payload",
            onRawQr = {
                handledRaw = it
                true
            },
            onPaired = { error("Pairing callback should not run") },
        )

        assertSame(QrRouteResult.HandledRaw, result)
        assertEquals("phone-companion-payload", handledRaw)
        assertNull(PendingPairing.flow.value)
    }

    @Test
    fun `invalid pairing payload returns parse error`() {
        val result = QrPayloadRouter().route(
            raw = "not-a-pairing-url",
            onRawQr = null,
            onPaired = { error("Pairing callback should not run") },
        )

        assertTrue(result is QrRouteResult.Invalid)
    }

    private companion object {
        const val VALID_PAIRING = "ssh://nik@example.com:22?token=abc"
    }
}
