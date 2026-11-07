package com.ghosttyxr.app.terminal.session

import com.ghosttyxr.app.terminal.TerminalSnapshot
import com.ghosttyxr.app.terminal.input.MappedKey
import com.ghosttyxr.app.terminal.theme.GhosttyTheme
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Narrow, UI-facing capability surface for one terminal pane. Backed in
 * production by a [TerminalSessionEngine] instance, but callers never see
 * the engine itself — only [id], connection [state], render [snapshot], and
 * the handful of input/output operations the cockpit chrome needs.
 */
interface CockpitPane {
    /** Stable identity — see [PaneId]. */
    val id: PaneId

    val state: StateFlow<SessionState>

    val snapshot: StateFlow<TerminalSnapshot>

    /**
     * Terminal-bell chime events (`\a` / completion chimes) drained from libghostty.
     * Each emission is the coalesced chime count for one snapshot tick — the spatial
     * layer plays one chime per count from this pane's 3D position.
     */
    val bellEvents: SharedFlow<Int>

    /** Encode and forward a mapped keyboard/controller key upstream. */
    fun sendKey(key: MappedKey)

    /** Forward raw bytes upstream (on-screen keyboard text, pty replies). */
    fun sendInput(bytes: ByteArray)

    /** Inject a whole voice/Gemini command, gated on channel quiescence. */
    fun submitCommand(bytes: ByteArray)

    fun scroll(delta: Int)

    fun scrollToActive()

    fun sendFocusEvent(focused: Boolean)

    fun resize(cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int)

    fun setColors(theme: GhosttyTheme)
}
