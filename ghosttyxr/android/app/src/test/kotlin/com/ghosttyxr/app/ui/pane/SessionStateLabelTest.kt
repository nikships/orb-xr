package com.ghosttyxr.app.ui.pane

import com.ghosttyxr.app.terminal.session.SessionState
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionStateLabelTest {

    @Test
    fun disconnected() {
        assertEquals("disconnected", describeState(SessionState.Disconnected))
    }

    @Test
    fun connecting() {
        assertEquals("connecting…", describeState(SessionState.Connecting))
    }

    @Test
    fun authenticated() {
        assertEquals("authenticated", describeState(SessionState.Authenticated))
    }

    @Test
    fun connectedUsesTitleWhenPresent() {
        assertEquals("vim ~/src", describeState(SessionState.Connected(title = "vim ~/src")))
    }

    @Test
    fun connectedHidesGeneratedTmuxTitle() {
        assertEquals("connected", describeState(SessionState.Connected(title = "tmux gxr-cockpit-1")))
        assertEquals("connected", describeState(SessionState.Connected(title = "gxr-cockpit-1:0.0-zsh")))
    }

    @Test
    fun connectedFallsBackToConnectedWhenBlankOrNull() {
        assertEquals("connected", describeState(SessionState.Connected(title = null)))
        assertEquals("connected", describeState(SessionState.Connected(title = "   ")))
    }

    @Test
    fun reconnectingShowsAttempt() {
        assertEquals("reconnecting #3", describeState(SessionState.Reconnecting(attempt = 3)))
    }

    @Test
    fun failedShowsError() {
        assertEquals("failed: nope", describeState(SessionState.Failed("nope")))
    }
}
