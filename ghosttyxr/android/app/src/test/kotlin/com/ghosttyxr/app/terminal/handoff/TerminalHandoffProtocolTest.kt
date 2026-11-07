package com.ghosttyxr.app.terminal.handoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Codec coverage for the terminal-handoff wire protocol: encode shape,
 * decode of every companion → headset message, lockstep version rejection,
 * session-name validation, and framer limits. Mirrors the discipline of
 * HostKeyboardProtocolTest — pure JVM, no Android, no SSH.
 */
class TerminalHandoffProtocolTest {

    @Test
    fun `hello line carries version role and app`() {
        val line = TerminalHandoffProtocol.helloLine().toString(Charsets.UTF_8)
        assertTrue(line.endsWith("\n"))
        val parsed = TerminalHandoffProtocol.parse(line.trim())
        assertEquals(TerminalHandoffMessage.Hello(role = "headset"), parsed)
        assertTrue(line.contains("\"v\":1"))
        assertTrue(line.contains("\"app\":\"ghosttyxr\""))
    }

    @Test
    fun `open_sessions line round-trips names titles and req id`() {
        val sessions = listOf(
            HostSessionRef(name = "gxr-abc-0", title = "claude"),
            HostSessionRef(name = "gxr-abc-1"),
        )
        val line = TerminalHandoffProtocol.openSessionsLine("req-7", sessions).toString(Charsets.UTF_8)
        val parsed = TerminalHandoffProtocol.parse(line.trim()) as TerminalHandoffMessage.OpenSessions
        assertEquals("req-7", parsed.reqId)
        assertEquals(sessions, parsed.sessions)
    }

    @Test
    fun `open_sessions encoding rejects invalid session names`() {
        assertThrows(TerminalHandoffProtocolException::class.java) {
            TerminalHandoffProtocol.openSessionsLine(
                "req-1",
                listOf(HostSessionRef(name = "gxr-abc-0; rm -rf /")),
            )
        }
    }

    @Test
    fun `open_sessions encoding rejects an empty session list`() {
        assertThrows(TerminalHandoffProtocolException::class.java) {
            TerminalHandoffProtocol.openSessionsLine("req-1", emptyList())
        }
    }

    @Test
    fun `parses ready`() {
        assertEquals(
            TerminalHandoffMessage.Ready,
            TerminalHandoffProtocol.parse("""{"v":1,"type":"ready"}"""),
        )
    }

    @Test
    fun `parses a successful open_result`() {
        val parsed = TerminalHandoffProtocol.parse(
            """{"v":1,"type":"open_result","req_id":"r1","ok":true,"opened":2,"failed":[]}""",
        ) as TerminalHandoffMessage.OpenResult
        assertEquals("r1", parsed.reqId)
        assertTrue(parsed.ok)
        assertEquals(2, parsed.opened)
        assertTrue(parsed.failed.isEmpty())
    }

    @Test
    fun `parses a failed open_result with failure details`() {
        val parsed = TerminalHandoffProtocol.parse(
            """
            {"v":1,"type":"open_result","req_id":"r2","ok":false,"opened":0,
             "failed":[{"name":"gxr-abc-1","error":"no such session"}]}
            """.trimIndent().replace("\n", ""),
        ) as TerminalHandoffMessage.OpenResult
        assertFalse(parsed.ok)
        assertEquals(0, parsed.opened)
        assertEquals(listOf(HandoffFailure("gxr-abc-1", "no such session")), parsed.failed)
    }

    @Test
    fun `parses an error message`() {
        val parsed = TerminalHandoffProtocol.parse("""{"v":1,"type":"error","message":"boom"}""")
        assertEquals(TerminalHandoffMessage.Error("boom"), parsed)
    }

    @Test
    fun `rejects a missing protocol version`() {
        assertThrows(TerminalHandoffProtocolException::class.java) {
            TerminalHandoffProtocol.parse("""{"type":"ready"}""")
        }
    }

    @Test
    fun `rejects an unsupported protocol version`() {
        assertThrows(TerminalHandoffProtocolException::class.java) {
            TerminalHandoffProtocol.parse("""{"v":2,"type":"ready"}""")
        }
    }

    @Test
    fun `rejects an unsupported message type`() {
        assertThrows(TerminalHandoffProtocolException::class.java) {
            TerminalHandoffProtocol.parse("""{"v":1,"type":"teleport"}""")
        }
    }

    @Test
    fun `rejects open_result without ok`() {
        assertThrows(TerminalHandoffProtocolException::class.java) {
            TerminalHandoffProtocol.parse("""{"v":1,"type":"open_result","req_id":"r1"}""")
        }
    }

    @Test
    fun `rejects inbound open_sessions with an invalid name`() {
        assertThrows(TerminalHandoffProtocolException::class.java) {
            TerminalHandoffProtocol.parse(
                """{"v":1,"type":"open_sessions","req_id":"r1","sessions":[{"name":"bad name"}]}""",
            )
        }
    }

    @Test
    fun `session name validation matches the bootstrap alphabet`() {
        assertTrue(TerminalHandoffProtocol.isValidSessionName("gxr-abc123-0"))
        assertTrue(TerminalHandoffProtocol.isValidSessionName("A_b-9"))
        assertFalse(TerminalHandoffProtocol.isValidSessionName(""))
        assertFalse(TerminalHandoffProtocol.isValidSessionName("has space"))
        assertFalse(TerminalHandoffProtocol.isValidSessionName("semi;colon"))
        assertFalse(TerminalHandoffProtocol.isValidSessionName("x".repeat(65)))
        assertTrue(TerminalHandoffProtocol.isValidSessionName("x".repeat(64)))
    }

    @Test
    fun `framer splits chunks into lines and strips carriage returns`() {
        val framer = TerminalHandoffLineFramer()
        val lines = framer.accept("{\"a\":1}\r\n{\"b\"".toByteArray()) +
            framer.accept(":2}\n".toByteArray())
        assertEquals(listOf("""{"a":1}""", """{"b":2}"""), lines)
    }

    @Test
    fun `framer rejects oversized frames`() {
        val framer = TerminalHandoffLineFramer(maxFrameBytes = 8)
        assertThrows(TerminalHandoffProtocolException::class.java) {
            framer.accept("0123456789".toByteArray())
        }
    }
}
