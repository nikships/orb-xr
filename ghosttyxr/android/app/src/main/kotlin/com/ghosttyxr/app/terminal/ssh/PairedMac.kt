package com.ghosttyxr.app.terminal.ssh

import com.ghosttyxr.app.qr.PairingPayload

/**
 * A Mac the headset has paired with and provisioned for key-based login.
 *
 * Persisted by [PairedMacStore] alongside the headset's [SshKey] so a later
 * launch can auto-connect without re-scanning the QR. The [hostFingerprint]
 * is the SHA-256 fp verified during the original pairing handshake — it's
 * re-asserted on every auto-connect (see [toPayload]) so a rotated/forged
 * host key still aborts before any channel opens.
 *
 * @param provisionedAtEpochMs when the headset pushed its public key into the
 *   Mac's `~/.ssh/authorized_keys`. Diagnostic only.
 */
data class PairedMac(
    val host: String,
    val port: Int,
    val user: String,
    val hostFingerprint: String,
    val provisionedAtEpochMs: Long,
) {
    /**
     * Build a [PairingPayload] for an auto-reconnect. The token is empty —
     * auto-connect authenticates with the persisted [SshKey], never a
     * password — while the verified [hostFingerprint] is carried through so
     * the engine's host-key check fails closed on a key change.
     */
    fun toPayload(): PairingPayload = PairingPayload(
        host = host,
        port = port,
        user = user,
        token = "",
        fingerprint = hostFingerprint,
    )
}
