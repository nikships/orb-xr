package com.ghosttyxr.app.terminal.ssh

import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Single, platform-free source of truth for turning an SSH host key into
 * the `SHA256:<base64>` fingerprint string OpenSSH and the Mac companion
 * print. [HostTrustPolicy] — the sole production caller, via
 * [VerifiedHostAccess] — calls through here rather than keeping its own
 * copy.
 *
 * Uses `kotlin.io.encoding.Base64` (not `java.util.Base64` or
 * `android.util.Base64`) to remain compatible with Android API 24 (minSdk)
 * while staying free of an Android dependency for plain JVM unit tests.
 * `java.util.Base64` requires API 26+, making it unsafe for this project.
 */
internal object HostKeyFingerprint {
    private const val ED25519_KEY_SIZE = 32

    /**
     * Compute the SHA-256 fingerprint of a raw key blob, in the standard
     * `SHA256:<base64>` form OpenSSH and the Mac companion print. The
     * base64 is unpadded — match `ssh-keygen -lf` output exactly so users
     * can eyeball-compare against `Remote Login` host key prints.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        // Use Kotlin stdlib Base64 (RFC 4648) with padding disabled to match
        // the unpadded SHA-256 fingerprints used by OpenSSH.
        val encoded = Base64.Default.withPadding(Base64.PaddingOption.ABSENT).encode(digest)
        return "SHA256:$encoded"
    }

    /**
     * Compute a fingerprint that matches `ssh-keygen -lf`.
     *
     * libssh2 returns Ed25519 host keys as the raw 32-byte public key, while
     * OpenSSH fingerprints the SSH public-key blob: string("ssh-ed25519") +
     * string(rawKey). QR pairing uses the OpenSSH form from the Mac
     * companion, so normalize here.
     */
    fun sha256(hostKey: HostKey): String {
        val isEd25519 = hostKey.algorithm.equals("ssh-ed25519", ignoreCase = true) ||
            hostKey.algorithm.equals("ED25519", ignoreCase = true)
        val isRawEd25519 = hostKey.bytes.size == ED25519_KEY_SIZE && isEd25519
        val bytes = if (isRawEd25519) openSshBlob("ssh-ed25519", hostKey.bytes) else hostKey.bytes
        return sha256(bytes)
    }

    private fun openSshBlob(algorithm: String, keyBytes: ByteArray): ByteArray {
        val algorithmBytes = algorithm.toByteArray(Charsets.UTF_8)
        return ByteBuffer
            .allocate(4 + algorithmBytes.size + 4 + keyBytes.size)
            .putInt(algorithmBytes.size)
            .put(algorithmBytes)
            .putInt(keyBytes.size)
            .put(keyBytes)
            .array()
    }
}
