package com.ghosttyxr.app.terminal.ssh

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ghosttyxr.app.qr.PairingPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end provisioning over a scripted transport: the provisioner should
 * verify the host key, push the headset pubkey, parse the sentinel, and return
 * a [PairedMac] carrying the verified fingerprint.
 */
@RunWith(AndroidJUnit4::class)
class MacProvisionerInstrumentedTest {

    private val hostKey = HostKey(ByteArray(32) { it.toByte() }, "ssh-ed25519")
    private val key = SshKey(
        name = "ghosttyxr-headset",
        algorithm = "RSA",
        privateKeyPem = "PEM",
        publicKeyOpenSsh = "ssh-rsa AAAAFAKE headset@ghosttyxr",
        createdAtEpochMs = 0L,
    )

    private fun payload(fp: String?) = PairingPayload(
        host = "10.0.0.5", port = 22, user = "nik", token = "tok", fingerprint = fp,
    )

    private fun provisioner(transport: SshTransport) = MacProvisioner(
        hostAccess = VerifiedHostAccess(transportFactory = { transport }),
        existingKey = { key },
    )

    @Test
    fun successPushesKeyAndReturnsVerifiedFingerprint() {
        val transport = ScriptedTransport(hostKey, exitStatus = 0)
        val expectedFp = HostTrustPolicy.fingerprintSha256(hostKey)

        val result = provisioner(transport).provision(payload(expectedFp))

        val provisioned = result.getOrThrow()
        assertEquals(expectedFp, provisioned.mac.hostFingerprint)
        assertEquals("nik", provisioned.mac.user)
        assertEquals(key, provisioned.key)
        assertTrue("expected the pubkey append command to be written", transport.wroteAuthorizedKeysCommand)
        assertTrue("transport should be closed", transport.closed)
    }

    @Test
    fun nonZeroExitFails() {
        val transport = ScriptedTransport(hostKey, exitStatus = 1)
        val result = provisioner(transport).provision(payload(null))
        assertTrue(result.isFailure)
    }

    @Test
    fun mismatchedHostFingerprintFailsBeforeAuth() {
        val transport = ScriptedTransport(hostKey, exitStatus = 0)
        val result = provisioner(transport).provision(payload("SHA256:wrong"))
        assertTrue(result.isFailure)
        assertEquals(false, transport.authenticated)
    }

    /**
     * Minimal scripted [SshTransport]: returns a fixed host key, records auth,
     * and replies to the authorized-keys write with the sentinel line carrying
     * [exitStatus]. Not the shared `FakeSshTransport` because that one decouples
     * reads from writes; here we need the write to drive the next read.
     */
    private class ScriptedTransport(
        private val hostKey: HostKey,
        private val exitStatus: Int,
    ) : SshTransport {
        var authenticated = false
        var closed = false
        var wroteAuthorizedKeysCommand = false
        private val pending = ArrayDeque<ByteArray>()

        override fun isAvailable() = true
        override fun handshake(host: String, port: Int, username: String) = hostKey
        override fun authenticate(user: String, password: String, key: SshKey?) {
            authenticated = true
        }
        override fun openShell(cols: Int, rows: Int, widthPx: Int, heightPx: Int, term: String) {}
        override fun resize(cols: Int, rows: Int, widthPx: Int, heightPx: Int) {}

        override fun read(maxBytes: Int): ByteArray? = pending.removeFirstOrNull() ?: ByteArray(0)

        override fun write(data: ByteArray) {
            val cmd = String(data, Charsets.UTF_8)
            if (cmd.contains("authorized_keys")) {
                wroteAuthorizedKeysCommand = true
                pending.add("${MacProvisioner.SENTINEL}:$exitStatus\n".toByteArray(Charsets.UTF_8))
            }
        }

        override fun close() {
            closed = true
        }
    }
}
