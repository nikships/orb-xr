package com.ghosttyxr.app.terminal.handoff

import com.ghosttyxr.app.terminal.ssh.EstablishedTunnel
import com.ghosttyxr.app.terminal.ssh.PairedMac
import com.ghosttyxr.app.terminal.ssh.SshKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * State-machine coverage for the handoff controller with a scripted fake
 * tunnel and fake cockpit lambdas — no SSH, no Android. Asserts the two
 * invariants the spec cares most about:
 *
 *  1. Success runs the full reset in order: stop capture → remove the old
 *     snapshot → start fresh → reset the cockpit to the fresh id.
 *  2. Any failure (no panes, unpaired, tunnel refused, companion error,
 *     `ok:false`, timeout) leaves the cockpit completely untouched.
 */
class TerminalHandoffControllerTest {

    /**
     * Loopback-in-memory [EstablishedTunnel]: every line the controller writes
     * is handed to [onWrite], which can enqueue the companion's scripted reply.
     */
    private class FakeTunnel : EstablishedTunnel {
        override val verifiedFingerprint = "SHA256:fake"
        val written = mutableListOf<String>()
        var closed = false
        var onWrite: (String) -> Unit = {}
        private val pending = ArrayDeque<ByteArray>()

        fun enqueue(line: String) {
            pending += (line + "\n").toByteArray(Charsets.UTF_8)
        }

        override fun read(maxBytes: Int): ByteArray? =
            if (pending.isEmpty()) ByteArray(0) else pending.removeFirst()

        override fun write(data: ByteArray) {
            val line = data.toString(Charsets.UTF_8).trim()
            written += line
            onWrite(line)
        }

        override fun close() {
            closed = true
        }
    }

    private class Fakes {
        val tunnel = FakeTunnel()
        var sessions = listOf(
            HostSessionRef("gxr-abc-0", "claude"),
            HostSessionRef("gxr-abc-1"),
        )
        var cockpitId: String? = "abc"
        var paired: Pair<PairedMac, SshKey>? = pairedMac()
        var tunnelFailure: Throwable? = null
        val events = mutableListOf<String>()

        fun controller(timeoutMs: Long = 2_000) = TerminalHandoffController(
            loadPairedMac = { paired },
            openTunnel = { _, _ -> tunnelFailure?.let { throw it } ?: tunnel },
            liveSessions = { sessions },
            activeCockpitId = { cockpitId },
            stopCapture = { events += "stopCapture" },
            removeSnapshot = { events += "removeSnapshot:$it" },
            startFreshSnapshot = { events += "startFresh"; "fresh-id" },
            resetCockpit = { events += "resetCockpit:$it" },
            newReqId = { "req-1" },
            timeoutMs = timeoutMs,
        )

        companion object {
            fun pairedMac() = PairedMac(
                host = "192.168.1.2",
                port = 22,
                user = "nik",
                hostFingerprint = "SHA256:fake",
                provisionedAtEpochMs = 0L,
            ) to SshKey(
                name = "test",
                algorithm = "ed25519",
                privateKeyPem = "pem",
                publicKeyOpenSsh = "pub",
                createdAtEpochMs = 0L,
            )
        }
    }

    /** Wire the fake companion: hello → ready, open_sessions → [resultLine]. */
    private fun scriptCompanion(f: Fakes, resultLine: String) {
        f.tunnel.onWrite = { line ->
            when {
                line.contains("\"type\":\"hello\"") -> f.tunnel.enqueue("""{"v":1,"type":"ready"}""")
                line.contains("\"type\":\"open_sessions\"") -> f.tunnel.enqueue(resultLine)
            }
        }
    }

    @Test
    fun `success opens sessions then resets the cockpit in spec order`() {
        val f = Fakes()
        scriptCompanion(f, """{"v":1,"type":"open_result","req_id":"req-1","ok":true,"opened":2,"failed":[]}""")
        val result = runBlocking { f.controller().handoffAll() }
        assertNull(result.error)
        assertEquals(2, result.opened)
        // Ordering is the seamlessness contract: capture stops before any
        // reset so the handed-off layout can never be re-saved under abc.
        assertEquals(
            listOf("stopCapture", "removeSnapshot:abc", "startFresh", "resetCockpit:fresh-id"),
            f.events,
        )
        assertTrue(f.tunnel.closed)
        // The request carried both live session names.
        val openLine = f.tunnel.written.single { it.contains("open_sessions") }
        assertTrue(openLine.contains("gxr-abc-0"))
        assertTrue(openLine.contains("gxr-abc-1"))
        assertTrue(openLine.contains("\"title\":\"claude\""))
    }

    @Test
    fun `no live terminals errors without touching anything`() {
        val f = Fakes().apply { sessions = emptyList() }
        val result = runBlocking { f.controller().handoffAll() }
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("no live terminals", ignoreCase = true))
        assertTrue(f.events.isEmpty())
        assertTrue(f.tunnel.written.isEmpty())
    }

    @Test
    fun `no active cockpit errors without touching anything`() {
        val f = Fakes().apply { cockpitId = null }
        val result = runBlocking { f.controller().handoffAll() }
        assertNotNull(result.error)
        assertTrue(f.events.isEmpty())
    }

    @Test
    fun `unpaired headset errors without opening a tunnel`() {
        val f = Fakes().apply { paired = null }
        val result = runBlocking { f.controller().handoffAll() }
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("Pair a Mac", ignoreCase = true))
        assertTrue(f.events.isEmpty())
        assertTrue(f.tunnel.written.isEmpty())
    }

    @Test
    fun `tunnel refused surfaces a companion-down error and changes nothing`() {
        val f = Fakes().apply { tunnelFailure = IllegalStateException("direct-tcpip open failed") }
        val result = runBlocking { f.controller().handoffAll() }
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("Mac companion"))
        assertTrue(f.events.isEmpty())
    }

    @Test
    fun `ok false leaves XR attached and reports the failed session`() {
        val f = Fakes()
        scriptCompanion(
            f,
            """
            {"v":1,"type":"open_result","req_id":"req-1","ok":false,"opened":0,
             "failed":[{"name":"gxr-abc-1","error":"no such session"}]}
            """.trimIndent().replace("\n", ""),
        )
        val result = runBlocking { f.controller().handoffAll() }
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("gxr-abc-1"))
        assertTrue(result.error!!.contains("no such session"))
        assertTrue(f.events.isEmpty())
        assertTrue(f.tunnel.closed)
    }

    @Test
    fun `companion error message aborts and changes nothing`() {
        val f = Fakes()
        f.tunnel.onWrite = { line ->
            if (line.contains("\"type\":\"hello\"")) {
                f.tunnel.enqueue("""{"v":1,"type":"error","message":"tmux not installed"}""")
            }
        }
        val result = runBlocking { f.controller().handoffAll() }
        assertEquals("tmux not installed", result.error)
        assertTrue(f.events.isEmpty())
        assertTrue(f.tunnel.closed)
    }

    @Test
    fun `silent companion times out and changes nothing`() {
        val f = Fakes() // never replies: hello is written, nothing comes back
        val result = runBlocking { f.controller(timeoutMs = 100).handoffAll() }
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("didn't answer", ignoreCase = true))
        assertTrue(f.events.isEmpty())
        assertTrue(f.tunnel.closed)
    }

    @Test
    fun `closed channel mid-handshake errors and changes nothing`() {
        val f = Fakes()
        val tunnel = object : EstablishedTunnel {
            override val verifiedFingerprint = "SHA256:fake"
            override fun read(maxBytes: Int): ByteArray? = null // EOF immediately
            override fun write(data: ByteArray) = Unit
            override fun close() = Unit
        }
        val controller = TerminalHandoffController(
            loadPairedMac = { f.paired },
            openTunnel = { _, _ -> tunnel },
            liveSessions = { f.sessions },
            activeCockpitId = { f.cockpitId },
            stopCapture = { f.events += "stopCapture" },
            removeSnapshot = { f.events += "removeSnapshot:$it" },
            startFreshSnapshot = { f.events += "startFresh"; "fresh-id" },
            resetCockpit = { f.events += "resetCockpit:$it" },
        )
        val result = runBlocking { controller.handoffAll() }
        assertNotNull(result.error)
        assertTrue(f.events.isEmpty())
    }

    @Test
    fun `open_result for a different request is ignored until ours arrives`() {
        val f = Fakes()
        f.tunnel.onWrite = { line ->
            when {
                line.contains("\"type\":\"hello\"") -> f.tunnel.enqueue("""{"v":1,"type":"ready"}""")
                line.contains("\"type\":\"open_sessions\"") -> {
                    f.tunnel.enqueue("""{"v":1,"type":"open_result","req_id":"stale","ok":false,"opened":0,"failed":[]}""")
                    f.tunnel.enqueue("""{"v":1,"type":"open_result","req_id":"req-1","ok":true,"opened":2,"failed":[]}""")
                }
            }
        }
        val result = runBlocking { f.controller().handoffAll() }
        assertNull(result.error)
        assertEquals(2, result.opened)
    }
}
