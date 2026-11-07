package com.ghosttyxr.app.terminal.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentResumeTest {

    @Test
    fun buildsProviderResumeCommands() {
        assertEquals(
            "claude --resume abc-123",
            AgentResume(AgentResume.Kind.Claude, "abc-123").command(),
        )
        assertEquals(
            "codex resume work_1",
            AgentResume(AgentResume.Kind.Codex, "work_1").command(),
        )
        assertEquals(
            "droid --resume session.1",
            AgentResume(AgentResume.Kind.Droid, "session.1").command(),
        )
        assertEquals(
            "grok --resume 019f54cf-e30c-71f2-8b8f-4f44c657abad",
            AgentResume(AgentResume.Kind.Grok, "019f54cf-e30c-71f2-8b8f-4f44c657abad").command(),
        )
    }

    @Test
    fun rejectsUnsafeSessionIds() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentResume(AgentResume.Kind.Claude, "hello; rm -rf /")
        }
    }
}
