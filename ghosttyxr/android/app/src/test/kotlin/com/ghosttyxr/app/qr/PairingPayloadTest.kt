package com.ghosttyxr.app.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingPayloadTest {

    @Test
    fun `parses full ssh url with token and fingerprint`() {
        val raw = "ssh://nik@192.168.1.42:22?token=abc123&fp=SHA256:Zm9v"
        val payload = PairingPayload.parse(raw).getOrThrow()
        assertEquals("192.168.1.42", payload.host)
        assertEquals(22, payload.port)
        assertEquals("nik", payload.user)
        assertEquals("abc123", payload.token)
        assertEquals("SHA256:Zm9v", payload.fingerprint)
    }

    @Test
    fun `defaults port to 22 when omitted`() {
        val payload = PairingPayload.parse("ssh://nik@example.com?token=abc").getOrThrow()
        assertEquals(22, payload.port)
    }

    @Test
    fun `fingerprint is optional`() {
        val payload = PairingPayload.parse("ssh://nik@example.com:22?token=abc").getOrThrow()
        assertNull(payload.fingerprint)
    }

    @Test
    fun `rejects non-ssh scheme`() {
        val result = PairingPayload.parse("http://nik@example.com:22?token=abc")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("scheme") == true)
    }

    @Test
    fun `rejects missing user`() {
        val result = PairingPayload.parse("ssh://example.com:22?token=abc")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("user") == true)
    }

    @Test
    fun `rejects missing token`() {
        val result = PairingPayload.parse("ssh://nik@example.com:22")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("token") == true)
    }

    @Test
    fun `rejects user with embedded password`() {
        val result = PairingPayload.parse("ssh://nik:secret@example.com:22?token=abc")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("password") == true)
    }

    @Test
    fun `blank token rejected`() {
        val result = PairingPayload.parse("ssh://nik@example.com:22?token=")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("token") == true)
    }

    @Test
    fun `url-decodes percent-encoded values`() {
        // `fp=SHA256:Zm9v/bar+baz` → percent-encoded `/` and `+`
        val raw = "ssh://nik@example.com:22?token=t%2Bok%2Fen&fp=SHA256%3AZm9v"
        val payload = PairingPayload.parse(raw).getOrThrow()
        assertEquals("t+ok/en", payload.token)
        assertEquals("SHA256:Zm9v", payload.fingerprint)
    }
}
