package com.ghosttyxr.app.terminal.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSessionProbeTest {

    @Test
    fun buildsHostProbeForNamedTmuxPanesAndTranscriptStores() {
        val command = AgentSessionProbe.buildCommand(
            mapOf(
                7L to "gxr-cockpit-7",
                9L to "gxr-cockpit-9",
            ),
        )

        assertTrue(command.contains("'gxr-cockpit-7':7"))
        assertTrue(command.contains("'gxr-cockpit-9':9"))
        assertTrue(command.contains("~/.claude/projects/**/*.jsonl"))
        assertTrue(command.contains("~/.codex/sessions/**/*.jsonl"))
        assertTrue(command.contains("~/.factory/sessions-index.json"))
        assertTrue(command.contains("~/.grok/active_sessions.json"))
        assertTrue(command.contains("~/.grok/sessions"))
        assertTrue(command.contains("\"grok\""))
        assertTrue(command.contains("#{pane_current_path}"))
        assertTrue(command.contains("\"-L\", \"${HostSessionBootstrap.TMUX_SOCKET}\""))
        assertFalse(command.contains("terminal title"))
    }

    @Test
    fun parsesOnlySafeStructuredProbeRecords() {
        val output = """
            shell command echo
            ${AgentSessionProbe.RESULT_MARKER}	7	claude	claude-session
            ${AgentSessionProbe.RESULT_MARKER}	9	codex	codex_session.2
            ${AgentSessionProbe.RESULT_MARKER}	11	grok	019f54cf-e30c-71f2-8b8f-4f44c657abad
            ${AgentSessionProbe.RESULT_MARKER}	10	droid	bad;command
            ${AgentSessionProbe.RESULT_MARKER}	nope	droid	droid-session
            ${AgentSessionProbe.DONE_MARKER}
        """.trimIndent()

        assertEquals(
            mapOf(
                7L to AgentResume(AgentResume.Kind.Claude, "claude-session"),
                9L to AgentResume(AgentResume.Kind.Codex, "codex_session.2"),
                11L to AgentResume(AgentResume.Kind.Grok, "019f54cf-e30c-71f2-8b8f-4f44c657abad"),
            ),
            AgentSessionProbe.parseOutput(output),
        )
    }
}
