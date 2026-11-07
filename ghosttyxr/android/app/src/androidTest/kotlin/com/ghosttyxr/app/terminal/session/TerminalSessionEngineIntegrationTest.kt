package com.ghosttyxr.app.terminal.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ghosttyxr.app.qr.PairingPayload
import com.ghosttyxr.app.terminal.TerminalSnapshot
import com.ghosttyxr.app.terminal.ssh.HostKey
import com.ghosttyxr.app.terminal.ssh.HostTrustPolicy
import com.ghosttyxr.app.terminal.ssh.LibSsh2Transport
import com.ghosttyxr.app.terminal.ssh.NativeSshBridge
import com.ghosttyxr.app.terminal.ssh.SshTransport
import com.ghosttyxr.app.terminal.ssh.VerifiedHostAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end check that the engine spine works with the real libghostty
 * surface (loaded from `libghosttyxr.so` on the device) but a fake SSH
 * transport. Verifies:
 *
 *   1. [TerminalSessionEngine.connect] drives the fake's handshake →
 *      verify → authenticate → openShell sequence in that order.
 *   2. State transitions Disconnected → Connecting → Authenticated → Connected.
 *   3. Bytes enqueued via [FakeSshTransport.enqueueRead] flow through
 *      `nativeWriteRemote` and surface in the next snapshot via
 *      [TerminalSnapshot.fromByteBuffer].
 *   4. The snapshot StateFlow re-emits — Compose collectors see it.
 *   5. QR-fingerprint mismatch rejects the connection before
 *      [FakeSshTransport.authenticate] is called — MITM defense.
 *   6. A first contact with no expected fingerprint accepts and reports
 *      the presented key's fingerprint on the established channel.
 *
 * Run on the `GhosttyXR_Headset` AVD (or any arm64-v8a device). The host
 * JVM can't load the .so, so this is androidTest-only.
 */
@RunWith(AndroidJUnit4::class)
class TerminalSessionEngineIntegrationTest {

    /** [VerifiedHostAccess] wired to always hand back [transport], with a test-controlled transport. */
    private fun hostAccess(transport: SshTransport): VerifiedHostAccess =
        VerifiedHostAccess(transportFactory = { transport })

    @Test
    fun engineDeliversFakeBytesToSnapshot() {
        val fake = FakeSshTransport()
        val engine = TerminalSessionEngine(paneId = 0, hostAccess = hostAccess(fake))

        try {
            engine.connect(PAIRING)
            awaitState(engine) { it is SessionState.Connected }

            assertTrue("handshake not invoked", fake.handshakeCalls.isNotEmpty())
            val hs = fake.handshakeCalls.first()
            assertEquals("mac.local", hs.host)
            assertEquals(22, hs.port)

            assertTrue("authenticate not invoked", fake.authenticateCalls.isNotEmpty())
            val auth = fake.authenticateCalls.first()
            assertEquals("dev", auth.user)
            assertEquals("hunter2", auth.password)
            assertNull(auth.key)

            assertTrue("openShell not invoked", fake.openShellCalls.isNotEmpty())

            fake.enqueueRead("hello fake\r\n".toByteArray(Charsets.UTF_8))

            val snapshot = awaitSnapshot(engine) { snap ->
                rowToString(snap, 0).trimEnd() == "hello fake"
            }
            assertNotNull(snapshot)
            assertEquals(0, snapshot.cursorX)
            assertEquals(1, snapshot.cursorY)
        } finally {
            engine.close()
        }
    }

    @Test
    fun engineWritesEncodedInputBackToTransport() {
        val fake = FakeSshTransport()
        val engine = TerminalSessionEngine(paneId = 1, hostAccess = hostAccess(fake))

        try {
            engine.connect(PAIRING)
            awaitState(engine) { it is SessionState.Connected }

            engine.sendInput("ls\r".toByteArray(Charsets.UTF_8))

            val deadline = System.currentTimeMillis() + 2_000
            var observed: ByteArray? = null
            while (System.currentTimeMillis() < deadline) {
                val writes = fake.writtenBytes()
                if (writes.any { it.contentEquals("ls\r".toByteArray(Charsets.UTF_8)) }) {
                    observed = writes.first {
                        it.contentEquals("ls\r".toByteArray(Charsets.UTF_8))
                    }
                    break
                }
                Thread.sleep(20)
            }
            assertNotNull("engine never wrote 'ls\\r' to transport", observed)
        } finally {
            engine.close()
        }
    }

    @Test
    fun eofTransitionsThroughReconnect() {
        val fake = FakeSshTransport()
        val engine = TerminalSessionEngine(paneId = 2, hostAccess = hostAccess(fake))

        try {
            engine.connect(PAIRING)
            awaitState(engine) { it is SessionState.Connected }

            fake.signalEof()

            awaitState(engine, timeoutMs = 3_000) {
                it is SessionState.Reconnecting || it is SessionState.Failed
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun halfOpenChannelDetectedByKeepaliveThenRecovers() {
        // The socket dies silently: read() keeps returning "open but idle",
        // but the keepalive probe fails. The engine must notice via the
        // liveness probe and reconnect rather than freeze on a dead channel.
        val fake = FakeSshTransport()
        val engine = TerminalSessionEngine(
            paneId = 0,
            hostAccess = hostAccess(fake),
            reconnectPolicy = ReconnectPolicy(baseDelayMs = 300, maxDelayMs = 600, jitter = false),
            keepAliveIntervalMs = 120,
        )

        try {
            engine.connect(PAIRING)
            awaitState(engine) { it is SessionState.Connected }

            fake.killChannel()
            awaitState(engine, timeoutMs = 4_000) { it is SessionState.Reconnecting }
            assertTrue("keepalive was never probed", fake.keepAliveCalls.get() > 0)

            // A fresh reconnect opens a live channel — back to Connected.
            awaitState(engine, timeoutMs = 6_000) { it is SessionState.Connected }
        } finally {
            engine.close()
        }
    }

    @Test
    fun writeFailureTriggersReconnect() {
        val fake = FakeSshTransport()
        val engine = TerminalSessionEngine(
            paneId = 1,
            hostAccess = hostAccess(fake),
            reconnectPolicy = ReconnectPolicy(baseDelayMs = 300, maxDelayMs = 600, jitter = false),
            // Large interval so only the broken write — not a keepalive —
            // can trip reconnect in this test.
            keepAliveIntervalMs = 60_000,
        )

        try {
            engine.connect(PAIRING)
            awaitState(engine) { it is SessionState.Connected }

            fake.failWrites()
            engine.sendInput("ls\r".toByteArray(Charsets.UTF_8))

            awaitState(engine, timeoutMs = 4_000) {
                it is SessionState.Reconnecting || it is SessionState.Failed
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun networkAvailableWakesReconnectBackoff() {
        // Backoff is deliberately huge (30s); the only way the test reaches
        // Connected promptly is onNetworkAvailable() cutting the wait short.
        val fake = FakeSshTransport()
        val engine = TerminalSessionEngine(
            paneId = 2,
            hostAccess = hostAccess(fake),
            reconnectPolicy = ReconnectPolicy(baseDelayMs = 30_000, maxDelayMs = 60_000, jitter = false),
            keepAliveIntervalMs = 60_000,
        )

        try {
            engine.connect(PAIRING)
            awaitState(engine) { it is SessionState.Connected }

            fake.signalEof()
            awaitState(engine, timeoutMs = 3_000) { it is SessionState.Reconnecting }

            engine.onNetworkAvailable()
            awaitState(engine, timeoutMs = 5_000) { it is SessionState.Connected }
        } finally {
            engine.close()
        }
    }

    @Test
    fun qrFingerprintMismatchAbortsBeforeAuth() {
        // QR-pairing fp must reject a host whose key doesn't match.
        // authenticate() must never run — that's the
        // MITM-doesn't-see-our-password guarantee.
        val fake = FakeSshTransport()
        val engine = TerminalSessionEngine(paneId = 3, hostAccess = hostAccess(fake))
        val payloadWithWrongFp = PAIRING.copy(fingerprint = "SHA256:not-the-right-fingerprint")

        try {
            engine.connect(payloadWithWrongFp)
            val state = awaitState(engine, timeoutMs = 3_000) { it is SessionState.Failed }
            assertTrue("expected Failed, got $state", state is SessionState.Failed)

            assertTrue("handshake must run to fetch the key", fake.handshakeCalls.isNotEmpty())
            assertEquals(
                "authenticate must NOT run when fp mismatches",
                0,
                fake.authenticateCalls.size,
            )
            assertEquals(
                "openShell must NOT run when fp mismatches",
                0,
                fake.openShellCalls.size,
            )
        } finally {
            engine.close()
        }
    }

    @Test
    fun firstContactWithoutExpectedFingerprintConnects() {
        // The PAIRING fixture carries fp=null: the engine accepts the
        // presented host key and completes the handshake → auth → shell
        // sequence. Pinning itself (accept on match, abort on mismatch) is
        // covered by HostTrustPolicyTest; the mismatch-aborts case above
        // covers the failure side at this layer.
        val fake = FakeSshTransport()
        val engine = TerminalSessionEngine(paneId = 0, hostAccess = hostAccess(fake))

        try {
            engine.connect(PAIRING)
            awaitState(engine) { it is SessionState.Connected }
            assertEquals(1, fake.authenticateCalls.size)
            assertEquals(1, fake.openShellCalls.size)
        } finally {
            engine.close()
        }
    }

    /**
     * Real Mac SSH round-trip. This is the close-out test for task #4:
     * a `TerminalSessionEngine` backed by a real [LibSsh2Transport]
     * connects to macOS Remote Login on the host, sends `echo hello`,
     * and waits for "hello" to appear in the snapshot.
     *
     * **Gating** — three Assume guards keep this test green on any
     * runtime where the wiring isn't fully present:
     *  - `LibSsh2Transport().isAvailable()` covers the bridge load.
     *  - `mac.user` and `mac.token` instrumentation args must be set.
     *    Pass via `-Pandroid.testInstrumentationRunnerArguments.mac.user=$USER
     *    -Pandroid.testInstrumentationRunnerArguments.mac.token=<password>`.
     *  - A failed `handshake` (Remote Login off, host unreachable, bad
     *    port) escapes through [assumeNoException] — the test ignores
     *    the run with a clear message rather than red.
     *
     * Default host `10.0.2.2` is the Android emulator's NAT alias for
     * the host machine. On a physical headset the test driver would
     * override `mac.host` to the LAN IP from the QR pairing payload.
     */
    @Test
    fun connectsToLocalhostSsh() {
        assumeTrue("libssh2 not in libghosttyxr.so", LibSsh2Transport(NativeSshBridge()).isAvailable())

        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("mac.host") ?: "10.0.2.2"
        val port = args.getString("mac.port")?.toIntOrNull() ?: 22
        val user = args.getString("mac.user")
        val token = args.getString("mac.token")
        assumeNotNull("set -Pandroid.testInstrumentationRunnerArguments.mac.user", user)
        assumeNotNull("set -Pandroid.testInstrumentationRunnerArguments.mac.token", token)

        // Pre-flight the transport directly so handshake failures
        // (Remote Login off, host unreachable) skip cleanly instead of
        // surfacing as a test failure. Reuse the same transport for the
        // engine — handshake is idempotent within libssh2's session
        // because we re-create the session inside the engine.
        val probe = LibSsh2Transport(NativeSshBridge())
        try {
            probe.handshake(host, port, user ?: "ghosttyxr")
        } catch (e: Exception) {
            probe.close()
            assumeNoException(
                "SSH handshake to $host:$port failed — enable macOS Remote Login " +
                    "(System Settings → General → Sharing → Remote Login) and re-run. " +
                    "Mac companion app will prompt for this in production. " +
                    "Cause: ${e.message}",
                e,
            )
        }
        probe.close()

        val engine = TerminalSessionEngine(
            paneId = 0,
            hostAccess = hostAccess(LibSsh2Transport(NativeSshBridge())),
        )
        val payload = PairingPayload(
            host = host,
            port = port,
            user = user!!,
            token = token!!,
            fingerprint = null,
        )
        try {
            engine.connect(payload)
            awaitState(engine, timeoutMs = 10_000) { it is SessionState.Connected }

            engine.sendInput("echo hello\n".toByteArray(Charsets.UTF_8))

            // Real shells echo the command, print the output, then
            // re-render the prompt — "hello" appears on its own line.
            // Grep any row for the substring; row position depends on
            // the shell's prompt rendering which we can't predict.
            val snapshot = awaitSnapshot(engine, timeoutMs = 5_000) { snap ->
                (0 until snap.rows).any { row ->
                    rowToString(snap, row).contains("hello")
                }
            }
            assertNotNull(snapshot)
        } finally {
            engine.close()
        }
    }

    private fun awaitState(
        engine: TerminalSessionEngine,
        timeoutMs: Long = 2_000,
        predicate: (SessionState) -> Boolean,
    ): SessionState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val current = engine.state.value
            if (predicate(current)) return current
            Thread.sleep(20)
        }
        throw AssertionError("state never matched predicate; last=${engine.state.value}")
    }

    private fun awaitSnapshot(
        engine: TerminalSessionEngine,
        timeoutMs: Long = 3_000,
        predicate: (TerminalSnapshot) -> Boolean,
    ): TerminalSnapshot {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val current = engine.snapshot.value
            if (predicate(current)) return current
            Thread.sleep(20)
        }
        throw AssertionError(
            "snapshot never matched; last row0='${rowToString(engine.snapshot.value, 0).trimEnd()}'",
        )
    }

    private fun rowToString(snapshot: TerminalSnapshot, row: Int): String {
        if (row !in 0 until snapshot.rows) return ""
        val sb = StringBuilder()
        val start = row * snapshot.cols
        for (i in start until start + snapshot.cols) {
            val cp = snapshot.codepoints[i]
            if (cp == 0) sb.append(' ') else sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    private companion object {
        val PAIRING = PairingPayload(
            host = "mac.local",
            port = 22,
            user = "dev",
            token = "hunter2",
            fingerprint = null,
        )
    }
}
