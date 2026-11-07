package com.ghosttyxr.app.terminal.session

import org.junit.Assert.assertTrue
import org.junit.Test

class HostSessionTerminatorTest {

    @Test
    fun commandKillsOnlyRequestedDedicatedServerSessions() {
        val command = HostSessionTerminator.command(listOf("gxr-cockpit-1", "gxr-cockpit-2"))

        assertTrue(command.contains("tmux -L gxr kill-session -t gxr-cockpit-1"))
        assertTrue(command.contains("tmux -L gxr kill-session -t gxr-cockpit-2"))
        assertTrue(command.contains("'TERMINATED__'"))
        assertTrue(command.endsWith("\r"))
    }

    @Test
    fun allCommandKillsOnlyTheDedicatedServer() {
        val command = HostSessionTerminator.allCommand()

        assertTrue(command.contains("tmux -L gxr kill-server"))
        assertTrue(command.contains("'TERMINATED__'"))
        assertTrue(command.endsWith("\r"))
    }
}
