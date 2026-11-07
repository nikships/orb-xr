package com.ghosttyxr.app.terminal.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

class PairedMacTest {

    @Test
    fun `toPayload carries host port user and verified fingerprint`() {
        val mac = PairedMac(
            host = "192.168.1.42",
            port = 2222,
            user = "nik",
            hostFingerprint = "SHA256:abc",
            provisionedAtEpochMs = 123L,
        )
        val payload = mac.toPayload()
        assertEquals("192.168.1.42", payload.host)
        assertEquals(2222, payload.port)
        assertEquals("nik", payload.user)
        assertEquals("SHA256:abc", payload.fingerprint)
    }

    @Test
    fun `toPayload uses empty token so auto-connect never sends a password`() {
        val mac = PairedMac("h", 22, "u", "SHA256:fp", 0L)
        assertEquals("", mac.toPayload().token)
    }
}
