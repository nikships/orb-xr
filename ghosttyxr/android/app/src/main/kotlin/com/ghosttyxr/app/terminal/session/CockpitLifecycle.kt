package com.ghosttyxr.app.terminal.session

import com.ghosttyxr.app.qr.PairingPayload
import com.ghosttyxr.app.terminal.handoff.HostSessionRef
import com.ghosttyxr.app.terminal.ssh.SshKey
import com.ghosttyxr.app.terminal.ssh.VerifiedHostAccess
import kotlinx.coroutines.flow.StateFlow

/** A resolved pairing credential: connect target plus optional pubkey auth. */
data class PairedCredentials(
    val payload: PairingPayload,
    val key: SshKey? = null,
)

/**
 * Public production seam for the cockpit's session layer.
 *
 * Owns cockpit-wide state ([CockpitState]), pane lifecycle (add/remove/focus/
 * disconnect), fresh-vs-resume startup selection, host-session termination,
 * and durable snapshot capture scheduling. Production is backed by
 * [DefaultCockpitLifecycle]; tests can provide a lightweight implementation
 * without launching the activity, foreground service, or XR spatial runtime.
 *
 * Implementations never expose [TerminalSessionEngine] — panes are only
 * reachable through the narrow [CockpitPane] capability surface.
 */
interface CockpitLifecycle {
    companion object {
        const val MAX_PANES = 6
    }

    val state: StateFlow<CockpitState>

    /**
     * The application's single SSH trust/transport authority — shared with
     * the provisioner, host-keyboard/window-mirror relays, and the terminal
     * handoff so no consumer constructs its own.
     */
    val hostAccess: VerifiedHostAccess

    /** The cockpit id host session names are minted under; null before first connect. */
    val currentCockpitId: String?

    /** One-shot XR Full Space restore flag, set by [resume] and consumed by the UI. */
    val pendingFullSpaceRestore: StateFlow<Boolean>

    /**
     * Decide the startup state: [CockpitState.NeedsPairing] when [paired] is
     * null, [CockpitState.ResumePicker] when durable snapshots exist, or a
     * fresh [CockpitState.Active] cockpit otherwise.
     */
    fun start(paired: PairedCredentials?)

    /** Connect a brand-new cockpit (fresh session id), replacing any resume picker. */
    fun startFresh(paired: PairedCredentials)

    /** Restore [sessionId] from durable storage and reconnect its panes. */
    fun resume(paired: PairedCredentials, sessionId: String)

    /**
     * Spawn a new pane, auto-connecting it to the credentials the active
     * cockpit was started with. Returns the new pane's id, or null if the
     * pane cap is already reached.
     */
    fun addPane(launchCommand: String? = null, inheritFrom: PaneId? = null): PaneId?

    /**
     * Remove the pane [id], tearing down its SSH session and native surface.
     * No-op when only one pane remains — the cockpit always keeps at least
     * one terminal. Clamps focus onto a remaining pane when the removed pane
     * was focused.
     */
    fun removePane(id: PaneId)

    fun focusPane(id: PaneId)

    fun disconnectPane(id: PaneId)

    /** Disconnect every pane, returning the cockpit to the idle/scanner state. */
    fun disconnectAllPanes()

    /** Kill [sessionId]'s host tmux sessions; deletes the local snapshot only on success. */
    fun terminateSession(paired: PairedCredentials, sessionId: String): Boolean

    /** Kill the whole dedicated tmux server; clears all local snapshots only on success. */
    fun terminateAllSessions(paired: PairedCredentials): Boolean

    /** Live Home/Full Space flag, reported by the UI so captures include it. */
    fun onFullSpaceChanged(isFullSpace: Boolean)

    fun consumePendingFullSpaceRestore()

    /** Force an immediate snapshot capture (e.g. on focus change / backgrounding). */
    fun flushCapture()

    /**
     * Host tmux session names for every currently connected pane in the active
     * cockpit — the set the terminal handoff sends to the Mac. Empty when no
     * cockpit id is active.
     */
    fun liveHostSessions(): List<HostSessionRef>

    /**
     * Post-handoff reset: the Mac terminals now own the attaches, so XR lets
     * go without killing anything. Detaches every pane, collapses the grid to
     * a single disconnected pane, and adopts [newCockpitId] for the next
     * spawned/connected pane. Never touches [terminateSession] / kill-session.
     */
    fun preparePostHandoffCockpit(newCockpitId: String)

    /**
     * Stop durable capture and barrier past any in-flight persist so no
     * pre-stop snapshot can resurrect a handed-off cockpit. A later
     * [start]/[resume] re-arms capture.
     */
    fun stopCapture()

    /** Release all resources (engines, capture job, network monitor). */
    fun close()
}
