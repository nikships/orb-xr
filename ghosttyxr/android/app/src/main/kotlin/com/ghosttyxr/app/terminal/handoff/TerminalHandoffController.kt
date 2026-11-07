package com.ghosttyxr.app.terminal.handoff

import com.ghosttyxr.app.terminal.ssh.EstablishedTunnel
import com.ghosttyxr.app.terminal.ssh.PairedMac
import com.ghosttyxr.app.terminal.ssh.SshKey
import com.ghosttyxr.app.util.GxrLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID

private const val TAG = "TerminalHandoff"

/** Outcome of a handoff turn, fed back to the Gemini pet as its tool result. */
data class HandoffResult(
    val opened: Int,
    val error: String? = null,
)

/**
 * Sends every live XR terminal to the Mac as local terminal windows attached
 * to the **same** host `tmux -L gxr` sessions, then clears them from XR.
 * Attach handoff, not process migration: agents keep running on the Mac the
 * whole time and no host session is ever killed
 * ([com.ghosttyxr.app.terminal.session.HostSessionTerminator] is deliberately
 * not on this path).
 *
 * Ordering (the seamlessness contract from the spec):
 *  1. Collect live session names while XR is still attached.
 *  2. Ask the companion to verify + open local attaches over a short-lived
 *     SSH `direct-tcpip` tunnel to its loopback listener
 *     ([TERMINAL_HANDOFF_RELAY_PORT]). Brief dual-attach is fine in tmux.
 *  3. Only on `ok:true`: stop session capture, drop this cockpit's resume
 *     snapshot, mint a fresh cockpit id, and reset the grid to a single
 *     disconnected pane (still paired, empty chrome).
 *
 * On any failure XR is left untouched — the user is never stranded with XR
 * closed and nothing on the Mac.
 *
 * Every cockpit dependency is injected as a lambda (same discipline as
 * [com.ghosttyxr.app.terminal.voice.command.VoiceCommandController]) so the
 * whole state machine is unit-testable with fakes — no SSH, no Android.
 */
class TerminalHandoffController(
    /** Persisted pairing (payload + SSH key); null when no Mac is paired. */
    private val loadPairedMac: () -> Pair<PairedMac, SshKey>?,
    /** Opens the verified direct-tcpip tunnel to the companion's handoff listener. */
    private val openTunnel: (PairedMac, SshKey) -> EstablishedTunnel,
    /** Live host session names (connected panes only) for the active cockpit. */
    private val liveSessions: () -> List<HostSessionRef>,
    /** The cockpit id whose resume snapshot must be dropped on success. */
    private val activeCockpitId: () -> String?,
    /** Stops [com.ghosttyxr.app.terminal.session.CockpitLifecycle] durable capture so the old layout is never re-saved. */
    private val stopCapture: () -> Unit,
    /** Removes the handed-off cockpit from the resume picker. */
    private val removeSnapshot: (String) -> Unit,
    /** Mints the next XR session id; returns it. */
    private val startFreshSnapshot: () -> String,
    /** Repository post-handoff reset: disconnect all, collapse to one pane, adopt the fresh cockpit id. */
    private val resetCockpit: (newCockpitId: String) -> Unit,
    private val newReqId: () -> String = { UUID.randomUUID().toString() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMs: Long = OPEN_TIMEOUT_MS,
) {
    /**
     * Run the full handoff. Never throws (except cancellation): every failure
     * becomes a [HandoffResult.error] string the pet can speak.
     */
    suspend fun handoffAll(): HandoffResult = withContext(ioDispatcher) {
        val cockpitId = activeCockpitId()
            ?: return@withContext HandoffResult(0, "No active cockpit to hand off.")
        val sessions = liveSessions()
        if (sessions.isEmpty()) {
            return@withContext HandoffResult(0, "There are no live terminals to send to the Mac.")
        }
        val (mac, key) = loadPairedMac()
            ?: return@withContext HandoffResult(0, "No Mac is paired. Pair a Mac first.")

        val tunnel = try {
            openTunnel(mac, key)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (err: Throwable) {
            GxrLog.w(TAG, "handoff tunnel failed: ${err.message}")
            return@withContext HandoffResult(
                0,
                "Couldn't reach the Mac companion — make sure it's running on your Mac.",
            )
        }

        val result = try {
            withTimeout(timeoutMs) { requestOpen(tunnel, sessions) }
        } catch (timeout: TimeoutCancellationException) {
            HandoffResult(0, "The Mac didn't answer in time. Nothing was changed in XR.")
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (err: Throwable) {
            GxrLog.w(TAG, "handoff request failed: ${err.message}")
            HandoffResult(0, err.message ?: "Sending terminals to the Mac failed.")
        } finally {
            runCatching { tunnel.close() }
        }

        if (result.error == null) {
            // Success only: the Mac windows are attached, so XR can let go.
            // Capture stops BEFORE any disconnect so the handed-off layout is
            // never re-saved under the old id; the snapshot removal clears the
            // resume picker; the fresh id keeps the headset paired with an
            // empty cockpit. No kill-session anywhere on this path.
            stopCapture()
            removeSnapshot(cockpitId)
            val freshCockpitId = startFreshSnapshot()
            resetCockpit(freshCockpitId)
            GxrLog.i(TAG, "handed off ${result.opened} session(s); fresh cockpit $freshCockpitId")
        }
        result
    }

    /**
     * Drive one hello → ready → open_sessions → open_result exchange on
     * [tunnel]. All-or-nothing: the companion opens either every window or
     * none, so an `ok:false` result leaves XR fully attached.
     */
    private suspend fun requestOpen(
        tunnel: EstablishedTunnel,
        sessions: List<HostSessionRef>,
    ): HandoffResult {
        val framer = TerminalHandoffLineFramer()
        tunnel.write(TerminalHandoffProtocol.helloLine())
        var reqId: String? = null
        while (true) {
            val chunk = tunnel.read(TERMINAL_HANDOFF_MAX_FRAME_BYTES)
                ?: error("The handoff channel closed before the Mac answered.")
            if (chunk.isEmpty()) {
                delay(IDLE_READ_DELAY_MS)
                continue
            }
            for (line in framer.accept(chunk)) {
                when (val message = TerminalHandoffProtocol.parse(line)) {
                    TerminalHandoffMessage.Ready -> if (reqId == null) {
                        val id = newReqId()
                        reqId = id
                        tunnel.write(TerminalHandoffProtocol.openSessionsLine(id, sessions))
                    }
                    is TerminalHandoffMessage.OpenResult -> {
                        val expected = reqId
                        if (expected != null && (message.reqId == null || message.reqId == expected)) {
                            return message.toHandoffResult()
                        }
                    }
                    is TerminalHandoffMessage.Error ->
                        error(message.message ?: "The Mac companion reported an error.")
                    is TerminalHandoffMessage.Hello,
                    is TerminalHandoffMessage.OpenSessions,
                    -> Unit // companion never sends these; ignore rather than die
                }
            }
        }
    }

    private fun TerminalHandoffMessage.OpenResult.toHandoffResult(): HandoffResult {
        if (ok) return HandoffResult(opened)
        val detail = failed.joinToString("; ") { "${it.name}: ${it.error}" }
        return HandoffResult(
            0,
            if (detail.isEmpty()) {
                "The Mac couldn't open the terminals. Nothing was changed in XR."
            } else {
                "The Mac couldn't open the terminals ($detail). Nothing was changed in XR."
            },
        )
    }

    companion object {
        /** End-to-end budget for verify + open on the Mac (spec: ~8s). */
        const val OPEN_TIMEOUT_MS = 8_000L
        private const val IDLE_READ_DELAY_MS = 16L
    }
}
