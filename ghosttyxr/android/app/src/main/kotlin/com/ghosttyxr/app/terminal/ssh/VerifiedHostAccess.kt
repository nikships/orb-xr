package com.ghosttyxr.app.terminal.ssh

import com.ghosttyxr.app.util.GxrLog
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "VerifiedHostAccess"

/**
 * Owns the full handshake → verify → authenticate → channel-open sequence
 * for a single SSH connection and hands the caller back only a narrow
 * [EstablishedShell] or [EstablishedTunnel] — never the underlying
 * [SshTransport]. A consumer can drive [EstablishedChannel.read] /
 * [EstablishedChannel.write] / [EstablishedChannel.close], but can never see
 * or re-run [SshTransport.handshake] / [SshTransport.authenticate] /
 * [SshTransport.openShell] once a channel exists.
 *
 * Sequencing per connect call:
 *  1. [transportFactory] builds a fresh [SshTransport] — this class owns its
 *     creation, the caller never constructs one.
 *  2. [SshTransport.handshake] — protocol-level handshake only, no auth yet.
 *  3. [HostTrustPolicy.verify] — QR/persisted fingerprint pin check.
 *     Throws before any credential is sent, so a rejected host never sees
 *     a password or key.
 *  4. [SshTransport.authenticate] — key first (when [key] is non-null),
 *     password/token fallback, exactly [SshTransport.authenticate]'s own
 *     ladder.
 *  5. [SshTransport.openShell] / [SshTransport.openDirectTcpip] — the
 *     channel the caller asked for.
 *
 * On success the returned channel *owns* the transport for the rest of its
 * lifetime — [EstablishedChannel.close] is the only way to tear it down, and
 * is idempotent regardless of how many times a consumer calls it or how
 * many overlapping cleanup paths race to call it.
 *
 * On failure at any step 2-5, the transport this call created is closed
 * before the exception propagates — no half-open native session leaks past
 * a failed [connectShell] / [connectTunnel] call. A [SshTransport.close]
 * failure during that cleanup is logged (with the original throwable
 * attached, for a full stack trace in logcat) and swallowed so it never
 * masks the real failure that triggered cleanup.
 *
 * The [EstablishedShell] / [EstablishedTunnel] returned on success serialize
 * every operation — [EstablishedChannel.read], [EstablishedChannel.write],
 * [EstablishedShell.resize], [EstablishedShell.sendKeepAlive], and
 * [EstablishedChannel.close] itself — through a single lock per channel.
 * [SshTransport] implementations (see [LibSsh2Transport]) mutate a bare
 * session handle with no internal synchronization, so without this a
 * `close()` racing an in-flight `read()`/`write()` from another thread could
 * tear down the native session out from under it. The lock only serializes;
 * it does not make `close()` interrupt a blocked read/write early.
 */
class VerifiedHostAccess(
    private val transportFactory: () -> SshTransport = { LibSsh2Transport() },
) {
    /**
     * Whether the underlying transport mechanism is loadable at all (e.g.
     * the native `.so` on device). Builds and immediately discards a
     * throwaway transport — cheap, since nothing has connected yet.
     */
    fun isAvailable(): Boolean {
        val transport = transportFactory()
        return try {
            transport.isAvailable()
        } finally {
            closeQuietly(transport, "isAvailable probe")
        }
    }

    /**
     * Establish a verified, authenticated PTY shell channel to [host]:[port].
     *
     * @param key tried first when non-null; [password] (or a QR token) is
     *   the fallback, matching [SshTransport.authenticate]'s own ladder.
     * @param expectedFingerprint the QR/persisted out-of-band fingerprint,
     *   or null to accept the presented key on first contact (debug flows).
     */
    fun connectShell(
        host: String,
        port: Int,
        username: String,
        expectedFingerprint: String?,
        password: String,
        key: SshKey? = null,
        cols: Int,
        rows: Int,
        widthPx: Int,
        heightPx: Int,
        term: String = "xterm-256color",
    ): EstablishedShell = establish(
        host = host,
        port = port,
        username = username,
        expectedFingerprint = expectedFingerprint,
        password = password,
        key = key,
        openChannel = { it.openShell(cols, rows, widthPx, heightPx, term) },
        wrap = { transport, fingerprint -> ShellChannel(transport, fingerprint) },
    )

    /**
     * Establish a verified, authenticated `direct-tcpip` tunnel to
     * [targetHost]:[targetPort] through [host]:[port]. Same handshake/
     * verify/authenticate sequence as [connectShell]; only the final
     * channel-open step differs.
     */
    fun connectTunnel(
        host: String,
        port: Int,
        username: String,
        expectedFingerprint: String?,
        password: String,
        key: SshKey? = null,
        targetHost: String,
        targetPort: Int,
        sourceHost: String = "127.0.0.1",
        sourcePort: Int = 0,
    ): EstablishedTunnel = establish(
        host = host,
        port = port,
        username = username,
        expectedFingerprint = expectedFingerprint,
        password = password,
        key = key,
        openChannel = { it.openDirectTcpip(targetHost, targetPort, sourceHost, sourcePort) },
        wrap = { transport, fingerprint -> TunnelChannel(transport, fingerprint) },
    )

    private fun <T : EstablishedChannel> establish(
        host: String,
        port: Int,
        username: String,
        expectedFingerprint: String?,
        password: String,
        key: SshKey?,
        openChannel: (SshTransport) -> Unit,
        wrap: (SshTransport, String) -> T,
    ): T {
        val transport = transportFactory()
        var established = false
        try {
            val hostKey = transport.handshake(host, port, username)
            val verifiedFingerprint = HostTrustPolicy.verify(
                expectedFingerprint = expectedFingerprint,
                hostKey = hostKey,
            )
            transport.authenticate(username, password, key)
            openChannel(transport)
            val channel = wrap(transport, verifiedFingerprint)
            established = true
            return channel
        } finally {
            if (!established) closeQuietly(transport, "failed connect")
        }
    }

    private fun closeQuietly(transport: SshTransport, context: String) {
        runCatching { transport.close() }
            .onFailure { GxrLog.w(TAG, "transport.close after $context failed", it) }
    }

    /**
     * [EstablishedShell] that owns [transport] for its lifetime.
     *
     * Every operation — including [close] — is serialized through [lock] so
     * a close on one thread can never run concurrently with a read/write/
     * resize/keepalive on another and tear down [transport] mid-operation.
     * [closed] still gates the actual [SshTransport.close] call so repeat
     * closes (even fully serialized, one after another) only tear down the
     * native session once.
     */
    private class ShellChannel(
        private val transport: SshTransport,
        override val verifiedFingerprint: String,
    ) : EstablishedShell {
        private val lock = Any()
        private val closed = AtomicBoolean(false)

        override fun read(maxBytes: Int): ByteArray? = synchronized(lock) { transport.read(maxBytes) }
        override fun write(data: ByteArray) = synchronized(lock) { transport.write(data) }
        override fun resize(cols: Int, rows: Int, widthPx: Int, heightPx: Int) = synchronized(lock) {
            transport.resize(cols, rows, widthPx, heightPx)
        }
        override fun sendKeepAlive(): Boolean = synchronized(lock) { transport.sendKeepAlive() }

        override fun close() {
            synchronized(lock) {
                if (closed.compareAndSet(false, true)) transport.close()
            }
        }
    }

    /**
     * [EstablishedTunnel] that owns [transport] for its lifetime. Same
     * single-lock serialization as [ShellChannel] — see its doc.
     */
    private class TunnelChannel(
        private val transport: SshTransport,
        override val verifiedFingerprint: String,
    ) : EstablishedTunnel {
        private val lock = Any()
        private val closed = AtomicBoolean(false)

        override fun read(maxBytes: Int): ByteArray? = synchronized(lock) { transport.read(maxBytes) }
        override fun write(data: ByteArray) = synchronized(lock) { transport.write(data) }

        override fun close() {
            synchronized(lock) {
                if (closed.compareAndSet(false, true)) transport.close()
            }
        }
    }
}

/**
 * Open a verified shell via [VerifiedHostAccess.connectShell], run [block]
 * with it, and always close it afterward — even if [block] throws. Close
 * failures are swallowed via [onCloseFailure] (default: silently ignored,
 * matching every short-lived shell consumer's original try/finally shape)
 * so they never mask [block]'s own result or exception.
 *
 * Factors out the identical "open a shell, run one command, always close"
 * pattern shared by [MacProvisioner],
 * [com.ghosttyxr.app.terminal.session.HostSessionTerminator],
 * [com.ghosttyxr.app.terminal.session.AgentSessionProbe], and
 * [com.ghosttyxr.app.terminal.session.PaneCwdProbe] — none of their command
 * bytes, marker parsing, read timeouts, or PTY sizing changes; only the
 * open/close ceremony around them is now written once.
 */
inline fun <T> VerifiedHostAccess.withShell(
    host: String,
    port: Int,
    username: String,
    expectedFingerprint: String?,
    password: String,
    key: SshKey? = null,
    cols: Int = 80,
    rows: Int = 24,
    widthPx: Int = 80,
    heightPx: Int = 24,
    onCloseFailure: (Throwable) -> Unit = {},
    block: (EstablishedShell) -> T,
): T {
    val shell = connectShell(
        host = host,
        port = port,
        username = username,
        expectedFingerprint = expectedFingerprint,
        password = password,
        key = key,
        cols = cols,
        rows = rows,
        widthPx = widthPx,
        heightPx = heightPx,
    )
    try {
        return block(shell)
    } finally {
        runCatching { shell.close() }.onFailure(onCloseFailure)
    }
}
