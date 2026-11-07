package com.ghosttyxr.app.terminal

import com.ghosttyxr.app.terminal.TerminalSnapshot.Companion.packArgb

/**
 * Hardcoded 80x25 snapshot used during Phase 1 before the libghostty JNI bridge is wired up.
 * Renders a neon banner + a few prompt lines so we can verify the renderer inside a SpatialPanel.
 */
fun buildDemoSnapshot(): TerminalSnapshot {
    val cols = 80
    val rows = 25
    val bg = packArgb(0x05, 0x07, 0x0A)
    val fg = packArgb(0xE0, 0xE6, 0xEF)
    val neonCyan = packArgb(0x00, 0xE5, 0xFF)
    val neonMagenta = packArgb(0xFF, 0x2E, 0xA8)
    val neonGreen = packArgb(0x39, 0xFF, 0x14)
    val dim = packArgb(0x6E, 0x7A, 0x90)

    val snap = TerminalSnapshot.blank(cols, rows, defaultFgArgb = fg, defaultBgArgb = bg)

    snap.fillRow(2, 1, "Orb XR — Phase 1 hello", neonCyan)
    snap.fillRow(2, 2, "spatial terminal scaffold", neonMagenta)
    snap.fillRow(2, 3, "SpatialPanel + TerminalCanvas + hardcoded snapshot", dim)

    snap.fillRow(2, 5, "$ uname -a", neonGreen)
    snap.fillRow(2, 6, "Android XR (Galaxy XR) aarch64", fg)
    snap.fillRow(2, 7, "$ echo 'libghostty arrives in phase 2'", neonGreen)
    snap.fillRow(2, 8, "libghostty arrives in phase 2", fg)
    snap.fillRow(2, 9, "$ ssh nik@mac.local", neonGreen)
    snap.fillRow(2, 10, "(libssh2 lands in phase 3)", dim)

    snap.fillRow(2, 12, "next: JNI bridge → 4 panes → grab + move → QR pair", fg)

    snap.fillRow(2, rows - 2, "$ _", neonGreen)
    return snap.copy(cursorX = 5, cursorY = rows - 2)
}

/**
 * Writes [text] into [this] starting at ([col], [row]) using [fgArgb] (or the snapshot's default
 * fg when null). Phase-1-only builder helper — mutates the underlying arrays directly, which is
 * fine while we own the freshly-allocated [TerminalSnapshot.blank] result, but is **not** safe
 * once the snapshot is a libghostty round-trip shared with the renderer.
 */
private fun TerminalSnapshot.fillRow(col: Int, row: Int, text: String, fgArgb: Int? = null) {
    if (row !in 0 until rows) return
    val fg = fgArgb ?: defaultFgArgb
    var c = col
    var i = 0
    while (i < text.length && c < cols) {
        val cp = text.codePointAt(i)
        val idx = row * cols + c
        codepoints[idx] = cp
        this.fgArgb[idx] = fg
        c++
        i += Character.charCount(cp)
    }
}
