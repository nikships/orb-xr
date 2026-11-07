package com.ghosttyxr.app.terminal

import java.nio.ByteBuffer

/**
 * JNI surface for the `libghosttyxr.so` Zig library. The native counterparts
 * are `export fn Java_com_ghosttyxr_app_terminal_GhosttyBridge_<methodName>`
 * in `ghosttyxr/zig-src/src/bridge/ghosttyxr_snapshot.zig`.
 *
 * Loading is wrapped in `runCatching` so a missing or broken `.so` surfaces
 * via [nativeStatus]/[isLoaded] instead of crashing the process at class init
 * — useful while task #1 (Zig cross-compile) is still landing and the .so
 * may be absent from `app/src/main/jniLibs/arm64-v8a/`.
 *
 * Snapshot binary layout (text + images) is shared with [TerminalSnapshot]:
 * see its `fromByteBuffer` / `parseImages` for the wire format.
 */
class GhosttyBridge {
    companion object {
        private val loadError: Throwable? = runCatching {
            System.loadLibrary("ghosttyxr")
        }.exceptionOrNull()
    }

    fun nativeStatus(): String {
        val error = loadError ?: return "loaded"
        val message = error.message?.takeIf { it.isNotBlank() } ?: "unknown"
        return "not loaded (${error::class.simpleName}: $message)"
    }

    fun isLoaded(): Boolean = loadError == null

    external fun nativeVersion(): String

    // Lifecycle.
    external fun nativeCreate(cols: Int, rows: Int, maxScrollback: Int): Long
    external fun nativeDestroy(handle: Long)

    // Remote bytes (SSH/PTY → libghostty stream parser). `data` is the raw
    // payload received from the SSH channel; libghostty parses ANSI/CSI/OSC
    // and updates its internal grid + scrollback + kitty image state.
    external fun nativeWriteRemote(handle: Long, data: ByteArray)

    // Geometry. cellW/cellH are pixel dimensions of a single glyph cell —
    // needed by libghostty for kitty image placement math.
    external fun nativeResize(handle: Long, cols: Int, rows: Int, cellW: Int, cellH: Int)

    // Scrollback navigation. Positive delta = newer content, negative = older.
    // When the active screen is the alt screen (vim/tmux/less) or mouse
    // reporting is on, the Zig side translates this to mouse-wheel escape
    // sequences and pushes them onto the pty write queue instead.
    external fun nativeScroll(handle: Long, delta: Int)
    external fun nativeScrollToActive(handle: Long)

    // Direct ByteBuffer over the Zig-owned snapshot arena. Caller must parse
    // before the next snapshot call — buffer contents are reused on the
    // native side.
    external fun nativeSnapshot(handle: Long): ByteBuffer
    external fun nativeSnapshotImages(handle: Long): ByteBuffer
    external fun nativeIsImageLoading(handle: Long): Boolean

    // OSC-driven metadata. `null` means "no change since last poll" — these
    // are dirty-bit channels, not snapshots. Treat absence as "keep current."
    external fun nativePollTitle(handle: Long): String?
    external fun nativePollPwd(handle: Long): String?
    external fun nativeDrainBellCount(handle: Long): Int

    // Colors. scheme: 0=light, 1=dark. paletteRgb must be 256*3 bytes
    // (R,G,B,R,G,B,...) — empty / null skips the palette override and
    // libghostty falls back to its built-in default palette.
    external fun nativeSetColorScheme(handle: Long, scheme: Int)
    external fun nativeSetDefaultColors(
        handle: Long,
        fgRgb: IntArray?,
        bgRgb: IntArray?,
        cursorRgb: IntArray?,
        paletteRgb: ByteArray?,
    )

    // Input encoding. Outputs are escape-sequence byte strings ready to write
    // to the SSH channel. Empty array = "no bytes to send" (e.g. a focus event
    // when the terminal hasn't enabled focus reporting).
    //
    // `key` is `ghostty.input.Key` ordinal, `mods` is the
    // `ghostty.input.KeyMods` bit-packed backing int, `action` is the
    // `ghostty.input.KeyAction` ordinal (press/release/repeat). `utf8` is the
    // raw text for printable keys (used by legacy / non-Kitty-keyboard hosts).
    external fun nativeEncodeKey(
        handle: Long,
        key: Int,
        cp: Int,
        mods: Int,
        action: Int,
        utf8: String?,
    ): ByteArray?

    // Mouse encoding requires a prior nativeSetMouseEncodingSize() call so
    // the Zig side knows how to translate float pixel coords into cell
    // coordinates; without it, encode returns an empty array.
    external fun nativeSetMouseEncodingSize(
        handle: Long,
        screenWidth: Int,
        screenHeight: Int,
        cellWidth: Int,
        cellHeight: Int,
        paddingTop: Int,
        paddingBottom: Int,
        paddingLeft: Int,
        paddingRight: Int,
    )
    external fun nativeEncodeMouse(
        handle: Long,
        action: Int,
        button: Int,
        mods: Int,
        x: Float,
        y: Float,
        anyButtonPressed: Boolean,
        trackLastCell: Boolean,
    ): ByteArray?

    external fun nativeEncodeFocus(handle: Long, focused: Boolean): ByteArray?

    // PTY writes are everything libghostty wants to emit back to the host:
    // OSC replies, DA queries, mouse-as-scroll bytes from nativeScroll, etc.
    // Drain on every read-loop tick after writing remote bytes and forward
    // the result over SSH.
    external fun nativeDrainPtyWrites(handle: Long): ByteArray
}
