package com.ghosttyxr.app.terminal.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkingDirectoryTest {

    @Test
    fun normalizesAbsolutePaths() {
        assertEquals("/Users/me/project", WorkingDirectory.normalize("/Users/me/project"))
        assertEquals("/tmp", WorkingDirectory.normalize("  /tmp  "))
    }

    @Test
    fun normalizesFileUrls() {
        assertEquals("/Users/me/project", WorkingDirectory.normalize("file:///Users/me/project"))
        assertEquals(
            "/Users/me/My Project",
            WorkingDirectory.normalize("file:///Users/me/My%20Project"),
        )
        assertEquals(
            "/Users/me/project",
            WorkingDirectory.normalize("file://localhost/Users/me/project"),
        )
    }

    @Test
    fun rejectsUnsafeOrRelativePaths() {
        assertNull(WorkingDirectory.normalize(null))
        assertNull(WorkingDirectory.normalize(""))
        assertNull(WorkingDirectory.normalize("relative/path"))
        assertNull(WorkingDirectory.normalize("C:\\Windows"))
    }

    @Test
    fun shellQuotesSingleQuotes() {
        assertEquals("'/Users/me/project'", WorkingDirectory.shellQuote("/Users/me/project"))
        assertEquals("'/Users/me/it'\\''s'", WorkingDirectory.shellQuote("/Users/me/it's"))
    }

    @Test
    fun postConnectCombinesCdAndLaunch() {
        assertEquals(
            "cd -- '/Users/me/proj' && claude --dangerously-skip-permissions",
            WorkingDirectory.postConnectCommand(
                "/Users/me/proj",
                "claude --dangerously-skip-permissions",
            ),
        )
        assertEquals(
            "cd -- '/Users/me/proj'",
            WorkingDirectory.postConnectCommand("/Users/me/proj", null),
        )
        assertEquals(
            "codex --yolo",
            WorkingDirectory.postConnectCommand(null, "codex --yolo"),
        )
        assertNull(WorkingDirectory.postConnectCommand(null, null))
    }
}
