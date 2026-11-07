package com.ghosttyxr.app.terminal.input

import android.util.Log

/**
 * Receiver for [MappedKey]s produced by Compose key dispatch, controller buttons, and the
 * on-screen keyboard fallback.
 *
 * Phase 6 (this task): only the mapping side is wired. Sinks log dispatched events.
 * Phase 3 / task #4: `TerminalSessionEngine` will implement this by calling
 * `GhosttyBridge.nativeEncodeKey` and writing the resulting bytes to the SSH channel.
 */
interface TerminalInputSink {
    /** True once the underlying session engine is ready to accept input. */
    fun isReady(): Boolean

    /** Dispatch a single mapped key event. Implementations should be non-blocking. */
    fun onKey(key: MappedKey)
}

/**
 * Default sink used by Phase 6 demo wiring. Logs to logcat tag `TerminalInput` and counts
 * dispatches so a debug overlay can show that key handling is reaching this layer.
 *
 * Once a real session engine exists, swap this out at the call site.
 */
class LoggingInputSink(
    private val tag: String = "TerminalInput",
) : TerminalInputSink {
    @Volatile
    private var counter: Int = 0
    val dispatchCount: Int get() = counter

    override fun isReady(): Boolean = true

    override fun onKey(key: MappedKey) {
        counter += 1
        Log.d(tag, "dispatch #$counter $key")
    }
}
