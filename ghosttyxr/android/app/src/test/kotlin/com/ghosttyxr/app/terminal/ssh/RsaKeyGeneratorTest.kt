package com.ghosttyxr.app.terminal.ssh

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RsaKeyGeneratorTest {
    @Test
    fun generatedKeyUsesCompatiblePemAndOpenSshEncoding() {
        val key = RsaKeyGenerator().generate("  headset  ", bits = 1024)

        assertEquals("headset", key.name)
        assertEquals("RSA", key.algorithm)

        val pemLines = key.privateKeyPem.trimEnd().lines()
        assertEquals("-----BEGIN PRIVATE KEY-----", pemLines.first())
        assertEquals("-----END PRIVATE KEY-----", pemLines.last())
        val bodyLines = pemLines.subList(1, pemLines.lastIndex)
        assertTrue(bodyLines.dropLast(1).all { it.length == 64 })
        assertTrue(bodyLines.last().length in 1..64)
        val privateDer = Base64.getDecoder().decode(bodyLines.joinToString(""))
        assertEquals(
            "RSA",
            KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(privateDer))
                .algorithm,
        )

        val publicParts = key.publicKeyOpenSsh.split(' ')
        assertEquals(listOf("ssh-rsa", "headset@ghosttyxr"), listOf(publicParts[0], publicParts[2]))
        val blob = ByteBuffer.wrap(Base64.getDecoder().decode(publicParts[1]))
        assertEquals("ssh-rsa", readSshString(blob).toString(StandardCharsets.US_ASCII))
        assertTrue(readSshString(blob).isNotEmpty()) // public exponent
        assertTrue(readSshString(blob).isNotEmpty()) // modulus
        assertEquals(0, blob.remaining())
    }

    private fun readSshString(buffer: ByteBuffer): ByteArray {
        val bytes = ByteArray(buffer.int)
        buffer.get(bytes)
        return bytes
    }
}
