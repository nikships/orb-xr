package com.ghosttyxr.app.terminal.ssh

import java.io.Closeable

/**
 * Server-presented SSH host key. Carries both the raw wire bytes (for
 * fingerprint hashing) and the algorithm string (for the
 * `known_hosts`-style `(host, port, alg)` key under which the bytes get
 * stored long-term).
 *
 * Returned from [SshTransport.handshake] *before* the transport runs
 * authentication, so the application can decline a host whose key has
 * rotated (or whose fingerprint doesn't match the QR pairing) without
 * leaking a password to a possible MITM.
 */
data class HostKey(
    val bytes: ByteArray,
    val algorithm: String,
) {
    // Generated equals/hashCode use reference equality on the ByteArray,
    // which is never useful — make them value-equal so test assertions
    // and de-duplication via Set/Map work as expected.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HostKey) return false
        return algorithm == other.algorithm && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * algorithm.hashCode() + bytes.contentHashCode()
}

/**
 * Internal wire-level abstraction over an SSH connection to the Mac —
 * implementation detail of [VerifiedHostAccess], never exposed to engine/
 * probe/relay consumers directly. [VerifiedHostAccess] is the only caller
 * that drives the raw handshake/authenticate/open-channel state machine;
 * everyone else only ever sees the [EstablishedShell]/[EstablishedTunnel]
 * it hands back once that sequence has completed.
 *
 * Hides the JNI handle and the libssh2 IPC framing. [VerifiedHostAccess]
 * drives this through a six-step sequence:
 *
 *   1. [handshake] — TCP + SSH protocol handshake. Returns the host key
 *      but does NOT authenticate. [VerifiedHostAccess] must verify the
 *      returned [HostKey] via [HostTrustPolicy.verify] *before* calling
 *      [authenticate] — otherwise a password may leak to a
 *      man-in-the-middle that presents a rotated key.
 *   2. [authenticate] — runs the auth ladder (pubkey then password).
 *   3. [openShell] / [openDirectTcpip] — request the channel the caller asked for.
 *   4. loop [read]/[write] — drives libghostty (shell channels) or a relay
 *      pump (tunnel channels).
 *   5. [resize] — when the user resizes a SpatialPanel (shell channels only).
 *   6. [close] — tear everything down.
 *
 * Why an interface — `NativeSshBridge`'s `external fun`s can't be
 * overridden in a Kotlin subclass, so an interface is the only way to
 * inject a fake. Two impls in this codebase:
 *
 *   - [LibSsh2Transport] (production) wraps `NativeSshBridge` + libssh2.
 *   - `FakeSshTransport` (JVM and `androidTest`) serves canned bytes so
 *     [VerifiedHostAccess] and its consumers can be verified end-to-end
 *     without zig-native's SSH zig exports.
 */
interface SshTransport : Closeable {
    /**
     * Whether the underlying transport is loadable. For [LibSsh2Transport]
     * this checks the .so; for the fake it's always true.
     */
    fun isAvailable(): Boolean

    /**
     * Open the SSH session and complete the protocol-level handshake.
     * Returns the host key the server presented during handshake — the
     * caller MUST verify this before calling [authenticate].
     */
    fun handshake(host: String, port: Int, username: String): HostKey

    /**
     * Authenticate the session previously opened by [handshake]. Tries
     * [key] first when non-null and falls back to [password]. Throws
     * with a descriptive message on rejection; the engine surfaces that
     * in [com.ghosttyxr.app.terminal.session.SessionState.Failed].
     */
    fun authenticate(user: String, password: String, key: SshKey? = null)

    fun openShell(cols: Int, rows: Int, widthPx: Int, heightPx: Int, term: String = "xterm-256color")

    /**
     * Open a direct-tcpip channel through the authenticated SSH session.
     *
     * Used by the Mac host-keyboard relay to reach the companion's loopback-only
     * listener without exposing an additional LAN port. This is mutually
     * exclusive with [openShell] on a transport instance; callers that need both
     * should create a second [SshTransport] and authenticate it separately.
     */
    fun openDirectTcpip(
        host: String,
        port: Int,
        sourceHost: String = "127.0.0.1",
        sourcePort: Int = 0,
    ) {
        error("direct-tcpip channel is not supported by this transport")
    }

    fun resize(cols: Int, rows: Int, widthPx: Int, heightPx: Int)

    /**
     * Pull up to [maxBytes] from the shell channel. Returns:
     *   - non-empty ByteArray when bytes are available
     *   - empty ByteArray when the channel is open but idle (caller polls)
     *   - null when the channel has closed (EOF — caller stops the loop)
     */
    fun read(maxBytes: Int = 65_536): ByteArray?

    /** Write [data] to the shell channel. Blocks until all bytes are flushed. */
    fun write(data: ByteArray)

    /**
     * Liveness probe — the SSH analogue of `ServerAliveInterval`. Sends a
     * keepalive on an otherwise-idle channel and reports whether it still
     * looks alive.
     *
     * The engine calls this after a stretch of idle [read]s to catch a
     * *half-open* connection: the Mac slept, the headset roamed Wi-Fi APs,
     * or a NAT dropped the flow with no FIN/RST, so [read] keeps returning
     * "open but idle" forever while the socket is actually dead. Returning
     * `false` (or throwing) is the engine's signal to tear down and
     * reconnect.
     *
     * Default is a no-op that returns `true`, so transports without a
     * liveness mechanism (and the test fake, unless a test opts in) are
     * unaffected.
     */
    fun sendKeepAlive(): Boolean = true

    override fun close()
}
