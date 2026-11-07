package com.ghosttyxr.app.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ghosttyxr.app.terminal.input.GhosttyKey
import com.ghosttyxr.app.terminal.input.GhosttyKeyAction
import com.ghosttyxr.app.terminal.input.GhosttyKeyMods
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Task #3 smoke test, instrumented. Runs on a real device/emulator with
 * `libghosttyxr.so` actually loaded (the host JVM can't dlopen the
 * aarch64-android .so, hence the `androidTest/` source set rather than
 * the `test/` one).
 *
 * Verifies the full JNI loop:
 *   1. `.so` loads.
 *   2. `nativeCreate` returns a non-zero handle.
 *   3. Bytes written via `nativeWriteRemote` are parsed by libghostty
 *      and surface in the binary snapshot.
 *   4. The snapshot deserializes through [TerminalSnapshot.fromByteBuffer]
 *      and the expected characters land at the expected grid positions.
 *
 * Also covers the previously-skipped JNI-bound KeyMapper assertion: the
 * arrow-up physical key encodes to `ESC [ A` through libghostty's key
 * encoder.
 *
 * Run with `./gradlew :app:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class BridgeInstrumentedTest {

    @Test
    fun loadsTheNativeLibrary() {
        val bridge = GhosttyBridge()
        assertTrue("expected libghosttyxr.so to load: ${bridge.nativeStatus()}", bridge.isLoaded())
    }

    @Test
    fun versionReturnsOne() {
        val bridge = GhosttyBridge()
        assertEquals("1", bridge.nativeVersion())
    }

    @Test
    fun echoHelloRoundTripsThroughSnapshot() {
        val bridge = GhosttyBridge()
        assertTrue(bridge.isLoaded())

        val cols = 80
        val rows = 25
        val handle = bridge.nativeCreate(cols, rows, 1000)
        assertNotEquals("nativeCreate returned 0 (alloc/init failed)", 0L, handle)

        try {
            // Establish a cell size so libghostty can compute kitty-image
            // grid offsets. Values are arbitrary for the text-only smoke.
            bridge.nativeResize(handle, cols, rows, 8, 16)

            bridge.nativeWriteRemote(handle, "echo hello\r\n".toByteArray(Charsets.UTF_8))

            val buffer = bridge.nativeSnapshot(handle)
            assertTrue("snapshot buffer was empty", buffer.capacity() > 0)

            val snapshot = TerminalSnapshot.fromByteBuffer(buffer)
            assertEquals(cols, snapshot.cols)
            assertEquals(rows, snapshot.rows)

            val row0 = rowToString(snapshot, 0).trimEnd()
            assertEquals("echo hello", row0)

            // libghostty parses `\r\n` as carriage return + line feed, so
            // the cursor lands on column 0 of row 1.
            assertEquals(0, snapshot.cursorX)
            assertEquals(1, snapshot.cursorY)
            assertTrue(snapshot.cursorVisible)
        } finally {
            bridge.nativeDestroy(handle)
        }
    }

    @Test
    fun arrowUpEncodesToEscBracketA() {
        val bridge = GhosttyBridge()
        assertTrue(bridge.isLoaded())

        val handle = bridge.nativeCreate(80, 25, 0)
        assertNotEquals(0L, handle)
        try {
            val bytes = bridge.nativeEncodeKey(
                handle,
                GhosttyKey.arrowUp,
                0,
                0,
                GhosttyKeyAction.Press,
                null,
            )
            assertNotNull("nativeEncodeKey returned null", bytes)
            // Default cursor key mode: CSI A
            assertArrayEquals(byteArrayOf(0x1b, '['.code.toByte(), 'A'.code.toByte()), bytes)
        } finally {
            bridge.nativeDestroy(handle)
        }
    }

    @Test
    fun controlCEncodesToInterruptByte() {
        val bridge = GhosttyBridge()
        assertTrue(bridge.isLoaded())

        val handle = bridge.nativeCreate(80, 25, 0)
        assertNotEquals(0L, handle)
        try {
            val bytes = bridge.nativeEncodeKey(
                handle,
                GhosttyKey.keyC,
                'c'.code,
                GhosttyKeyMods.CONTROL,
                GhosttyKeyAction.Press,
                null,
            )
            assertNotNull("nativeEncodeKey returned null", bytes)
            assertArrayEquals(byteArrayOf(0x03), bytes)
        } finally {
            bridge.nativeDestroy(handle)
        }
    }

    @Test
    fun normalScreenScrollStaysLocalAndTracksLiveOutput() {
        val bridge = GhosttyBridge()
        assertTrue(bridge.isLoaded())

        val handle = bridge.nativeCreate(12, 3, 100)
        assertNotEquals(0L, handle)
        try {
            bridge.nativeResize(handle, 12, 3, 8, 16)
            bridge.nativeWriteRemote(
                handle,
                "line1\r\nline2\r\nline3\r\nline4\r\n".toByteArray(Charsets.UTF_8),
            )
            bridge.nativeDrainPtyWrites(handle)

            bridge.nativeScroll(handle, -1)

            assertTrue(
                "normal-screen scroll unexpectedly emitted host input",
                bridge.nativeDrainPtyWrites(handle).isEmpty(),
            )
            val scrolled = TerminalSnapshot.fromByteBuffer(bridge.nativeSnapshot(handle))
            assertEquals("line2", rowToString(scrolled, 0).trimEnd())

            bridge.nativeWriteRemote(handle, "line5\r\n".toByteArray(Charsets.UTF_8))
            val anchored = TerminalSnapshot.fromByteBuffer(bridge.nativeSnapshot(handle))
            assertEquals(
                "live output moved the local viewport while scrolled back",
                "line2",
                rowToString(anchored, 0).trimEnd(),
            )

            bridge.nativeScrollToActive(handle)
            val active = TerminalSnapshot.fromByteBuffer(bridge.nativeSnapshot(handle))
            assertTrue(
                "returning to the live edge did not reveal current output",
                (0 until active.rows).any { rowToString(active, it).trimEnd() == "line5" },
            )
        } finally {
            bridge.nativeDestroy(handle)
        }
    }

    @Test
    fun mouseReportingScrollEmitsWheelBytesWithoutArrowKeys() {
        val bridge = GhosttyBridge()
        assertTrue(bridge.isLoaded())

        val handle = bridge.nativeCreate(12, 3, 100)
        assertNotEquals(0L, handle)
        try {
            bridge.nativeResize(handle, 12, 3, 8, 16)
            bridge.nativeSetMouseEncodingSize(handle, 96, 48, 8, 16, 0, 0, 0, 0)
            bridge.nativeWriteRemote(handle, "\u001B[?1000h".toByteArray(Charsets.UTF_8))
            bridge.nativeDrainPtyWrites(handle)

            bridge.nativeScroll(handle, -1)

            val encoded = bridge.nativeDrainPtyWrites(handle)
            assertTrue("mouse-reporting scroll emitted no wheel bytes", encoded.isNotEmpty())
            val wire = encoded.toString(Charsets.ISO_8859_1)
            assertFalse("scroll injected arrow-up input", wire.contains("\u001B[A"))
            assertFalse("scroll injected arrow-down input", wire.contains("\u001B[B"))
        } finally {
            bridge.nativeDestroy(handle)
        }
    }

    private fun rowToString(snapshot: TerminalSnapshot, row: Int): String {
        if (row !in 0 until snapshot.rows) return ""
        val sb = StringBuilder()
        val start = row * snapshot.cols
        for (i in start until start + snapshot.cols) {
            val cp = snapshot.codepoints[i]
            if (cp == 0) sb.append(' ') else sb.appendCodePoint(cp)
        }
        return sb.toString()
    }
}
