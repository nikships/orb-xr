package com.ghosttyxr.app.terminal.session

import com.ghosttyxr.app.terminal.input.MappedKey
import com.ghosttyxr.app.terminal.ssh.FakeSshTransport
import com.ghosttyxr.app.terminal.ssh.VerifiedHostAccess
import com.ghosttyxr.app.terminal.theme.GhosttyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pure-JVM coverage for [EngineCockpitPane] — the [CockpitPane] adapter that
 * lets [DefaultCockpitLifecycle] hand out a narrow capability surface without
 * ever exposing the underlying [TerminalSessionEngine].
 */
class EngineCockpitPaneTest {

    private fun buildEngine(paneId: Int = 0): TerminalSessionEngine =
        TerminalSessionEngine(
            paneId = paneId,
            hostAccess = VerifiedHostAccess(
                transportFactory = {
                    FakeSshTransport(handshakeFailure = IllegalStateException("no SSH in JVM engine tests"))
                },
                ),
        )

    @Test
    fun `id mirrors the wrapped engine's stable paneId`() {
        val engine = buildEngine(paneId = 42)

        val pane = EngineCockpitPane(engine)

        assertEquals(PaneId(42L), pane.id)
        engine.close()
    }

    @Test
    fun `state and snapshot expose the same flow instances as the engine`() {
        val engine = buildEngine()

        val pane = EngineCockpitPane(engine)

        assertSame(engine.state, pane.state)
        assertSame(engine.snapshot, pane.snapshot)
        engine.close()
    }

    @Test
    fun `capability calls forward to the engine without throwing`() {
        val engine = buildEngine()
        val pane = EngineCockpitPane(engine)

        // No native surface exists on the host JVM (no libghosttyxr.so) — every
        // capability must be a safe, harmless forward rather than throwing.
        pane.sendKey(MappedKey(key = 0, codepoint = 'a'.code, mods = 0, action = 0, utf8 = "a"))
        pane.sendInput(byteArrayOf(1, 2, 3))
        pane.submitCommand("echo hi\r".encodeToByteArray())
        pane.scroll(3)
        pane.scrollToActive()
        pane.sendFocusEvent(true)
        pane.resize(cols = 80, rows = 24, cellWidthPx = 8, cellHeightPx = 16)
        pane.setColors(GhosttyTheme.parse("test", ""))

        // Without a real transport/native surface the engine simply stays
        // Disconnected — forwarding didn't corrupt or advance its state.
        assertEquals(SessionState.Disconnected, engine.state.value)
        engine.close()
    }
}
