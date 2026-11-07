package com.ghosttyxr.app.ui.pairing

import com.ghosttyxr.app.terminal.session.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingStatusTest {

    @Test
    fun connectingTakesPriority() {
        val states = listOf(SessionState.Connecting, SessionState.Disconnected)
        assertEquals("Connecting to your Mac…", pairingStatusText(states, isPaired = false))
    }

    @Test
    fun authenticatedShowsOpeningShells() {
        assertEquals(
            "Opening terminal shells…",
            pairingStatusText(listOf(SessionState.Authenticated), isPaired = true),
        )
    }

    @Test
    fun reconnectingShowsReconnecting() {
        assertEquals(
            "Reconnecting to your Mac…",
            pairingStatusText(listOf(SessionState.Reconnecting(2)), isPaired = false),
        )
    }

    @Test
    fun failedWhilePairedMentionsRePair() {
        val text = pairingStatusText(listOf(SessionState.Failed("boom")), isPaired = true)
        assertTrue("expected paired-failure copy, got: $text", text.contains("paired Mac"))
    }

    @Test
    fun failedWhileUnpairedMentionsRemoteLogin() {
        val text = pairingStatusText(listOf(SessionState.Failed("boom")), isPaired = false)
        assertTrue("expected unpaired-failure copy, got: $text", text.contains("Remote Login"))
    }

    @Test
    fun pairedIdleShowsReconnecting() {
        assertEquals(
            "Reconnecting to your Mac…",
            pairingStatusText(listOf(SessionState.Disconnected), isPaired = true),
        )
    }

    @Test
    fun unpairedIdlePromptsForQr() {
        assertEquals(
            "Scan the pairing QR from your Mac.",
            pairingStatusText(emptyList(), isPaired = false),
        )
    }

    @Test
    fun connectingBeatsFailedWhenBothPresent() {
        val states = listOf(SessionState.Failed("x"), SessionState.Connecting)
        assertEquals("Connecting to your Mac…", pairingStatusText(states, isPaired = true))
    }
}
