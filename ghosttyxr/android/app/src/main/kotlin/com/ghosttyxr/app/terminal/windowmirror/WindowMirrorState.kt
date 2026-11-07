package com.ghosttyxr.app.terminal.windowmirror

enum class WindowMirrorStatus {
    WaitingForPairing,
    Connecting,
    WaitingForCompanion,
    WaitingForWindow,
    Streaming,
    PermissionNeeded,
    Error,
}

enum class WindowMirrorTransport {
    SshTunnel,

    /**
     * Control and input over the SSH tunnel; video over a direct LAN TCP socket.
     * The split exists because the tunnel does one synchronous JNI round-trip per
     * read and cannot carry video bitrates.
     */
    DirectTcp,
}

/** Who currently owns the physical Mac mouse in Symbiosis (full-screen share) mode. */
enum class SymbiosisPointerOwner {
    Mac,
    Xr,
}

/**
 * Symbiosis pointer events streamed from the Mac while XR owns the pointer.
 *
 * [seq] is a per-pointer-session sequence number stamped by the Mac (1 = the
 * handoff itself, then +1 per event). Consumers use it to detect dropped frames
 * (network loss or buffer overflow) and cancel any in-flight press rather than
 * stranding a drag. A seq of 0 means the sender predates sequencing; tracking
 * is disabled for that session.
 */
sealed interface SymbiosisPointerEvent {
    val seq: Long

    data class Handoff(override val seq: Long, val edge: String, val y: Float) : SymbiosisPointerEvent
    data class Delta(override val seq: Long, val dx: Float, val dy: Float) : SymbiosisPointerEvent
    data class Button(override val seq: Long, val button: String, val action: String) : SymbiosisPointerEvent
    data class Scroll(override val seq: Long, val dx: Float, val dy: Float) : SymbiosisPointerEvent
}

data class WindowMirrorMetadata(
    val id: Long,
    val title: String,
    val app: String,
    val width: Int,
    val height: Int,
    val scale: Double,
    val interactive: Boolean,
    val kind: String = "window",
    val codec: String = "h264",
) {
    val displayTitle: String
        get() = listOf(app, title).filter { it.isNotBlank() }.joinToString(" · ")

    val isDisplayShare: Boolean
        get() = kind == "display"
}

data class WindowMirrorState(
    val status: WindowMirrorStatus = WindowMirrorStatus.WaitingForPairing,
    val detail: String? = null,
    val metadata: WindowMirrorMetadata? = null,
    val interactive: Boolean = false,
    val pointerOwner: SymbiosisPointerOwner = SymbiosisPointerOwner.Mac,
    val transport: WindowMirrorTransport = WindowMirrorTransport.SshTunnel,
    val stats: WindowMirrorStats = WindowMirrorStats(),
) {
    val hasWindow: Boolean
        get() = metadata != null && status == WindowMirrorStatus.Streaming

    val isDisplayShare: Boolean
        get() = metadata?.isDisplayShare == true
}

data class WindowMirrorStats(
    val receivedFramesPerSecond: Double = 0.0,
    val receivedBitrateMbps: Double = 0.0,
    /**
     * Capture-to-composite latency measured on this device. Unlike the old
     * jitter-buffer delay this has no queue behind it, so a healthy stream keeps
     * it flat rather than letting it creep upward — the number worth watching.
     */
    val endToEndLatencyMs: Double = 0.0,
    val keyFrameRequests: Int = 0,
    val framesDropped: Int = 0,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val codec: String = "H264",
)
