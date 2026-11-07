package com.ghosttyxr.app.terminal.session

import com.ghosttyxr.app.terminal.ssh.HostKey
import com.ghosttyxr.app.terminal.ssh.SshKey
import com.ghosttyxr.app.terminal.ssh.SshTransport
import java.util.concurrent.LinkedBlockingDeque

/**
 * Test fake for [SshTransport]. Plays back canned bytes on [read] and
 * records every [write] in a buffer the test can inspect. Lets
 * `TerminalSessionEngineIntegrationTest` exercise the full engine →
 * libghostty → snapshot pipeline without needing zig-native's SSH zig
 * exports to land first.
 *
 * Models the post-#4-API split: [handshake] returns a synthetic
 * [HostKey] [VerifiedHostAccess] runs through [HostTrustPolicy.verify], then
 * [authenticate] is a separate step — same order as the production
 * libssh2 wire calls.
 *
 * Thread-safe: the engine drives this from its single-thread dispatcher,
 * but tests assert from the main thread, so the queues are concurrent.
 */
class FakeSshTransport(
    /**
     * Bytes that [handshake] returns as the host key. Default is a
     * deterministic fingerprint so tests get a stable `SHA256:<base64>` form.
     */
    private val presentedHostKey: ByteArray = DEFAULT_HOST_KEY,
    private val presentedHostKeyAlgorithm: String = "ssh-ed25519",
) : SshTransport {
    private val readQueue = LinkedBlockingDeque<ByteArray>()
    private val writes = mutableListOf<ByteArray>()
    private val writeLock = Any()

    @Volatile private var handshaked: Boolean = false
    @Volatile private var authenticated: Boolean = false
    @Volatile private var shellOpen: Boolean = false
    @Volatile private var eof: Boolean = false

    // Failure injection for the reconnect-resilience tests.
    @Volatile private var keepAliveAlive: Boolean = true
    @Volatile private var writesFail: Boolean = false

    /** Times the engine has probed liveness via [sendKeepAlive]. */
    val keepAliveCalls = java.util.concurrent.atomic.AtomicInteger(0)

    val handshakeCalls = LinkedBlockingDeque<HandshakeCall>()
    val authenticateCalls = LinkedBlockingDeque<AuthenticateCall>()
    val openShellCalls = LinkedBlockingDeque<OpenShellCall>()

    data class HandshakeCall(val host: String, val port: Int, val username: String)
    data class AuthenticateCall(val user: String, val password: String, val key: SshKey?)
    data class OpenShellCall(
        val cols: Int,
        val rows: Int,
        val widthPx: Int,
        val heightPx: Int,
        val term: String,
    )

    /**
     * Enqueue bytes that the next [read] call will return. Multiple calls
     * stack — each `read` consumes one queued ByteArray. Use empty arrays
     * to simulate idle-but-open ticks; null is reserved for EOF (call
     * [signalEof] instead).
     */
    fun enqueueRead(bytes: ByteArray) {
        readQueue.offer(bytes)
    }

    /** Signal channel EOF — the next [read] returns null and the engine's read loop exits. */
    fun signalEof() {
        eof = true
    }

    /**
     * Simulate a half-open connection: [read] keeps returning "open but
     * idle" while the channel is actually dead, so the engine's keepalive
     * probe ([sendKeepAlive]) reports false and routes to reconnect. A
     * subsequent [openShell] (i.e. a fresh reconnect) revives the channel,
     * modelling "old socket dead, new socket fine".
     */
    fun killChannel() {
        keepAliveAlive = false
    }

    /** Make every subsequent [write] throw, simulating a broken pipe. */
    fun failWrites() {
        writesFail = true
    }

    /** Stop failing writes (e.g. after a simulated reconnect recovers). */
    fun recoverWrites() {
        writesFail = false
    }

    /** All bytes the engine has called [write] with, in order. */
    fun writtenBytes(): List<ByteArray> = synchronized(writeLock) { writes.toList() }

    override fun isAvailable(): Boolean = true

    override fun handshake(host: String, port: Int, username: String): HostKey {
        handshakeCalls.offer(HandshakeCall(host, port, username))
        handshaked = true
        return HostKey(presentedHostKey, presentedHostKeyAlgorithm)
    }

    override fun authenticate(user: String, password: String, key: SshKey?) {
        check(handshaked) { "authenticate before handshake" }
        authenticateCalls.offer(AuthenticateCall(user, password, key))
        authenticated = true
    }

    override fun openShell(cols: Int, rows: Int, widthPx: Int, heightPx: Int, term: String) {
        check(authenticated) { "openShell before authenticate" }
        openShellCalls.offer(OpenShellCall(cols, rows, widthPx, heightPx, term))
        shellOpen = true
        // A freshly opened shell is alive and not at EOF again — models a
        // successful reconnect after a half-open drop or channel EOF.
        keepAliveAlive = true
        eof = false
    }

    override fun openDirectTcpip(host: String, port: Int, sourceHost: String, sourcePort: Int) {
        check(authenticated) { "openDirectTcpip before authenticate" }
        shellOpen = true
        keepAliveAlive = true
        eof = false
    }

    override fun resize(cols: Int, rows: Int, widthPx: Int, heightPx: Int) {
        // No-op for the fake; tests inspect Engine's bridge resize through
        // libghostty snapshot dimensions instead.
    }

    override fun read(maxBytes: Int): ByteArray? {
        if (!shellOpen) return null
        // Non-blocking poll: empty queue → empty array (engine ticks again).
        // EOF → null (engine exits loop).
        val next = readQueue.poll()
        if (next != null) {
            return if (next.size > maxBytes) next.copyOf(maxBytes) else next
        }
        if (eof) return null
        return ByteArray(0)
    }

    override fun write(data: ByteArray) {
        if (writesFail) error("fake transport: simulated broken pipe on write")
        synchronized(writeLock) { writes += data.copyOf() }
    }

    override fun sendKeepAlive(): Boolean {
        keepAliveCalls.incrementAndGet()
        return keepAliveAlive
    }

    override fun close() {
        shellOpen = false
        authenticated = false
        handshaked = false
    }

    companion object {
        // 32 bytes — shaped like an Ed25519 public key so SHA-256
        // fingerprinting downstream produces the same `SHA256:<base64>`
        // form ssh-keygen -lf would print. Content is deterministic so
        // tests can pre-trust the matching fp.
        private val DEFAULT_HOST_KEY: ByteArray = ByteArray(32) { i -> i.toByte() }
    }
}
