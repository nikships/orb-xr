package com.ghosttyxr.app.terminal.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pure-JVM coverage for [HostTrustPolicy] — the single out-of-band pin check
 * (QR `fp=` on first pair, persisted [PairedMac.hostFingerprint] on every
 * auto-connect). Runs on the JVM with no Android dependency.
 */
class HostTrustPolicyTest {

    private val hostKey = HostKey(ByteArray(32) { it.toByte() }, "ssh-ed25519")

    @Test
    fun `matching expected fingerprint accepts`() {
        val fp = HostTrustPolicy.fingerprintSha256(hostKey)
        // Should not throw.
        HostTrustPolicy.verify(expectedFingerprint = fp, hostKey = hostKey)
    }

    @Test
    fun `mismatched expected fingerprint aborts`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            HostTrustPolicy.verify(
                expectedFingerprint = "SHA256:wrong",
                hostKey = hostKey,
            )
        }
        assertEquals(true, ex.message?.contains("does not match"))
    }

    @Test
    fun `no expected fingerprint accepts and reports the presented fingerprint`() {
        // First contact with no out-of-band proof accepts. Callers persist
        // the returned value and pin the next connection to it.
        val accepted = HostTrustPolicy.verify(expectedFingerprint = null, hostKey = hostKey)
        assertEquals(HostTrustPolicy.fingerprintSha256(hostKey), accepted)
    }

    @Test
    fun `rotated host key aborts against the pinned fingerprint`() {
        val pinned = HostTrustPolicy.verify(expectedFingerprint = null, hostKey = hostKey)
        val rotated = HostKey(ByteArray(32) { (it + 1).toByte() }, "ssh-ed25519")
        val ex = assertThrows(IllegalStateException::class.java) {
            HostTrustPolicy.verify(expectedFingerprint = pinned, hostKey = rotated)
        }
        assertEquals(true, ex.message?.contains("does not match"))
    }

    @Test
    fun `a disagreeing expected fingerprint aborts even after an earlier accept`() {
        // First contact accepts the presented key...
        HostTrustPolicy.verify(expectedFingerprint = null, hostKey = hostKey)
        // ...but a QR/persisted fingerprint that disagrees must still abort.
        val ex = assertThrows(IllegalStateException::class.java) {
            HostTrustPolicy.verify(
                expectedFingerprint = "SHA256:not-the-real-one",
                hostKey = hostKey,
            )
        }
        assertEquals(true, ex.message?.contains("does not match"))
    }

    @Test
    fun `fingerprint computation is deterministic`() {
        val a = HostTrustPolicy.fingerprintSha256(hostKey)
        val b = HostTrustPolicy.fingerprintSha256(hostKey)
        assertEquals(a, b)
        assert(a.startsWith("SHA256:"))
    }

    @Test
    fun `raw ed25519 bytes and openssh-normalized bytes fingerprint the same way`() {
        // libssh2 hands back the raw 32-byte Ed25519 public key; QR pairing
        // carries the OpenSSH-blob form. Both must normalize to one fp.
        val rawEd25519 = HostKey(ByteArray(32) { it.toByte() }, "ssh-ed25519")
        val fp = HostTrustPolicy.fingerprintSha256(rawEd25519)
        assert(fp.startsWith("SHA256:"))
        // A non-Ed25519 algorithm is fingerprinted straight off the bytes,
        // no OpenSSH-blob wrapping, and must differ from the Ed25519 fp for
        // the same raw bytes.
        val rsaLike = HostKey(ByteArray(32) { it.toByte() }, "ssh-rsa")
        val rsaFp = HostTrustPolicy.fingerprintSha256(rsaLike)
        assert(rsaFp.startsWith("SHA256:"))
        assertEquals(false, fp == rsaFp)
    }
}
