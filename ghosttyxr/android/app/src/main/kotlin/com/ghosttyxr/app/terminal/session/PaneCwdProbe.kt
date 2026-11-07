package com.ghosttyxr.app.terminal.session

import android.util.Log
import com.ghosttyxr.app.qr.PairingPayload
import com.ghosttyxr.app.terminal.ssh.EstablishedShell
import com.ghosttyxr.app.terminal.ssh.SshKey
import com.ghosttyxr.app.terminal.ssh.VerifiedHostAccess
import com.ghosttyxr.app.terminal.ssh.withShell
import com.ghosttyxr.app.util.GxrLog

private const val TAG = "PaneCwdProbe"

/**
 * Asks the Mac for a source pane's live working directory over a short-lived
 * SSH shell, via `tmux display-message '#{pane_current_path}'` on the gxr
 * server. tmux is the only authoritative source here: common shell/agent
 * setups never emit OSC 7 inside tmux, so the engine-side pwd
 * ([TerminalSessionEngine.workingDirectory]) is usually null even when the
 * host pane has a perfectly good cwd (see docs/cwd-routing-handoff.md).
 *
 * Runs out-of-band (its own transport, like [AgentSessionProbe]) so the
 * result is a concrete path the destination pane's bootstrap can embed as a
 * safely quoted `new-session -c` flag — no command substitution inside the
 * typed bootstrap line and no second command injected into the new pane.
 */
class PaneCwdProbe(
    private val hostAccess: VerifiedHostAccess,
) {
    /** Normalized absolute host cwd of [sessionName]'s active pane, or null. */
    fun probe(payload: PairingPayload, key: SshKey?, sessionName: String): String? {
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
                val command = buildCommand(sessionName).encodeToByteArray()
                shell.write(command)
                readResult(shell, command).also { cwd ->
                    GxrLog.d(TAG) { "probe $sessionName -> ${cwd ?: "<none>"}" }
                }
            }
        }.onFailure {
            Log.w(TAG, "pane cwd probe failed: ${it.message}")
        }.getOrNull()
    }

    /**
     * Drain shell-startup chatter (rc files, prompt paint) until the channel
     * has been quiet for [READY_QUIET_WINDOW_MS]. Writing before the remote
     * interactive shell's line editor is up silently loses the line — ZLE
     * initialization flushes type-ahead (confirmed against a real Mac pty).
     * The engine's bootstrap only survives this because submitCommand gates
     * on the same kind of quiescence.
     */
    private fun awaitShellReady(shell: EstablishedShell) {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        var seenOutput = false
        var lastBytesMs = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            val chunk = shell.read() ?: return
            if (chunk.isNotEmpty()) {
                seenOutput = true
                lastBytesMs = System.currentTimeMillis()
                continue
            }
            if (seenOutput && System.currentTimeMillis() - lastBytesMs >= READY_QUIET_WINDOW_MS) return
            Thread.sleep(IDLE_POLL_MS)
        }
    }

    private fun readResult(shell: EstablishedShell, command: ByteArray): String? {
        val output = StringBuilder()
        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + PROBE_TIMEOUT_MS
        val retryAt = startedAt + RETRY_AFTER_MS
        var retried = false
        var sawEof = false
        var sawDone = false
        while (System.currentTimeMillis() < deadline) {
            val chunk = shell.read()
            if (chunk == null) {
                sawEof = true
                break
            }
            if (chunk.isEmpty()) {
                if (!retried && System.currentTimeMillis() >= retryAt) {
                    // Insurance for a first write that still raced the line
                    // editor's type-ahead flush: by now the shell is
                    // interactive, so type the line once more.
                    retried = true
                    runCatching { shell.write(command) }
                }
                Thread.sleep(IDLE_POLL_MS)
                continue
            }
            output.append(chunk.toString(Charsets.UTF_8))
            if (output.contains(DONE_MARKER)) {
                sawDone = true
                break
            }
        }
        GxrLog.d(TAG) {
            val elapsed = System.currentTimeMillis() - startedAt
            "probe read ${elapsed}ms done=$sawDone eof=$sawEof retried=$retried " +
                "raw(${output.length}ch): ${escapeForLog(output.toString().takeLast(RAW_LOG_TAIL_CHARS))}"
        }
        return parseOutput(output.toString())
    }

    companion object {
        internal const val RESULT_MARKER = "__GXR_CWD__"
        internal const val DONE_MARKER = "__GXR_CWD_DONE__"

        // Bounded so a wedged probe delays pane creation by at most
        // READY_TIMEOUT + PROBE_TIMEOUT; on failure the caller falls back to
        // OSC 7 / no cwd.
        private const val PROBE_TIMEOUT_MS = 5_000L
        private const val READY_TIMEOUT_MS = 2_500L
        private const val READY_QUIET_WINDOW_MS = 250L
        private const val RETRY_AFTER_MS = 2_000L
        private const val IDLE_POLL_MS = 16L
        private const val RAW_LOG_TAIL_CHARS = 600

        /** Control bytes and escapes as visible text so logcat shows one honest line. */
        private fun escapeForLog(s: String): String = buildString(s.length) {
            for (ch in s) {
                when {
                    ch == '\\' -> append("\\\\")
                    ch.code in 0x20..0x7E -> append(ch)
                    ch == '\n' -> append("\\n")
                    ch == '\r' -> append("\\r")
                    ch == '\t' -> append("\\t")
                    else -> append("\\x%02x".format(ch.code))
                }
            }
        }

        /**
         * One line typed into the remote interactive shell. Both markers are
         * assembled by printf from split pieces so the pty's echo of the
         * command text can never contain a contiguous marker — the parser
         * only ever matches real output, even before `stty -echo` lands.
         */
        internal fun buildCommand(sessionName: String): String {
            require(sessionName.matches(SESSION_NAME)) { "invalid host session name" }
            val t = HostSessionBootstrap.tmux()
            // CR-terminated like every proven typed line in this codebase
            // (bootstrap, submitCommand): CR is what a real Enter sends.
            return "stty -echo 2>/dev/null; " +
                "printf '__GXR_%s\\t%s\\n__GXR_%s\\n' 'CWD__' " +
                "\"\$($t display-message -p -t $sessionName '#{pane_current_path}' 2>/dev/null)\" " +
                "'CWD_DONE__'; " +
                "stty echo 2>/dev/null\r"
        }

        internal fun parseOutput(output: String): String? {
            return output.lineSequence()
                .mapNotNull { raw ->
                    // The marker may not start the line: zsh precmd/preexec
                    // hooks emit OSC title escapes (ESC]2;...BEL) with no
                    // trailing newline, gluing them onto the front of the
                    // result line. Matching anywhere is still echo-safe —
                    // neither the typed command nor the title text derived
                    // from it ever contains the contiguous marker.
                    val idx = raw.indexOf(RESULT_MARKER)
                    if (idx < 0) return@mapNotNull null
                    raw.substring(idx + RESULT_MARKER.length)
                        .substringBefore('\u001b')
                        .substringBefore('\u0007')
                        // Tab-separated, but tolerate ptys that expand output
                        // tabs to spaces (BSD oxtabs).
                        .trimStart('\t', ' ')
                        .trimEnd()
                        .takeIf { it.isNotBlank() }
                }
                .firstOrNull()
                ?.let(WorkingDirectory::normalize)
        }

        private val SESSION_NAME = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
