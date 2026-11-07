package com.ghosttyxr.app.terminal.ssh

/**
 * JNI surface for libssh2 ops inside `libghosttyxr.so`. Mirrors chuchu's
 * `NativeSshBridge.kt` — same Zig export names, same JVM signatures.
 *
 * Loaded via `System.loadLibrary("ghosttyxr")` (idempotent — `GhosttyBridge`
 * already triggers the load) so the same .so backs both the terminal and
 * SSH JNI surfaces. The Zig side exports are added in task #4; until then
 * `nativeStatus()` returns `not loaded` on the host JVM and the external
 * fns will throw `UnsatisfiedLinkError` at first call.
 */
class NativeSshBridge {
    companion object {
        private val loadError: Throwable? = runCatching {
            System.loadLibrary("ghosttyxr")
        }.exceptionOrNull()
    }

    fun isLoaded(): Boolean = loadError == null

    fun nativeStatus(): String {
        val error = loadError ?: return "loaded"
        val message = error.message?.takeIf { it.isNotBlank() } ?: "unknown"
        return "not loaded (${error::class.simpleName}: $message)"
    }

    // Session lifecycle. Handles are opaque pointers — pass them back as-is.
    external fun nativeCreateSession(): Long
    external fun nativeDestroySession(handle: Long)

    // Connection + diagnostics.
    external fun nativeConnect(handle: Long, host: String, port: Int, username: String): Boolean
    external fun nativeGetLastError(handle: Long): String?

    // Host key inspection. Called after `nativeConnect` returns true and
    // before any auth — pass results through [HostTrustPolicy.verify] to
    // decide whether to proceed.
    external fun nativeGetHostKey(handle: Long): ByteArray?
    external fun nativeGetHostKeyAlgorithm(handle: Long): String?

    // Auth. Try in this order:
    //   1. `nativeAuthenticateNone` — probes whether the server has zero-auth
    //      methods configured. Always returns false for macOS Remote Login,
    //      but the call also populates the list of acceptable methods.
    //   2. `nativeAuthenticatePublicKeyMemory` — preferred (after first
    //      QR pairing). Pass an in-memory keypair from [SshKey].
    //   3. `nativeAuthenticatePassword` — fallback / first-time pairing.
    external fun nativeAuthenticateNone(handle: Long): Boolean
    external fun nativeAuthenticatePassword(handle: Long, password: String): Boolean
    external fun nativeAuthenticatePublicKeyMemory(
        handle: Long,
        publicKeyOpenSsh: String?,
        privateKeyPem: String,
        passphrase: String?,
    ): Boolean

    // Channel (PTY shell). After this returns true, all stream I/O goes
    // through the libghostty bridge — `nativeWriteRemote` for shell→
    // terminal bytes, `nativeDrainPtyWrites` for terminal→shell bytes.
    // Width/height in pixels are advisory; libssh2 forwards them to the
    // remote shell via the standard `pty-req` size hints.
    external fun nativeOpenShell(
        handle: Long,
        cols: Int,
        rows: Int,
        widthPx: Int,
        heightPx: Int,
        term: String,
    ): Boolean

    external fun nativeOpenDirectTcpip(
        handle: Long,
        host: String,
        port: Int,
        sourceHost: String,
        sourcePort: Int,
    ): Boolean

    external fun nativeResize(
        handle: Long,
        cols: Int,
        rows: Int,
        widthPx: Int,
        heightPx: Int,
    ): Boolean

    // Length-prefixed request/response over the SSH channel. Currently used
    // by chuchu for an in-band IPC protocol with a companion daemon. Kept
    // for now in case Orb XR's Mac companion app reuses the same shape;
    // can drop if unused.
    external fun nativeIpcExchange(handle: Long, request: ByteArray): ByteArray?

    // SFTP — used during initial pairing to drop the headset's public key
    // into the Mac's `~/.ssh/authorized_keys`. Not used after pairing.
    external fun nativeSftpInit(handle: Long): Boolean
    external fun nativeSftpListDirectory(handle: Long, path: String): Array<String>?
    external fun nativeSftpRealpath(handle: Long, path: String): String?
    external fun nativeSftpOpenWrite(handle: Long, path: String): Boolean
    external fun nativeSftpWriteChunk(handle: Long, data: ByteArray): Int
    external fun nativeSftpCloseWrite(handle: Long): Boolean

    external fun nativeClose(handle: Long)
}
