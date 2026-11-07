package com.ghosttyxr.app.terminal.session

/**
 * Per-pane connection lifecycle.
 *
 * Slimmer than chuchu's flat `SessionStatus` enum + `SessionState` data
 * class — modelled as a sealed interface so the compiler enforces handling
 * every state and the variant-specific data (reconnect attempt count,
 * error message, title/pwd) lives where it's relevant instead of as
 * nullable fields on a god-object.
 *
 * Transitions:
 *   [Disconnected] → [Connecting] → ([Authenticated] →) [Connected]
 *   [Connected] → [Reconnecting] → [Connected] (on success) or [Failed]
 *   any → [Disconnected] (on `disconnect()`)
 *   any → [Failed] (terminal error; user must `connect()` again)
 */
sealed interface SessionState {
    data object Disconnected : SessionState

    data object Connecting : SessionState

    /** SSH transport + auth succeeded; PTY shell not yet open. */
    data object Authenticated : SessionState

    /** PTY shell open and read loop running. */
    data class Connected(
        val title: String? = null,
        val pwd: String? = null,
    ) : SessionState

    data class Reconnecting(val attempt: Int) : SessionState

    data class Failed(val error: String) : SessionState
}
