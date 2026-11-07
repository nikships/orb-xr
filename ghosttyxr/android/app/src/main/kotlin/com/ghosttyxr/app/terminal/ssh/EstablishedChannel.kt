package com.ghosttyxr.app.terminal.ssh

import java.io.Closeable

/**
 * Narrow, already-authenticated view of an SSH channel opened by
 * [VerifiedHostAccess]. Deliberately smaller than [SshTransport]: no
 * [SshTransport.handshake]/[SshTransport.authenticate]/[SshTransport.openShell]
 * — those steps already ran (and were verified) before a consumer ever sees
 * one of these. A caller holding an [EstablishedChannel] cannot re-drive the
 * handshake/auth state machine, only push bytes through the channel it
 * already owns.
 *
 * [close] must be safe to call more than once — [VerifiedHostAccess]'s
 * implementations guard repeat calls so a consumer's own cleanup path (e.g.
 * a `finally` block racing a reconnect) can never double-tear-down the
 * underlying [SshTransport].
 */
interface EstablishedChannel : Closeable {
    /**
     * The `SHA256:...` fingerprint [VerifiedHostAccess] verified for this
     * connection's host key before authenticating — the same value
     * [HostTrustPolicy.verify] returned. Exposed so a caller that needs to
     * persist the verified identity (e.g. [MacProvisioner] writing a
     * [PairedMac]) doesn't have to re-derive it or see the raw [HostKey].
     */
    val verifiedFingerprint: String

    /**
     * Pull up to [maxBytes] from the channel. Returns:
     *   - non-empty ByteArray when bytes are available
     *   - empty ByteArray when the channel is open but idle (caller polls)
     *   - null when the channel has closed (EOF — caller stops the loop)
     */
    fun read(maxBytes: Int = 65_536): ByteArray?

    /** Write [data] to the channel. Blocks until all bytes are flushed. */
    fun write(data: ByteArray)

    override fun close()
}

/**
 * An [EstablishedChannel] backed by a PTY shell — the interactive channel
 * [com.ghosttyxr.app.terminal.session.TerminalSessionEngine] drives libghostty through.
 */
interface EstablishedShell : EstablishedChannel {
    fun resize(cols: Int, rows: Int, widthPx: Int, heightPx: Int)

    /**
     * Liveness probe — see [SshTransport.sendKeepAlive]. Default no-op
     * mirrors the transport-level default for channels that don't need it.
     */
    fun sendKeepAlive(): Boolean = true
}

/**
 * An [EstablishedChannel] backed by a `direct-tcpip` forward — used by the
 * Mac host-keyboard relay and window-mirror signaling to reach a
 * loopback-only listener through the authenticated SSH session without a
 * PTY or an extra LAN port.
 */
interface EstablishedTunnel : EstablishedChannel
