package com.ghosttyxr.app.terminal.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ghosttyxr.app.qr.PairingPayload
import com.ghosttyxr.app.terminal.ssh.VerifiedHostAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies [TerminalSessionEngine.submitCommand] (the voice / Gemini path) holds a command until
 * the channel is inbound-quiescent, so libghostty's terminal-capability query replies
 * (`ESC[?62;…c`, `ESC[?0u`) never share a write window with the injected command — the
 * "Gemini command prepended with 0u62;…" bug.
 *
 * Real engine + real libghostty over [FakeSshTransport]. The fake injects a capability query
 * (as a real prompt would after a resize-driven redraw) slightly AFTER the command is submitted,
 * modelling the SSH round-trip latency that makes the reply land late. The gate must absorb that:
 * the reply is written, libghostty answers, and only then is the command written — as its own
 * contiguous bytes with no reply spliced in.
 */
@RunWith(AndroidJUnit4::class)
class CommandQuiescenceTest {

    private fun hostAccess(fake: FakeSshTransport): VerifiedHostAccess =
        VerifiedHostAccess(transportFactory = { fake })

    @Test
    fun submitCommandWaitsOutInFlightQueryTraffic() {
        val fake = FakeSshTransport()
        val engine = TerminalSessionEngine(paneId = 0, hostAccess = hostAccess(fake))
        try {
            engine.connect(PAIRING)
            awaitConnected(engine)

            // Model the production trigger: a fancy prompt is mid-redraw, streaming capability
            // queries inbound, when the user fires the voice command. Keep the queries flowing
            // for a stretch on a background thread, submitting the command while they're in flight.
            val streamer = Thread {
                repeat(8) {
                    fake.enqueueRead("\u001b[c\u001b[?u".toByteArray())
                    Thread.sleep(20)
                }
            }.also { it.start() }

            Thread.sleep(30) // let inbound traffic get going
            engine.submitCommand("git status\r".toByteArray())

            awaitWrite(fake) { it.contains("git status\r") }
            streamer.join()
            Thread.sleep(50)

            val writes = fake.writtenBytes().map { String(it, Charsets.UTF_8) }
            println("QUIES writes = ${writes.map { escape(it) }}")

            // 1) The command is its own contiguous write — never merged with a reply.
            val commandWrite = writes.firstOrNull { it.contains("git status\r") }
            assertTrue("command never written; writes=${writes.map { escape(it) }}", commandWrite != null)
            assertEquals(
                "command write must be exactly the injected bytes: '${escape(commandWrite!!)}'",
                "git status\r",
                commandWrite,
            )

            // 2) The command is the LAST write — every query reply was flushed before it, and the
            //    gate held the command until inbound traffic stopped, so a reply is never the byte
            //    immediately preceding the command (which is what splices "0u"/"62;" onto the line).
            assertEquals(
                "command must be the final write, after all query replies settled",
                writes.size - 1,
                writes.indexOfFirst { it.contains("git status\r") },
            )
            // 3) The write just before the command must be a query reply that has fully landed —
            //    and the gate guarantees a quiet gap after it, so they aren't adjacent on the wire.
            val replyWrites = writes.dropLast(1)
            assertTrue(
                "expected query replies to have been flushed before the command",
                replyWrites.any { it.contains("\u001b[?62;") || it.contains("\u001b[?0u") },
            )
        } finally {
            engine.close()
        }
    }

    @Test
    fun submitCommandSeparatedFromLastInboundByQuiescenceWindow() {
        val fake = FakeSshTransport()
        val engine = TerminalSessionEngine(paneId = 2, hostAccess = hostAccess(fake))
        try {
            engine.connect(PAIRING)
            awaitConnected(engine)

            // Submit while a query reply is in flight; record when the last inbound byte was
            // processed vs when the command was written. The gap is what stops the prepend.
            val lastInboundAtMs = java.util.concurrent.atomic.AtomicLong(0)
            val streamer = Thread {
                repeat(5) {
                    fake.enqueueRead("\u001b[c".toByteArray())
                    lastInboundAtMs.set(System.currentTimeMillis())
                    Thread.sleep(20)
                }
            }.also { it.start() }

            Thread.sleep(25)
            engine.submitCommand("ls\r".toByteArray())
            awaitWrite(fake) { it.contains("ls\r") }
            val commandWrittenAtMs = System.currentTimeMillis()
            streamer.join()

            val gap = commandWrittenAtMs - lastInboundAtMs.get()
            println("QUIES gap last-inbound→command = ${gap}ms")
            // The command must trail the last inbound byte by roughly the quiescence window,
            // not land right on top of it.
            assertTrue(
                "command landed too close to inbound traffic (${gap}ms); prepend risk",
                gap >= COMMAND_QUIESCENCE_WINDOW_MS - COMMAND_QUIESCENCE_POLL_MS,
            )
        } finally {
            engine.close()
        }
    }

    @Test
    fun submitCommandWritesPromptlyOnQuietChannel() {
        val fake = FakeSshTransport()
        val engine = TerminalSessionEngine(paneId = 1, hostAccess = hostAccess(fake))
        try {
            engine.connect(PAIRING)
            awaitConnected(engine)
            // Quiet channel: command should be written within roughly the quiescence window,
            // well under the timeout.
            val t0 = System.currentTimeMillis()
            engine.submitCommand("ls\r".toByteArray())
            awaitWrite(fake) { it.contains("ls\r") }
            val elapsed = System.currentTimeMillis() - t0
            println("QUIES quiet elapsed=${elapsed}ms")
            assertTrue("submit took too long on a quiet channel: ${elapsed}ms", elapsed < 1_000)
        } finally {
            engine.close()
        }
    }

    private fun awaitConnected(engine: TerminalSessionEngine, timeoutMs: Long = 3_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (engine.state.value is SessionState.Connected) return
            Thread.sleep(20)
        }
        throw AssertionError("never connected")
    }

    private fun awaitWrite(fake: FakeSshTransport, timeoutMs: Long = 3_000, predicate: (String) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val combined = fake.writtenBytes().joinToString("") { String(it, Charsets.UTF_8) }
            if (predicate(combined)) return
            Thread.sleep(20)
        }
        throw AssertionError("write predicate never matched")
    }

    private fun escape(s: String): String = buildString {
        for (c in s) when {
            c == '\u001b' -> append("<ESC>")
            c == '\r' -> append("<CR>")
            c == '\n' -> append("<LF>")
            c.code < 0x20 -> append("<0x%02x>".format(c.code))
            else -> append(c)
        }
    }

    private companion object {
        val PAIRING = PairingPayload(host = "mac.local", port = 22, user = "dev", token = "hunter2", fingerprint = null)
    }
}
