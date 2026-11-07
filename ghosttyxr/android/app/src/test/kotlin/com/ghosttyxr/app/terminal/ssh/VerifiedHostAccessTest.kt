package com.ghosttyxr.app.terminal.ssh

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for [VerifiedHostAccess]: sequencing, verification/auth
 * failure boundaries, partial-cleanup failure handling, auth argument
 * propagation, capability checks, and repeated-close idempotency. Drives a
 * [FakeSshTransport] the class never sees directly — only through
 * [VerifiedHostAccess.connectShell] / [VerifiedHostAccess.connectTunnel].
 */
class VerifiedHostAccessTest {

    private val key = SshKey(
        name = "headset",
        algorithm = "Ed25519",
        privateKeyPem = "priv",
        publicKeyOpenSsh = "ssh-ed25519 AAAA headset",
        createdAtEpochMs = 0L,
    )

    private fun access(
        transport: FakeSshTransport,
    ): VerifiedHostAccess = VerifiedHostAccess(transportFactory = { transport })

    // --- Sequencing / happy path -------------------------------------------------

    @Test
    fun `connectShell runs handshake, verify, authenticate then openShell in order`() {
        val transport = FakeSshTransport()
        val access = access(transport)

        val shell = access.connectShell(
            host = "mac.local", port = 22, username = "nik",
            expectedFingerprint = null, password = "token", key = key,
            cols = 80, rows = 24, widthPx = 800, heightPx = 600,
        )

        assertEquals(FakeSshTransport.HandshakeCall("mac.local", 22, "nik"), transport.handshakeCall)
        assertEquals(
            FakeSshTransport.AuthenticateCall("nik", "token", key),
            transport.authenticateCall,
        )
        assertEquals(
            FakeSshTransport.OpenShellCall(80, 24, 800, 600, "xterm-256color"),
            transport.openShellCall,
        )
        assertNull(transport.openDirectTcpipCall)
        shell.close()
    }

    @Test
    fun `connectTunnel runs handshake, verify, authenticate then openDirectTcpip`() {
        val transport = FakeSshTransport()
        val access = access(transport)

        val tunnel = access.connectTunnel(
            host = "mac.local", port = 22, username = "nik",
            expectedFingerprint = null, password = "", key = key,
            targetHost = "127.0.0.1", targetPort = 4577,
        )

        assertEquals(FakeSshTransport.HandshakeCall("mac.local", 22, "nik"), transport.handshakeCall)
        assertEquals(FakeSshTransport.AuthenticateCall("nik", "", key), transport.authenticateCall)
        assertEquals(
            FakeSshTransport.OpenDirectTcpipCall("127.0.0.1", 4577, "127.0.0.1", 0),
            transport.openDirectTcpipCall,
        )
        assertNull(transport.openShellCall)
        tunnel.close()
    }

    @Test
    fun `established shell delegates read write resize and keepalive to the transport`() {
        val transport = FakeSshTransport()
        val shell = access(transport).connectShell(
            host = "h", port = 22, username = "u", expectedFingerprint = null,
            password = "p", cols = 80, rows = 24, widthPx = 1, heightPx = 1,
        )

        shell.read()
        shell.write(byteArrayOf(1, 2, 3))
        shell.resize(100, 30, 2, 2)
        shell.sendKeepAlive()

        assertEquals(1, transport.readCalls)
        assertEquals(1, transport.writtenBytes.size)
        assertEquals(1, transport.resizeCalls)
        assertEquals(1, transport.keepAliveCalls)
        shell.close()
    }

    // --- Verification precedence ---------------------------------------------

    @Test
    fun `verify runs before authenticate so a mismatched fingerprint never authenticates`() {
        val transport = FakeSshTransport()
        val access = access(transport)

        assertThrows(IllegalStateException::class.java) {
            access.connectShell(
                host = "h", port = 22, username = "u",
                expectedFingerprint = "SHA256:not-the-real-one", password = "p",
                cols = 80, rows = 24, widthPx = 1, heightPx = 1,
            )
        }

        assertNull(transport.authenticateCall)
        assertNull(transport.openShellCall)
        assertEquals(1, transport.closeCalls)
    }

    // --- Failure boundaries + cleanup -----------------------------------------

    @Test
    fun `handshake failure cleans up and never authenticates`() {
        val transport = FakeSshTransport(handshakeFailure = RuntimeException("handshake boom"))
        val access = access(transport)

        val ex = assertThrows(RuntimeException::class.java) {
            access.connectShell(
                host = "h", port = 22, username = "u", expectedFingerprint = null,
                password = "p", cols = 80, rows = 24, widthPx = 1, heightPx = 1,
            )
        }

        assertEquals("handshake boom", ex.message)
        assertNull(transport.authenticateCall)
        assertEquals(1, transport.closeCalls)
    }

    @Test
    fun `authenticate failure cleans up and never opens a channel`() {
        val transport = FakeSshTransport(authenticateFailure = RuntimeException("auth boom"))
        val access = access(transport)

        val ex = assertThrows(RuntimeException::class.java) {
            access.connectShell(
                host = "h", port = 22, username = "u", expectedFingerprint = null,
                password = "p", cols = 80, rows = 24, widthPx = 1, heightPx = 1,
            )
        }

        assertEquals("auth boom", ex.message)
        assertNull(transport.openShellCall)
        assertEquals(1, transport.closeCalls)
    }

    @Test
    fun `openShell failure cleans up`() {
        val transport = FakeSshTransport(openShellFailure = RuntimeException("shell boom"))
        val access = access(transport)

        val ex = assertThrows(RuntimeException::class.java) {
            access.connectShell(
                host = "h", port = 22, username = "u", expectedFingerprint = null,
                password = "p", cols = 80, rows = 24, widthPx = 1, heightPx = 1,
            )
        }

        assertEquals("shell boom", ex.message)
        assertEquals(1, transport.closeCalls)
    }

    @Test
    fun `openDirectTcpip failure cleans up`() {
        val transport = FakeSshTransport(openDirectTcpipFailure = RuntimeException("tunnel boom"))
        val access = access(transport)

        val ex = assertThrows(RuntimeException::class.java) {
            access.connectTunnel(
                host = "h", port = 22, username = "u", expectedFingerprint = null,
                password = "p", targetHost = "127.0.0.1", targetPort = 1,
            )
        }

        assertEquals("tunnel boom", ex.message)
        assertEquals(1, transport.closeCalls)
    }

    // --- Partial cleanup failure: close() itself throws during teardown -------

    @Test
    fun `handshake failure surfaces even if cleanup close also throws`() {
        val transport = FakeSshTransport(
            handshakeFailure = RuntimeException("handshake boom"),
            closeFailure = RuntimeException("close boom"),
        )
        val access = access(transport)

        val ex = assertThrows(RuntimeException::class.java) {
            access.connectShell(
                host = "h", port = 22, username = "u", expectedFingerprint = null,
                password = "p", cols = 80, rows = 24, widthPx = 1, heightPx = 1,
            )
        }

        assertEquals("handshake boom", ex.message)
        assertEquals(1, transport.closeCalls)
    }

    @Test
    fun `verify failure surfaces even if cleanup close also throws`() {
        val transport = FakeSshTransport(closeFailure = RuntimeException("close boom"))
        val access = access(transport)

        val ex = assertThrows(IllegalStateException::class.java) {
            access.connectShell(
                host = "h", port = 22, username = "u",
                expectedFingerprint = "SHA256:not-the-real-one", password = "p",
                cols = 80, rows = 24, widthPx = 1, heightPx = 1,
            )
        }

        assertTrue(ex.message.orEmpty().contains("does not match"))
        assertEquals(1, transport.closeCalls)
    }

    @Test
    fun `authenticate failure surfaces even if cleanup close also throws`() {
        val transport = FakeSshTransport(
            authenticateFailure = RuntimeException("auth boom"),
            closeFailure = RuntimeException("close boom"),
        )
        val access = access(transport)

        val ex = assertThrows(RuntimeException::class.java) {
            access.connectShell(
                host = "h", port = 22, username = "u", expectedFingerprint = null,
                password = "p", cols = 80, rows = 24, widthPx = 1, heightPx = 1,
            )
        }

        assertEquals("auth boom", ex.message)
        assertEquals(1, transport.closeCalls)
    }

    @Test
    fun `channel-open failure surfaces even if cleanup close also throws`() {
        val transport = FakeSshTransport(
            openShellFailure = RuntimeException("shell boom"),
            closeFailure = RuntimeException("close boom"),
        )
        val access = access(transport)

        val ex = assertThrows(RuntimeException::class.java) {
            access.connectShell(
                host = "h", port = 22, username = "u", expectedFingerprint = null,
                password = "p", cols = 80, rows = 24, widthPx = 1, heightPx = 1,
            )
        }

        assertEquals("shell boom", ex.message)
        assertEquals(1, transport.closeCalls)
    }

    // --- Auth argument propagation ---------------------------------------------

    @Test
    fun `key is passed through to authenticate when provided`() {
        val transport = FakeSshTransport()
        access(transport).connectShell(
            host = "h", port = 22, username = "u", expectedFingerprint = null,
            password = "token", key = key, cols = 80, rows = 24, widthPx = 1, heightPx = 1,
        ).close()

        assertSame(key, transport.authenticateCall?.key)
        assertEquals("token", transport.authenticateCall?.password)
        assertEquals("u", transport.authenticateCall?.user)
    }

    @Test
    fun `null key falls through to password-only authenticate call`() {
        val transport = FakeSshTransport()
        access(transport).connectShell(
            host = "h", port = 22, username = "u", expectedFingerprint = null,
            password = "token", key = null, cols = 80, rows = 24, widthPx = 1, heightPx = 1,
        ).close()

        assertNull(transport.authenticateCall?.key)
        assertEquals("token", transport.authenticateCall?.password)
    }

    // --- Capabilities ------------------------------------------------------------

    @Test
    fun `isAvailable delegates to the transport and does not leak a handle`() {
        val available = FakeSshTransport(available = true)
        assertTrue(access(available).isAvailable())
        assertEquals(1, available.closeCalls)

        val unavailable = FakeSshTransport(available = false)
        assertFalse(access(unavailable).isAvailable())
        assertEquals(1, unavailable.closeCalls)
        assertNull(unavailable.handshakeCall)
    }

    // --- Repeated close ------------------------------------------------------------

    @Test
    fun `closing an established shell repeatedly only closes the transport once`() {
        val transport = FakeSshTransport()
        val shell = access(transport).connectShell(
            host = "h", port = 22, username = "u", expectedFingerprint = null,
            password = "p", cols = 80, rows = 24, widthPx = 1, heightPx = 1,
        )

        shell.close()
        shell.close()
        shell.close()

        assertEquals(1, transport.closeCalls)
    }

    @Test
    fun `closing an established tunnel repeatedly only closes the transport once`() {
        val transport = FakeSshTransport()
        val tunnel = access(transport).connectTunnel(
            host = "h", port = 22, username = "u", expectedFingerprint = null,
            password = "p", targetHost = "127.0.0.1", targetPort = 1,
        )

        tunnel.close()
        tunnel.close()

        assertEquals(1, transport.closeCalls)
    }

    @Test
    fun `established shell exposes the verified fingerprint the trust policy computed`() {
        val transport = FakeSshTransport()
        val shell = access(transport).connectShell(
            host = "mac.local", port = 22, username = "u", expectedFingerprint = null,
            password = "p", cols = 80, rows = 24, widthPx = 1, heightPx = 1,
        )

        assertEquals(
            HostTrustPolicy.fingerprintSha256(HostKey(ByteArray(32) { it.toByte() }, "ssh-ed25519")),
            shell.verifiedFingerprint,
        )
        shell.close()
    }

    @Test
    fun `established tunnel exposes the verified fingerprint the trust policy computed`() {
        val transport = FakeSshTransport()
        val tunnel = access(transport).connectTunnel(
            host = "mac.local", port = 22, username = "u", expectedFingerprint = null,
            password = "p", targetHost = "127.0.0.1", targetPort = 1,
        )

        assertEquals(
            HostTrustPolicy.fingerprintSha256(HostKey(ByteArray(32) { it.toByte() }, "ssh-ed25519")),
            tunnel.verifiedFingerprint,
        )
        tunnel.close()
    }

    @Test
    fun `no expected fingerprint accepts the presented key and reports its fingerprint`() {
        val transport = FakeSshTransport()
        val shell = access(transport).connectShell(
            host = "mac.local", port = 22, username = "u", expectedFingerprint = null,
            password = "p", cols = 80, rows = 24, widthPx = 1, heightPx = 1,
        )

        assertEquals(
            HostTrustPolicy.fingerprintSha256(HostKey(ByteArray(32) { it.toByte() }, "ssh-ed25519")),
            shell.verifiedFingerprint,
        )
        shell.close()
    }

    // --- Serialized channel operations (close vs. concurrent read/close) ------

    @Test
    fun `close waits for an in-flight read on another thread before touching the transport`() {
        val readStarted = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<String>())
        val transport = FakeSshTransport(onRead = {
            readStarted.countDown()
            releaseRead.await()
            order.add("read")
        })
        val shell = access(transport).connectShell(
            host = "h", port = 22, username = "u", expectedFingerprint = null,
            password = "p", cols = 80, rows = 24, widthPx = 1, heightPx = 1,
        )

        val reader = thread { shell.read() }
        assertTrue(readStarted.await(2, TimeUnit.SECONDS))

        val closer = thread {
            shell.close()
            order.add("close")
        }
        // The closer should be blocked behind the channel's lock while the
        // read holds it — give it a beat and confirm it hasn't reached the
        // transport yet.
        Thread.sleep(100)
        assertEquals(0, transport.closeCalls)

        releaseRead.countDown()
        reader.join(2_000)
        closer.join(2_000)

        assertEquals(listOf("read", "close"), order)
        assertEquals(1, transport.closeCalls)
    }

    @Test
    fun `concurrent close calls from multiple threads still close the transport exactly once`() {
        val transport = FakeSshTransport()
        val shell = access(transport).connectShell(
            host = "h", port = 22, username = "u", expectedFingerprint = null,
            password = "p", cols = 80, rows = 24, widthPx = 1, heightPx = 1,
        )

        val closers = List(8) { thread { shell.close() } }
        closers.forEach { it.join(2_000) }

        assertEquals(1, transport.closeCalls)
    }
}
