package com.ghosttyxr.app.terminal.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for [HostKeyFingerprint] — the single implementation
 * [HostTrustPolicy] (platform-free, the sole production caller via
 * [VerifiedHostAccess]) delegates to for the Ed25519-blob normalization +
 * SHA-256 + base64 algorithm.
 */
class HostKeyFingerprintTest {

    private val ed25519Key = HostKey(ByteArray(32) { it.toByte() }, "ssh-ed25519")

    @Test
    fun `raw bytes fingerprint is stable SHA256 base64 form`() {
        val a = HostKeyFingerprint.sha256(byteArrayOf(1, 2, 3))
        val b = HostKeyFingerprint.sha256(byteArrayOf(1, 2, 3))
        assertEquals(a, b)
        assertTrue(a.startsWith("SHA256:"))
        // Unpadded base64 — matches ssh-keygen -lf, never ends in '='.
        assertTrue(!a.endsWith("="))
    }

    @Test
    fun `ed25519 host key normalizes to the openssh blob before hashing`() {
        val direct = HostKeyFingerprint.sha256(ed25519Key)
        val rsaLike = HostKeyFingerprint.sha256(HostKey(ed25519Key.bytes, "ssh-rsa"))
        // Same raw bytes, different algorithm tag → the ed25519 path wraps
        // the bytes in an OpenSSH blob first, so the two must diverge.
        assertTrue(direct != rsaLike)
    }

    @Test
    fun `HostTrustPolicy delegates to the shared fingerprint implementation`() {
        assertEquals(HostKeyFingerprint.sha256(ed25519Key.bytes), HostTrustPolicy.fingerprintSha256(ed25519Key.bytes))
        assertEquals(HostKeyFingerprint.sha256(ed25519Key), HostTrustPolicy.fingerprintSha256(ed25519Key))
    }
}
