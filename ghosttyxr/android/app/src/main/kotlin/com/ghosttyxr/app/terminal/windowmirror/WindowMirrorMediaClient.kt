package com.ghosttyxr.app.terminal.windowmirror

import com.ghosttyxr.app.util.GxrLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val TAG = "WindowMirrorMedia"
private const val CONNECT_TIMEOUT_MS = 5_000
private const val READ_BUFFER_BYTES = 64 * 1024
private const val GCM_TAG_BITS = 128

/**
 * Direct LAN TCP client for mirror video.
 *
 * Reads length-prefixed slices, reassembles each frame, and hands complete access
 * units straight to the decoder. There is deliberately **no jitter buffer**: a
 * slice is decoded the moment it lands. Buffering to smooth jitter is exactly what
 * made the previous transport drift later and later until it froze; here a slow
 * network shows up as a dropped frame, which self-corrects, rather than as
 * permanent accumulated delay.
 */
class WindowMirrorMediaClient(
    private val decoder: WindowVideoDecoder,
    private val onKeyFrameNeeded: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private var socket: Socket? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Bumped by every [connect] / [disconnect]. A superseded run must not report
     * its own teardown as an error or clear the flags of the run that replaced
     * it — the Mac re-announces the media session whenever the codec or cipher
     * mode changes, so replacing a live connection is routine, not a failure.
     */
    private var generation = 0L

    // Frame reassembly. Slices of one frame arrive in order on a single TCP
    // stream, so a plain accumulator is sufficient.
    private val pending = mutableListOf<ByteArray>()
    private var pendingKeyFrame = false
    private var pendingGap = false
    private var expectedSequence = 0L

    @Volatile
    var isConnected: Boolean = false
        private set

    @Synchronized
    fun connect(session: WindowMirrorMessage.MediaSession) {
        val myGeneration = teardown()
        job = scope.launch {
            val wasConnected = runCatching { run(myGeneration, session) }
                .onFailure { error ->
                    if (isSuperseded(myGeneration)) return@onFailure
                    if (isConnected) {
                        onError("Mirror video connection lost: ${error.message}")
                    } else {
                        onError("Could not open mirror video connection: ${error.message}")
                    }
                }
            if (wasConnected.isSuccess || !isSuperseded(myGeneration)) {
                markDisconnected(myGeneration)
            }
        }
    }

    @Synchronized
    fun disconnect() {
        teardown()
    }

    /** Invalidates the current run and closes its socket. Returns the new generation. */
    @Synchronized
    private fun teardown(): Long {
        generation++
        job?.cancel()
        job = null
        runCatching { socket?.close() }
        socket = null
        isConnected = false
        resetReassembly()
        return generation
    }

    @Synchronized
    private fun isSuperseded(myGeneration: Long): Boolean = generation != myGeneration

    @Synchronized
    private fun markDisconnected(myGeneration: Long) {
        if (generation != myGeneration) return
        isConnected = false
    }

    @Synchronized
    private fun publish(myGeneration: Long, socket: Socket): Boolean {
        if (generation != myGeneration) return false
        this.socket = socket
        return true
    }

    private suspend fun run(myGeneration: Long, session: WindowMirrorMessage.MediaSession) {
        val cipherKey = if (session.encrypted) {
            SecretKeySpec(
                MessageDigest.getInstance("SHA-256").digest(session.token.toByteArray()),
                "AES",
            )
        } else {
            null
        }

        val socket = withContext(Dispatchers.IO) {
            Socket().apply {
                // Nagle off: slices are small and must not wait for a partner.
                tcpNoDelay = true
                connect(InetSocketAddress(session.host, session.port), CONNECT_TIMEOUT_MS)
            }
        }
        // A connect() landing while this socket was being dialled owns the client
        // now; close ours instead of overwriting its field.
        if (!publish(myGeneration, socket)) {
            runCatching { socket.close() }
            return
        }
        decoder.setCodec(session.codec)
        resetReassembly()

        val output = socket.getOutputStream()
        withContext(Dispatchers.IO) {
            output.write(WindowMirrorProtocol.mediaHelloFrame(session.token))
            output.flush()
        }
        isConnected = true
        GxrLog.i(TAG, "mirror video connected to ${session.host}:${session.port} (${session.codec})")

        val decoderState = WindowMirrorFrameDecoder()
        val input = socket.getInputStream()
        val buffer = ByteArray(READ_BUFFER_BYTES)
        while (true) {
            val read = readOrThrow(input, buffer)
            if (read < 0) break
            if (read == 0) continue
            for (frame in decoderState.accept(buffer, read)) {
                handleFrame(frame, cipherKey)
            }
        }
        isConnected = false
    }

    private fun readOrThrow(input: InputStream, buffer: ByteArray): Int = input.read(buffer)

    private fun handleFrame(frame: WindowMirrorFrame, cipherKey: SecretKeySpec?) {
        if (frame.type != WindowMirrorFrameType.VideoSlice) return
        val slice = runCatching { decodeWindowMirrorVideoSlice(frame.payload) }
            .getOrElse { error ->
                onError("Malformed mirror video slice: ${error.message}")
                return
            }

        // A sequence gap means slices were lost or reordered. TCP does not lose
        // data, so this only happens when the Mac deliberately dropped slices to
        // avoid queueing — the reference chain is broken and needs a keyframe.
        if (expectedSequence != 0L && slice.sequence != expectedSequence) {
            GxrLog.d(TAG) { "slice gap: expected $expectedSequence got ${slice.sequence}" }
            pendingGap = true
            pending.clear()
            requestKeyFrame("gap")
        }
        expectedSequence = slice.sequence + 1

        val bitstream = if (cipherKey != null) {
            decrypt(slice.bitstream, cipherKey, slice.sequence) ?: run {
                onError("Mirror video decryption failed.")
                pendingGap = true
                pending.clear()
                requestKeyFrame("decrypt")
                return
            }
        } else {
            slice.bitstream
        }

        // Discard slices belonging to a partially-lost frame; only a fresh frame
        // boundary is a safe place to resume.
        if (pendingGap && !slice.isKeyFrame) {
            if (slice.isFrameEnd) {
                pendingGap = false
                pending.clear()
            }
            return
        }

        if (slice.sliceIndex == 0) {
            pending.clear()
            pendingKeyFrame = slice.isKeyFrame
        }
        pending.add(bitstream)

        if (!slice.isFrameEnd) return

        val total = pending.sumOf { it.size }
        val assembled = ByteArray(total)
        var offset = 0
        for (part in pending) {
            part.copyInto(assembled, offset)
            offset += part.size
        }
        pending.clear()

        val hadGap = pendingGap
        pendingGap = false
        decoder.queue(
            WindowMirrorVideoFrame(
                ptsUs = slice.ptsUs,
                width = slice.width,
                height = slice.height,
                keyFrame = pendingKeyFrame || slice.isKeyFrame,
                hadGap = hadGap,
                data = assembled,
            ),
        )
        if (decoder.needsKeyFrame) {
            requestKeyFrame("decoder-resync")
        }
    }

    private fun decrypt(payload: ByteArray, key: SecretKeySpec, sequence: Long): ByteArray? {
        // 12-byte nonce: 4 zero bytes then the big-endian sequence number, matching
        // the Mac. The sequence never repeats under one session key.
        val nonce = ByteArray(12)
        ByteBuffer.wrap(nonce).order(ByteOrder.BIG_ENDIAN).putInt(0).putLong(sequence)
        return runCatching {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
                doFinal(payload)
            }
        }.getOrNull()
    }

    private var lastKeyFrameRequestMs = 0L

    private fun requestKeyFrame(reason: String) {
        // Rate limit: one request per 200ms. An IDR takes time to arrive and
        // spamming requests would turn one loss into an IDR storm.
        val now = System.currentTimeMillis()
        if (now - lastKeyFrameRequestMs < 200) return
        lastKeyFrameRequestMs = now
        onKeyFrameNeeded(reason)
    }

    private fun resetReassembly() {
        pending.clear()
        pendingKeyFrame = false
        pendingGap = false
        expectedSequence = 0
    }
}
