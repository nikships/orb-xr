package com.ghosttyxr.app.terminal.ssh

/**
 * Pure-JVM [SshTransport] fake for [VerifiedHostAccessTest]. Unlike the
 * `androidTest` `FakeSshTransport` (session-engine focused, only write/
 * keepalive failure injection), every lifecycle step here can be forced to
 * throw so tests can exercise each of [VerifiedHostAccess]'s partial-failure
 * cleanup boundaries without a native `.so` or an emulator.
 *
 * Every call is recorded before any configured failure is thrown, so a test
 * asserting "step N was attempted with these arguments, then step N+1 never
 * ran" can inspect the call even on the failing step.
 *
 * [onRead] is a test-only hook invoked from inside [read] before it returns
 * — a concurrency test can block there (e.g. on a [java.util.concurrent.CountDownLatch])
 * to hold the channel's lock open on one thread while asserting that
 * [close] on another thread doesn't reach [SshTransport.close] until the
 * read finishes.
 */
class FakeSshTransport(
    private val available: Boolean = true,
    private val hostKey: HostKey = DEFAULT_HOST_KEY,
    private val handshakeFailure: Throwable? = null,
    private val authenticateFailure: Throwable? = null,
    private val openShellFailure: Throwable? = null,
    private val openDirectTcpipFailure: Throwable? = null,
    private val closeFailure: Throwable? = null,
    private val onRead: (() -> Unit)? = null,
) : SshTransport {

    data class HandshakeCall(val host: String, val port: Int, val username: String)
    data class AuthenticateCall(val user: String, val password: String, val key: SshKey?)
    data class OpenShellCall(val cols: Int, val rows: Int, val widthPx: Int, val heightPx: Int, val term: String)
    data class OpenDirectTcpipCall(val host: String, val port: Int, val sourceHost: String, val sourcePort: Int)

    var handshakeCall: HandshakeCall? = null
        private set
    var authenticateCall: AuthenticateCall? = null
        private set
    var openShellCall: OpenShellCall? = null
        private set
    var openDirectTcpipCall: OpenDirectTcpipCall? = null
        private set
    var closeCalls: Int = 0
        private set
    var readCalls: Int = 0
        private set
    var writtenBytes: MutableList<ByteArray> = mutableListOf()
        private set
    var resizeCalls: Int = 0
        private set
    var keepAliveCalls: Int = 0
        private set

    override fun isAvailable(): Boolean = available

    override fun handshake(host: String, port: Int, username: String): HostKey {
        handshakeCall = HandshakeCall(host, port, username)
        handshakeFailure?.let { throw it }
        return hostKey
    }

    override fun authenticate(user: String, password: String, key: SshKey?) {
        authenticateCall = AuthenticateCall(user, password, key)
        authenticateFailure?.let { throw it }
    }

    override fun openShell(cols: Int, rows: Int, widthPx: Int, heightPx: Int, term: String) {
        openShellCall = OpenShellCall(cols, rows, widthPx, heightPx, term)
        openShellFailure?.let { throw it }
    }

    override fun openDirectTcpip(host: String, port: Int, sourceHost: String, sourcePort: Int) {
        openDirectTcpipCall = OpenDirectTcpipCall(host, port, sourceHost, sourcePort)
        openDirectTcpipFailure?.let { throw it }
    }

    override fun resize(cols: Int, rows: Int, widthPx: Int, heightPx: Int) {
        resizeCalls += 1
    }

    override fun read(maxBytes: Int): ByteArray? {
        readCalls += 1
        onRead?.invoke()
        return ByteArray(0)
    }

    override fun write(data: ByteArray) {
        writtenBytes += data
    }

    override fun sendKeepAlive(): Boolean {
        keepAliveCalls += 1
        return true
    }

    override fun close() {
        closeCalls += 1
        closeFailure?.let { throw it }
    }

    companion object {
        // 32 bytes — shaped like an Ed25519 public key so SHA-256
        // fingerprinting downstream produces a stable `SHA256:<base64>` form.
        private val DEFAULT_HOST_KEY = HostKey(ByteArray(32) { it.toByte() }, "ssh-ed25519")
    }
}
