package com.ghosttyxr.app.terminal.ssh

import android.util.Log
import com.ghosttyxr.app.qr.PairingPayload

private const val TAG = "MacProvisioner"

/**
 * One-shot first-pair provisioning: turns a QR token pairing into a durable,
 * passwordless trust relationship so the headset never has to scan again.
 *
 * Steps, over a throwaway SSH session authenticated with the QR token:
 *  1. load-or-generate the headset's persistent RSA keypair,
 *  2. handshake + verify the host key (same [VerifiedHostAccess] seam the engine uses),
 *  3. authenticate with the one-time token,
 *  4. append the headset's public key to the Mac's `~/.ssh/authorized_keys`
 *     (idempotent; never truncates — see [buildAuthorizedKeyCommand]),
 *  5. return the verified host fingerprint + key for [PairedMacStore].
 *
 * The append runs as a shell command rather than SFTP because the native SFTP
 * write path opens with `LIBSSH2_FXF_TRUNC` (see `ghosttyxr_ssh.zig`), which
 * would clobber any keys the user already has in `authorized_keys`.
 */
class MacProvisioner(
    private val hostAccess: VerifiedHostAccess,
    private val keyGenerator: RsaKeyGenerator = RsaKeyGenerator(),
    private val existingKey: () -> SshKey? = { PairedMacStore.loadKey() },
) {
    data class Provisioned(val mac: PairedMac, val key: SshKey)

    /**
     * Provision [payload]'s Mac for key auth. Returns the [PairedMac] +
     * [SshKey] to persist on success, or a failure carrying a user-facing
     * message. Never throws — the QR-scan path turns the result into either a
     * persisted auto-reconnect or a degraded token-only connection.
     */
    fun provision(payload: PairingPayload): Result<Provisioned> = runCatching {
        val key = existingKey() ?: keyGenerator.generate(name = "ghosttyxr-headset")

        // Token-as-password; pass the key too so a headset that re-pairs
        // (key already in authorized_keys) authenticates straight away.
        hostAccess.withShell(
            host = payload.host,
            port = payload.port,
            username = payload.user,
            expectedFingerprint = payload.fingerprint,
            password = payload.token,
            key = key,
            onCloseFailure = { Log.w(TAG, "shell.close after provisioning failed: ${it.message}") },
        ) { shell ->
            pushPublicKey(shell, key.publicKeyOpenSsh)

            Provisioned(
                mac = PairedMac(
                    host = payload.host,
                    port = payload.port,
                    user = payload.user,
                    hostFingerprint = shell.verifiedFingerprint,
                    provisionedAtEpochMs = System.currentTimeMillis(),
                ),
                key = key,
            )
        }
    }.onFailure { Log.w(TAG, "provisioning failed: ${it.message}") }

    /**
     * Send the append command and read the channel until the sentinel line,
     * then assert the reported exit status is 0. Throws on a non-zero status
     * or if the channel closes before the sentinel arrives.
     */
    private fun pushPublicKey(shell: EstablishedShell, publicKeyOpenSsh: String) {
        shell.write(buildAuthorizedKeyCommand(publicKeyOpenSsh).toByteArray(Charsets.UTF_8))

        val output = StringBuilder()
        var status: Int? = null
        val deadline = System.currentTimeMillis() + PUSH_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val chunk = shell.read() ?: break
            if (chunk.isNotEmpty()) {
                output.append(String(chunk, Charsets.UTF_8))
                // Break on a parsed exit status, NOT on merely seeing the
                // sentinel string: a PTY echoes the command (which contains
                // the sentinel literal `__GXR_PROVISION_DONE__:$?`) before it
                // runs, so an early `contains` check would stop reading before
                // the real `<sentinel>:<exit-code>` result line arrives.
                status = parseSentinelStatus(output.toString())
                if (status != null) break
            } else {
                Thread.sleep(IDLE_POLL_MS)
            }
        }
        val exit = status
            ?: error("authorized_keys update did not confirm (no sentinel in shell output)")
        if (exit != 0) error("authorized_keys update failed on the Mac (exit $exit)")
    }

    companion object {
        internal const val SENTINEL = "__GXR_PROVISION_DONE__"
        private const val PUSH_TIMEOUT_MS = 15_000L
        private const val IDLE_POLL_MS = 16L

        /**
         * Build the idempotent shell command that appends [publicKeyOpenSsh]
         * to `~/.ssh/authorized_keys`:
         *  - `mkdir -p` + `chmod 700` the `.ssh` dir,
         *  - `grep -qF ... || printf ... >>` so a re-pair doesn't duplicate,
         *  - `chmod 600` the file (sshd ignores group/other-writable files),
         *  - echo the sentinel with `$?` so the caller can read the result.
         *
         * The key is wrapped in single quotes with any embedded single quote
         * escaped (`'\''`) — OpenSSH public keys are base64 + a comment, so in
         * practice there are none, but we escape defensively.
         */
        internal fun buildAuthorizedKeyCommand(publicKeyOpenSsh: String): String {
            val key = publicKeyOpenSsh.trim()
            val quoted = "'" + key.replace("'", "'\\''") + "'"
            return buildString {
                append("mkdir -p ~/.ssh && chmod 700 ~/.ssh && touch ~/.ssh/authorized_keys && ")
                append("{ grep -qF $quoted ~/.ssh/authorized_keys || printf '%s\\n' $quoted >> ~/.ssh/authorized_keys; } && ")
                append("chmod 600 ~/.ssh/authorized_keys; ")
                append("echo $SENTINEL:\$?\n")
            }
        }

        /**
         * Pull the exit code out of a `__GXR_PROVISION_DONE__:<n>` line in the
         * shell output, or null if no sentinel is present. Tolerates the
         * command itself being echoed back by the PTY (takes the last match).
         */
        internal fun parseSentinelStatus(output: String): Int? {
            val marker = "$SENTINEL:"
            var search = output.lastIndexOf(marker)
            while (search >= 0) {
                val start = search + marker.length
                val digits = output.drop(start).takeWhile { it.isDigit() }
                if (digits.isNotEmpty()) return digits.toInt()
                search = output.lastIndexOf(marker, search - 1)
            }
            return null
        }
    }
}
