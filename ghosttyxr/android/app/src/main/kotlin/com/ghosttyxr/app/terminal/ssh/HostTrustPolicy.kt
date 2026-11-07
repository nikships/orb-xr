package com.ghosttyxr.app.terminal.ssh

/**
 * Platform-free host-key trust policy: a single out-of-band pin check
 * (QR `fp=` on first pair, persisted [PairedMac.hostFingerprint] on every
 * auto-connect) plus the platform-free base64 fingerprint (see
 * [HostKeyFingerprint]) so the trust decision itself can be exercised in a
 * plain JVM unit test.
 *
 * [VerifiedHostAccess] is the only production caller — it runs this
 * **after** [SshTransport.handshake] and **before**
 * [SshTransport.authenticate].
 *
 * Policy:
 *  - **Out-of-band fingerprint** ([expectedFingerprint], from a QR `fp=` or
 *    a persisted [PairedMac]). If present and it disagrees with the
 *    presented key, abort — strongest signal of an active MITM. This runs
 *    before any credential is sent, so a rejected host never sees a
 *    password or key.
 *  - **No expected fingerprint** means first contact with no out-of-band
 *    proof (debug/emulator flows, the instrumented localhost round-trip).
 *    Accept the presented key and return its fingerprint so the caller can
 *    persist it — the next connection pins to that value. This is
 *    deliberately not a second trust root: nothing is stored here, and a
 *    rotated key on a pinned connection aborts. If the Mac's host keys
 *    rotate, the user re-scans the QR.
 */
object HostTrustPolicy {
    /**
     * Verify [hostKey] against [expectedFingerprint] (if any).
     * Throws — with a message describing the mismatch — on any failure.
     * Returns the presented `SHA256:...` fingerprint on success so a caller
     * (e.g. [VerifiedHostAccess]) can attach it to the resulting channel
     * without recomputing it or re-touching [hostKey].
     */
    fun verify(
        expectedFingerprint: String?,
        hostKey: HostKey,
    ): String {
        val presentedFp = fingerprintSha256(hostKey)

        if (expectedFingerprint != null && expectedFingerprint != presentedFp) {
            error("host key $presentedFp does not match expected $expectedFingerprint")
        }
        return presentedFp
    }

    /**
     * Compute the SHA-256 fingerprint of a raw key blob, in the standard
     * `SHA256:<base64>` form OpenSSH and the Mac companion print. The
     * base64 is unpadded — match `ssh-keygen -lf` output exactly.
     *
     * Delegates to [HostKeyFingerprint] — the single source of truth for
     * this algorithm.
     */
    fun fingerprintSha256(bytes: ByteArray): String = HostKeyFingerprint.sha256(bytes)

    /**
     * Compute a fingerprint that matches `ssh-keygen -lf`.
     *
     * libssh2 returns Ed25519 host keys as the raw 32-byte public key, while
     * OpenSSH fingerprints the SSH public-key blob: string("ssh-ed25519") +
     * string(rawKey). QR pairing uses the OpenSSH form from the Mac
     * companion, so normalize here.
     *
     * Delegates to [HostKeyFingerprint] — see [fingerprintSha256] above.
     */
    fun fingerprintSha256(hostKey: HostKey): String = HostKeyFingerprint.sha256(hostKey)
}
