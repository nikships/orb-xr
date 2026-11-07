package com.ghosttyxr.app.terminal

import android.util.Log

/**
 * Task #3 smoke test. Drives the JNI bridge with a single literal write so
 * we can verify, end-to-end, that:
 *   - `libghosttyxr.so` loads,
 *   - `nativeCreate` returns a non-zero handle,
 *   - libghostty parses bytes via `nativeWriteRemote`,
 *   - the binary snapshot round-trips through `TerminalSnapshot.fromByteBuffer`,
 *   - the resulting grid contains the bytes we wrote.
 *
 * Returns null if the `.so` failed to load or the bridge returned 0
 * (allocator failed). Caller falls back to [buildDemoSnapshot] in that case
 * so the headset still renders something.
 *
 * Temporary — remove once Phase 3 wires real SSH bytes through the same
 * code path.
 */
fun buildBridgeSmokeSnapshot(bridge: GhosttyBridge = GhosttyBridge()): TerminalSnapshot? {
    if (!bridge.isLoaded()) {
        Log.w("BridgeSmokeTest", "ghosttyxr .so not loaded: ${bridge.nativeStatus()}")
        return null
    }

    val cols = 80
    val rows = 25
    val handle = bridge.nativeCreate(cols, rows, 1000)
    if (handle == 0L) {
        Log.w("BridgeSmokeTest", "nativeCreate returned 0 — allocator or init failed")
        return null
    }

    return try {
        // Cell size 8x16 is arbitrary for the smoke test; only matters for
        // kitty image placement math which isn't exercised here.
        bridge.nativeResize(handle, cols, rows, 8, 16)

        val payload = "echo hello\r\n".toByteArray(Charsets.UTF_8)
        bridge.nativeWriteRemote(handle, payload)

        val buffer = bridge.nativeSnapshot(handle)
        if (buffer.capacity() == 0) {
            Log.w("BridgeSmokeTest", "snapshot buffer was empty")
            return null
        }

        val snapshot = TerminalSnapshot.fromByteBuffer(buffer)
        Log.i(
            "BridgeSmokeTest",
            "ok cols=${snapshot.cols} rows=${snapshot.rows} cursor=(${snapshot.cursorX},${snapshot.cursorY}) firstRow=${rowToString(snapshot, 0)}",
        )
        snapshot
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
    return sb.toString().trimEnd()
}
