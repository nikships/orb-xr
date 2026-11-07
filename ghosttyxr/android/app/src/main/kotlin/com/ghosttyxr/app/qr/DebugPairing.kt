package com.ghosttyxr.app.qr

import android.content.Intent
import android.util.Log

/**
 * Debug-only pairing path for the emulator, where there's no camera to scan the
 * Mac-companion QR. Lets you inject the exact same [PairingPayload] the QR would
 * decode to via `adb`, e.g.:
 *
 * ```
 * # Pass the whole ssh:// URL the Mac companion shows under its QR:
 * adb shell am start -n com.ghosttyxr.app/.MainActivity \
 *   -e ghostty_pair 'ssh://me@10.0.2.2:22?token=abc123&fp=SHA256:...'
 *
 * # Or just the pieces (host defaults to the emulator's host-Mac alias 10.0.2.2):
 * adb shell am start -n com.ghosttyxr.app/.MainActivity \
 *   -e ghostty_user me -e ghostty_token abc123
 * ```
 *
 * Wired only behind `BuildConfig.DEBUG` from [com.ghosttyxr.app.MainActivity], so
 * it's compiled out of release builds — release devices pair via the QR scanner.
 */
object DebugPairing {
    private const val TAG = "DebugPairing"

    /** Full `ssh://user@host:port?token=…&fp=…` URL — same format the QR carries. */
    const val EXTRA_URL = "ghostty_pair"

    // Piecewise overrides — convenient when you don't want to hand-assemble a URL.
    const val EXTRA_HOST = "ghostty_host"
    const val EXTRA_PORT = "ghostty_port"
    const val EXTRA_USER = "ghostty_user"
    const val EXTRA_TOKEN = "ghostty_token"
    const val EXTRA_FINGERPRINT = "ghostty_fp"

    /**
     * The emulator reaches the developer machine (where the Mac companion / sshd
     * runs) at this loopback alias. Used as the host default so a bare
     * `-e ghostty_user … -e ghostty_token …` launch connects straight to the Mac.
     */
    const val EMULATOR_HOST_ALIAS = "10.0.2.2"
    private const val DEFAULT_PORT = 22

    /**
     * Build a [PairingPayload] from debug intent extras, or `null` if the intent
     * carries no pairing hints. Returns `null` (rather than throwing) on a
     * malformed payload so a normal launch falls through to the QR scanner.
     */
    fun fromIntent(intent: Intent?): PairingPayload? {
        if (intent == null) return null

        intent.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() }?.let { url ->
            return PairingPayload.parse(url)
                .onFailure { Log.w(TAG, "ignoring malformed $EXTRA_URL extra '$url'", it) }
                .getOrNull()
        }

        val user = intent.getStringExtra(EXTRA_USER)?.takeIf { it.isNotBlank() }
        val token = intent.getStringExtra(EXTRA_TOKEN)?.takeIf { it.isNotBlank() }
        // Need at least a user + token to attempt a connection; otherwise there's
        // nothing to inject and we defer to the QR scanner.
        if (user == null || token == null) return null

        val host = intent.getStringExtra(EXTRA_HOST)?.takeIf { it.isNotBlank() }
            ?: EMULATOR_HOST_ALIAS
        val port = intent.getStringExtra(EXTRA_PORT)?.toIntOrNull() ?: DEFAULT_PORT
        val fingerprint = intent.getStringExtra(EXTRA_FINGERPRINT)?.takeIf { it.isNotBlank() }

        Log.d(TAG, "injecting debug pairing for $user@$host:$port")
        return PairingPayload(
            host = host,
            port = port,
            user = user,
            token = token,
            fingerprint = fingerprint,
        )
    }
}
