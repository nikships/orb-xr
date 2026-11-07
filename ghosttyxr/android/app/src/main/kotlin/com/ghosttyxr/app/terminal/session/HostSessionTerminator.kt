package com.ghosttyxr.app.terminal.session

import com.ghosttyxr.app.qr.PairingPayload
import com.ghosttyxr.app.terminal.ssh.EstablishedShell
import com.ghosttyxr.app.terminal.ssh.SshKey
import com.ghosttyxr.app.terminal.ssh.VerifiedHostAccess
import com.ghosttyxr.app.terminal.ssh.withShell

class HostSessionTerminator(
    private val hostAccess: VerifiedHostAccess,
) {
    fun terminate(
        payload: PairingPayload,
        key: SshKey?,
        snapshots: List<SessionSnapshot>,
    ): Boolean {
        val sessionNames = snapshots.flatMap { snapshot ->
            snapshot.panes.map { HostSessionBootstrap.sessionName(snapshot.id, it.paneKey) }
        }.distinct()
        if (sessionNames.isEmpty()) return true
        return runCommand(payload, key, command(sessionNames))
    }

    fun terminateAll(payload: PairingPayload, key: SshKey?): Boolean =
        runCommand(payload, key, allCommand())

    private fun runCommand(payload: PairingPayload, key: SshKey?, command: String): Boolean {
        return runCatching {
            hostAccess.withShell(
                host = payload.host,
                port = payload.port,
                username = payload.user,
                expectedFingerprint = payload.fingerprint,
                password = payload.token,
                key = key,
            ) { shell ->
                awaitShellReady(shell)
                shell.write(command.encodeToByteArray())
                awaitCompletion(shell)
            }
        }.getOrDefault(false)
    }

    private fun awaitShellReady(shell: EstablishedShell) {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        var sawOutput = false
        var lastOutputAt = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            val chunk = shell.read() ?: return
            if (chunk.isNotEmpty()) {
                sawOutput = true
                lastOutputAt = System.currentTimeMillis()
            } else if (sawOutput && System.currentTimeMillis() - lastOutputAt >= QUIET_WINDOW_MS) {
                return
            } else {
                Thread.sleep(POLL_MS)
            }
        }
    }

    private fun awaitCompletion(shell: EstablishedShell): Boolean {
        val deadline = System.currentTimeMillis() + COMMAND_TIMEOUT_MS
        val output = StringBuilder()
        while (System.currentTimeMillis() < deadline) {
            val chunk = shell.read() ?: return false
            if (chunk.isNotEmpty()) {
                output.append(chunk.toString(Charsets.UTF_8))
                if (output.contains(DONE_MARKER)) return true
            } else {
                Thread.sleep(POLL_MS)
            }
        }
        return false
    }

    companion object {
        internal const val DONE_MARKER = "__GXR_TERMINATED__"
        private val SESSION_NAME = Regex("[A-Za-z0-9_-]{1,64}")
        private const val READY_TIMEOUT_MS = 2_500L
        private const val COMMAND_TIMEOUT_MS = 5_000L
        private const val QUIET_WINDOW_MS = 250L
        private const val POLL_MS = 16L

        internal fun command(sessionNames: List<String>): String {
            require(sessionNames.isNotEmpty())
            require(sessionNames.all { it.matches(SESSION_NAME) })
            val kills = sessionNames.joinToString("; ") {
                "${HostSessionBootstrap.tmux()} kill-session -t $it 2>/dev/null || true"
            }
            return "stty -echo 2>/dev/null; $kills; " +
                "printf '__GXR_%s\\n' 'TERMINATED__'; stty echo 2>/dev/null\r"
        }

        internal fun allCommand(): String =
            "stty -echo 2>/dev/null; ${HostSessionBootstrap.tmux()} kill-server 2>/dev/null || true; " +
                "printf '__GXR_%s\\n' 'TERMINATED__'; stty echo 2>/dev/null\r"
    }
}
