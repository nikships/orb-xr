package com.ghosttyxr.app.terminal.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentLaunchTest {

    @Test
    fun mapsKnownAgentsToLaunchCommands() {
        assertEquals(
            "claude --dangerously-skip-permissions",
            AgentLaunch.command("claude"),
        )
        assertEquals("codex --yolo", AgentLaunch.command("codex"))
        assertEquals("droid", AgentLaunch.command("droid"))
        assertEquals("grok", AgentLaunch.command("grok"))
    }

    @Test
    fun matchingIsCaseInsensitiveAndTrims() {
        assertEquals("grok", AgentLaunch.command("  Grok  "))
        assertEquals("codex --yolo", AgentLaunch.command("CODEX"))
    }

    @Test
    fun unknownAgentReturnsNull() {
        assertNull(AgentLaunch.command("aider"))
        assertNull(AgentLaunch.command(""))
    }
}
