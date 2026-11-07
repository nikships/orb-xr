package com.ghosttyxr.app.qr

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory holder for the most recently scanned [PairingPayload]. Phase 9b prep substrate only —
 * the actual handoff to a `TerminalSessionEngine` happens in task #11 once libssh2 (#4) is wired.
 */
object PendingPairing {
    private val state = MutableStateFlow<PairingPayload?>(null)
    val flow: StateFlow<PairingPayload?> = state.asStateFlow()

    fun set(payload: PairingPayload) {
        state.value = payload
    }

    fun clear() {
        state.value = null
    }
}
