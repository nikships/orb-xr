package com.ghosttyxr.app.terminal.hostkeyboard

enum class HostKeyboardRelayStatus {
    WaitingForPairing,
    Connecting,
    WaitingForCompanion,
    Connected,
    Capturing,
    PermissionNeeded,
    Error,
}

data class HostKeyboardRelayState(
    val status: HostKeyboardRelayStatus = HostKeyboardRelayStatus.WaitingForPairing,
    val detail: String? = null,
) {
    val isCapturing: Boolean
        get() = status == HostKeyboardRelayStatus.Capturing
}
