package com.ghosttyxr.app.terminal.ssh

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * libssh2-backed [SshTransport]. Wraps [NativeSshBridge] and translates
 * the engine's six-method contract into the libssh2 wire calls.
 *
 * Host-key policy is NOT decided here — [handshake] returns the
 * server-presented key and lets [VerifiedHostAccess] apply
 * [HostTrustPolicy.verify]. That keeps wire-level mechanism (this class)
 * cleanly separated from application policy ([VerifiedHostAccess]).
 *
 * Read/write goes through chuchu's `nativeIpcExchange` framing — version
 * + tag + length + payload, little-endian — because the Zig side
 * (`chuchu_ssh.zig`, being ported to `ghosttyxr_ssh.zig`) speaks that
 * same framing. Direct read/write external funs would require new Zig
 * exports we don't need.
 *
 * **PENDING: zig-side ghosttyxr_ssh.zig port (task #4 zig side, owned by
 * zig-native).** Until those `Java_com_ghosttyxr_app_terminal_ssh_NativeSshBridge_*`
 * symbols land in `libghosttyxr.so`, every method here throws
 * [UnsatisfiedLinkError] on first dispatch. The engine catches that and
 * emits [com.ghosttyxr.app.terminal.session.SessionState.Failed].
 */
class LibSsh2Transport(
    private val bridge: NativeSshBridge = NativeSshBridge(),
) : SshTransport {

    private var handle: Long = 0L

    override fun isAvailable(): Boolean = bridge.isLoaded()

    override fun handshake(host: String, port: Int, username: String): HostKey {
        require(bridge.isLoaded()) { "Native SSH unavailable: ${bridge.nativeStatus()}" }
        close()
        handle = bridge.nativeCreateSession()
        check(handle != 0L) { "nativeCreateSession returned 0" }

        requireNativeSuccess(
            bridge.nativeConnect(handle, host, port, username),
            "libssh2 connect failed",
        )

        val bytes = bridge.nativeGetHostKey(handle)
            ?: error("server presented no host key")
        val algorithm = bridge.nativeGetHostKeyAlgorithm(handle)
            ?: error("server presented no host key algorithm")
        return HostKey(bytes, algorithm)
    }

    override fun authenticate(user: String, password: String, key: SshKey?) {
        check(handle != 0L) { "authenticate before handshake" }
        if (key != null) {
            val ok = bridge.nativeAuthenticatePublicKeyMemory(
                handle,
                key.publicKeyOpenSsh.ifBlank { null },
                key.privateKeyPem,
                null,
            )
            if (ok) return
            // Server rejected the key — fall back to password rather than
            // hard-failing. The first-pairing flow always lands here.
        }
        requireNativeSuccess(
            bridge.nativeAuthenticatePassword(handle, password),
            "password auth rejected",
        )
    }

    override fun openShell(
        cols: Int,
        rows: Int,
        widthPx: Int,
        heightPx: Int,
        term: String,
    ) {
        check(handle != 0L) { "openShell before handshake" }
        requireNativeSuccess(
            bridge.nativeOpenShell(handle, cols, rows, widthPx, heightPx, term),
            "openShell failed",
        )
    }

    override fun openDirectTcpip(host: String, port: Int, sourceHost: String, sourcePort: Int) {
        check(handle != 0L) { "openDirectTcpip before handshake" }
        requireNativeSuccess(
            bridge.nativeOpenDirectTcpip(handle, host, port, sourceHost, sourcePort),
            "openDirectTcpip failed",
        )
    }

    override fun resize(cols: Int, rows: Int, widthPx: Int, heightPx: Int) {
        if (handle == 0L) return
        requireNativeSuccess(
            bridge.nativeResize(handle, cols, rows, widthPx, heightPx),
            "resize failed",
        )
    }

    override fun read(maxBytes: Int): ByteArray? {
        if (handle == 0L) return null
        val response = bridge.nativeIpcExchange(handle, Ipc.encodeRead(maxBytes)) ?: return null
        val frame = Ipc.decode(response)
        return when (frame.tag) {
            Ipc.TAG_DATA -> frame.payload
            Ipc.TAG_ERROR -> error(Ipc.parseError(frame.payload))
            else -> error("unexpected read tag ${frame.tag}")
        }
    }

    override fun write(data: ByteArray) {
        if (handle == 0L || data.isEmpty()) return
        var offset = 0
        var stallCount = 0
        // libssh2 channel_write may report a partial write — loop until
        // the full buffer drains. Stall guard mirrors chuchu's: 64
        // consecutive zero-byte writes (~256ms) means the channel is
        // wedged and we bail rather than spin forever.
        while (offset < data.size) {
            val chunk = if (offset == 0) data else data.copyOfRange(offset, data.size)
            val response = bridge.nativeIpcExchange(handle, Ipc.encodeWrite(chunk))
                ?: error(bridge.nativeGetLastError(handle) ?: "write failed")
            val frame = Ipc.decode(response)
            val written = when (frame.tag) {
                Ipc.TAG_ACK -> Ipc.parseAckWritten(frame.payload)
                Ipc.TAG_ERROR -> error(Ipc.parseError(frame.payload))
                else -> error("unexpected write tag ${frame.tag}")
            }
            if (written == 0) {
                stallCount += 1
                if (stallCount > 64) error("SSH write stalled")
                Thread.sleep(4)
                continue
            }
            stallCount = 0
            offset += written
        }
    }

    override fun sendKeepAlive(): Boolean {
        if (handle == 0L) return false
        // Maps to libssh2_keepalive_send on the Zig side. Like every other
        // method here, the native export is pending the ghosttyxr_ssh.zig
        // port (task #4): until TAG_KEEPALIVE is handled in the .so this
        // throws UnsatisfiedLinkError / errors out on first dispatch, and
        // the engine treats that as a dead channel and reconnects.
        val response = bridge.nativeIpcExchange(handle, Ipc.encodeKeepAlive())
            ?: error(bridge.nativeGetLastError(handle) ?: "keepalive failed")
        val frame = Ipc.decode(response)
        return when (frame.tag) {
            Ipc.TAG_ACK -> true
            Ipc.TAG_ERROR -> error(Ipc.parseError(frame.payload))
            else -> error("unexpected keepalive tag ${frame.tag}")
        }
    }

    override fun close() {
        if (handle == 0L) return
        try {
            bridge.nativeClose(handle)
            bridge.nativeDestroySession(handle)
        } finally {
            handle = 0L
        }
    }

    private fun requireNativeSuccess(success: Boolean, fallbackMessage: String) {
        if (!success) {
            error(bridge.nativeGetLastError(handle) ?: fallbackMessage)
        }
    }

    // IPC framing — must stay byte-identical to the Zig side of
    // ghosttyxr_ssh.zig (currently a port of chuchu_ssh.zig). Layout:
    //   u8 version | u8 tag | i32 len (LE) | bytes[len]
    private object Ipc {
        private const val VERSION: Byte = 1
        private const val TAG_WRITE: Byte = 1
        private const val TAG_READ: Byte = 2
        private const val TAG_KEEPALIVE: Byte = 3
        const val TAG_ACK: Byte = 100.toByte()
        const val TAG_DATA: Byte = 101.toByte()
        const val TAG_ERROR: Byte = 255.toByte()
        private const val HEADER_SIZE = 6

        data class Frame(val tag: Byte, val payload: ByteArray)

        fun encodeWrite(data: ByteArray): ByteArray = ByteBuffer
            .allocate(HEADER_SIZE + data.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(VERSION)
            .put(TAG_WRITE)
            .putInt(data.size)
            .put(data)
            .array()

        fun encodeRead(maxBytes: Int): ByteArray = ByteBuffer
            .allocate(HEADER_SIZE + 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(VERSION)
            .put(TAG_READ)
            .putInt(4)
            .putInt(maxBytes.coerceAtLeast(1))
            .array()

        // Zero-payload request; the Zig side runs libssh2_keepalive_send and
        // replies TAG_ACK (alive) or TAG_ERROR (dead channel).
        fun encodeKeepAlive(): ByteArray = ByteBuffer
            .allocate(HEADER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(VERSION)
            .put(TAG_KEEPALIVE)
            .putInt(0)
            .array()

        fun decode(frame: ByteArray): Frame {
            require(frame.size >= HEADER_SIZE) { "short IPC frame: ${frame.size}" }
            val buf = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
            val version = buf.get()
            require(version == VERSION) { "unsupported IPC version $version" }
            val tag = buf.get()
            val len = buf.int
            require(len >= 0 && frame.size == HEADER_SIZE + len) {
                "truncated IPC frame: declared=$len total=${frame.size}"
            }
            val payload = ByteArray(len)
            if (len > 0) buf.get(payload)
            return Frame(tag, payload)
        }

        fun parseAckWritten(payload: ByteArray): Int {
            require(payload.size == 4) { "bad ACK payload size ${payload.size}" }
            return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).int
        }

        fun parseError(payload: ByteArray): String =
            if (payload.isEmpty()) "native IPC error" else payload.toString(Charsets.UTF_8)
    }
}
