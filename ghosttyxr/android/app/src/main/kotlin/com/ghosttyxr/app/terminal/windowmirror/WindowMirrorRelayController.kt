package com.ghosttyxr.app.terminal.windowmirror

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.KeyEvent
import com.ghosttyxr.app.terminal.ssh.ChannelGenerationGuard
import com.ghosttyxr.app.terminal.ssh.EstablishedTunnel
import com.ghosttyxr.app.terminal.ssh.PairedMac
import com.ghosttyxr.app.terminal.ssh.PairedMacStore
import com.ghosttyxr.app.terminal.ssh.SshKey
import com.ghosttyxr.app.terminal.ssh.VerifiedHostAccess
import com.ghosttyxr.app.util.GxrLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.view.Surface
import kotlin.math.min

private const val TAG = "WindowMirrorRelay"
private const val HEARTBEAT_INTERVAL_MS = 1_000L
private const val WATCHDOG_TIMEOUT_MS = 3_000L
private const val IDLE_READ_DELAY_MS = 1L
private const val READ_CHUNK_BYTES = 64 * 1024
private const val LATENCY_REPORT_INTERVAL_MS = 1_000L

class WindowMirrorRelayController(
    context: Context,
    private val scope: CoroutineScope,
    private val hostAccess: VerifiedHostAccess,
) {
    private val appContext = context.applicationContext
    private val controlOutbound = Channel<ByteArray>(Channel.UNLIMITED)
    private var job: Job? = null
    private val tunnelGuard = ChannelGenerationGuard<EstablishedTunnel>()
    private val decoder = WindowVideoDecoder()
    private val media = WindowMirrorMediaClient(
        decoder = decoder,
        onKeyFrameNeeded = { reason ->
            controlOutbound.trySend(WindowMirrorProtocol.keyFrameRequestFrame(reason))
        },
        onError = { message -> reportError(message) },
    )
    @Volatile private var runningRequested = false
    private var lastReportedFrameCount = 0L

    private val _state = MutableStateFlow(WindowMirrorState())
    val state: StateFlow<WindowMirrorState> = _state.asStateFlow()

    private val _pointerEvents = MutableSharedFlow<SymbiosisPointerEvent>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val pointerEvents: SharedFlow<SymbiosisPointerEvent> = _pointerEvents.asSharedFlow()

    fun start() {
        runningRequested = true
        if (job?.isActive == true) return
        val myGeneration = tunnelGuard.newGeneration()
        job = scope.launch(Dispatchers.IO) {
            runLoop(myGeneration)
        }
    }

    fun stop() {
        runningRequested = false
        job?.cancel()
        job = null
        invalidateConnection()
        _state.value = WindowMirrorState(WindowMirrorStatus.WaitingForPairing)
    }

    fun release() {
        stop()
        decoder.release()
    }

    fun restart() {
        val shouldRestart = runningRequested
        job?.cancel()
        job = null
        invalidateConnection()
        if (shouldRestart) start() else _state.value = WindowMirrorState()
    }

    fun showLocalNetworkPermissionRequired() {
        _state.value = WindowMirrorState(
            status = WindowMirrorStatus.PermissionNeeded,
            detail = "Allow local network access to connect to the Mac companion.",
            transport = WindowMirrorTransport.DirectTcp,
        )
    }

    /** Attach the pane's SurfaceView output. Ownership stays with the caller. */
    fun setSurface(surface: Surface?) {
        decoder.setSurface(surface)
    }

    fun closeMirror() {
        sendControlFrame(WindowMirrorProtocol.closeFrame("Closed from XR."))
        _state.update { current ->
            WindowMirrorState(
                status = WindowMirrorStatus.WaitingForWindow,
                detail = "Mirror closed. Choose Bring Window into XR from the Mac companion.",
                transport = current.transport,
                stats = current.stats,
            )
        }
    }

    fun sendPointer(action: String, x: Float, y: Float) {
        if (!_state.value.interactive) return
        sendControlFrame(WindowMirrorProtocol.pointerFrame(action, x, y))
    }

    fun sendScroll(x: Float, y: Float, dx: Float, dy: Float) {
        if (!_state.value.interactive) return
        sendControlFrame(WindowMirrorProtocol.scrollFrame(x, y, dx, dy))
    }

    fun sendKey(event: KeyEvent): Boolean {
        if (!_state.value.interactive) return false
        val frame = WindowMirrorProtocol.keyFrame(event) ?: return false
        sendControlFrame(frame)
        return true
    }

    fun sendPointerReturn(x: Float, y: Float) {
        while (true) {
            val current = _state.value
            if (current.pointerOwner != SymbiosisPointerOwner.Xr) return
            val returned = current.copy(pointerOwner = SymbiosisPointerOwner.Mac)
            if (_state.compareAndSet(current, returned)) {
                sendControlFrame(WindowMirrorProtocol.pointerReturnFrame(x, y))
                return
            }
        }
    }

    private suspend fun runLoop(myGeneration: Long) {
        var retryDelayMs = 500L
        while (currentCoroutineContext().isActive) {
            val pair = loadPairedMac()
            if (pair == null) {
                _state.value = WindowMirrorState(
                    WindowMirrorStatus.WaitingForPairing,
                    "Pair a Mac to enable window mirroring.",
                )
                delay(1_000L)
                continue
            }

            val (mac, key) = pair
            val outcome = runCatching {
                _state.value = WindowMirrorState(
                    WindowMirrorStatus.Connecting,
                    "Opening a secure mirror connection to ${mac.host}.",
                    transport = WindowMirrorTransport.DirectTcp,
                )
                connectAndPump(mac, key, myGeneration)
            }

            val failure = outcome.exceptionOrNull()
            if (failure is CancellationException) throw failure
            if (failure != null) {
                GxrLog.w(TAG, "mirror relay stopped: ${failure.message}")
                val message = failure.message.orEmpty()
                _state.value = WindowMirrorState(
                    status = statusForFailure(message),
                    detail = message.ifBlank { failure::class.simpleName },
                    transport = WindowMirrorTransport.DirectTcp,
                )
            }

            releaseConnection(myGeneration)
            delay(retryDelayMs)
            retryDelayMs = min(retryDelayMs * 2, 5_000L)
        }
    }

    private fun statusForFailure(message: String): WindowMirrorStatus {
        val companionHints = listOf("direct-tcpip", "Connection refused", "open failed")
        return if (companionHints.any { message.contains(it, ignoreCase = true) }) {
            WindowMirrorStatus.WaitingForCompanion
        } else {
            WindowMirrorStatus.Error
        }
    }

    /** End-of-cycle cleanup: release only if [myGeneration] is still current. */
    private fun releaseConnection(myGeneration: Long) = teardownConnection { tunnelGuard.releaseBoth(myGeneration) }

    /** Stop/restart entry point: unconditionally invalidate and hand back whatever is held. */
    private fun invalidateConnection() = teardownConnection { tunnelGuard.invalidateBoth() }

    private fun teardownConnection(takeBoth: () -> Pair<EstablishedTunnel?, AutoCloseable?>) {
        while (controlOutbound.tryReceive().isSuccess) {
            // Drop frames queued for the connection being torn down.
        }
        val (tunnel, session) = takeBoth()
        runCatching { tunnel?.close() }
        runCatching { session?.close() }
    }

    private fun reportError(message: String) {
        GxrLog.w(TAG, message)
        _state.update {
            it.copy(
                status = WindowMirrorStatus.Error,
                detail = message,
                transport = WindowMirrorTransport.DirectTcp,
            )
        }
    }

    /** Queues a control frame for the SSH-tunnelled connection. */
    private fun sendControlFrame(frame: ByteArray) {
        controlOutbound.trySend(frame)
    }

    private suspend fun loadPairedMac(): Pair<PairedMac, SshKey>? = withContext(Dispatchers.IO) {
        PairedMacStore.init(appContext)
        PairedMacStore.load()
    }

    private suspend fun connectAndPump(mac: PairedMac, key: SshKey, myGeneration: Long) {
        val payload = mac.toPayload()
        // Blocking — handshake/verify/authenticate/open all run before this
        // returns, so a stop/restart on another thread may already have
        // invalidated [myGeneration] by the time we get here.
        val connection = hostAccess.connectTunnel(
            host = payload.host,
            port = payload.port,
            username = payload.user,
            expectedFingerprint = payload.fingerprint,
            password = "",
            key = key,
            targetHost = "127.0.0.1",
            targetPort = WINDOW_MIRROR_RELAY_PORT,
        )
        if (!tunnelGuard.claim(myGeneration, connection)) {
            // Shutdown/restart beat us to it — nobody else will ever see
            // this connection, so close it immediately instead of leaking it.
            runCatching { connection.close() }
            return
        }
        // The media socket is owned by the same generation as the tunnel, so a
        // stop/restart landing mid-setup tears both down together rather than
        // leaving a live video connection behind an invalidated control channel.
        val attached = tunnelGuard.attachSecondary(
            myGeneration,
            AutoCloseable { media.disconnect() },
        ) {
            // Nothing to publish: `media` is a stable field.
        }
        if (!attached) {
            return
        }

        connection.write(WindowMirrorProtocol.helloFrame())
        connection.write(
            WindowMirrorProtocol.codecSupportFrame(
                codecs = supportedCodecs(),
                lowLatency = true,
            ),
        )
        _state.value = WindowMirrorState(
            status = WindowMirrorStatus.Connecting,
            detail = "Opening the mirror video connection.",
            transport = WindowMirrorTransport.DirectTcp,
        )
        pumpControl(connection)
    }

    /**
     * Codecs this device can actually decode. HEVC is only offered when a
     * hardware decoder exists — a software HEVC path cannot keep up at 90fps and
     * would be worse than H.264.
     */
    private fun supportedCodecs(): List<String> {
        val codecs = mutableListOf("h264")
        val hasHevc = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                !info.isEncoder &&
                    info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) } &&
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        info.isHardwareAccelerated
                    } else {
                        isLegacyHardwareCodec(info.name)
                    }
            }
        }.getOrDefault(false)
        if (hasHevc) codecs.add("hevc")
        return codecs
    }

    private suspend fun pumpControl(connection: EstablishedTunnel) {
        val frames = WindowMirrorFrameDecoder()
        var lastInboundAt = System.currentTimeMillis()
        var lastHeartbeatAt = 0L
        var lastLatencyReportAt = 0L
        try {
            while (currentCoroutineContext().isActive) {
                drainControlOutbound(connection)
                val chunk = connection.read(READ_CHUNK_BYTES)
                    ?: error("window mirror control channel closed")
                val now = System.currentTimeMillis()
                if (chunk.isNotEmpty()) {
                    lastInboundAt = now
                    frames.accept(chunk).forEach { handleControlFrame(it, connection) }
                }
                drainControlOutbound(connection)
                if (now - lastHeartbeatAt >= HEARTBEAT_INTERVAL_MS) {
                    connection.write(WindowMirrorProtocol.pingFrame())
                    lastHeartbeatAt = now
                }
                if (now - lastLatencyReportAt >= LATENCY_REPORT_INTERVAL_MS) {
                    reportLatency(connection, now - lastLatencyReportAt)
                    lastLatencyReportAt = now
                }
                if (now - lastInboundAt > WATCHDOG_TIMEOUT_MS) {
                    error("window mirror control watchdog timed out")
                }
                if (chunk.isEmpty()) delay(IDLE_READ_DELAY_MS)
            }
        } finally {
            frames.reset()
            media.disconnect()
        }
    }

    /**
     * Sends the measured decode rate and latency back to the Mac, which steers
     * its encoder bitrate from it. Reporting real receiver-side numbers is what
     * lets the sender adapt without guessing from RTT.
     */
    private fun reportLatency(connection: EstablishedTunnel, elapsedMs: Long) {
        val frames = decoder.decodedFrameCount
        val delta = frames - lastReportedFrameCount
        lastReportedFrameCount = frames
        val fps = if (elapsedMs > 0) delta * 1000.0 / elapsedMs else 0.0
        val latencyMs = _state.value.stats.endToEndLatencyMs
        connection.write(
            WindowMirrorProtocol.latencyReportFrame(
                endToEndMs = latencyMs,
                decodedFps = fps,
                decodeQueueDepth = 0,
            ),
        )
        _state.update {
            it.copy(stats = it.stats.copy(receivedFramesPerSecond = fps))
        }
    }

    private fun drainControlOutbound(connection: EstablishedTunnel) {
        while (true) {
            val frame = controlOutbound.tryReceive().getOrNull() ?: return
            connection.write(frame)
        }
    }

    private fun handleControlFrame(frame: WindowMirrorFrame, connection: EstablishedTunnel) {
        when (val message = WindowMirrorProtocol.parse(frame)) {
            is WindowMirrorMessage.Hello -> {
                check(WindowMirrorProtocol.isFeatureCompatible(message.version)) {
                    "Mac companion window mirror protocol is too old."
                }
            }
            is WindowMirrorMessage.MediaSession -> {
                GxrLog.i(TAG, "mirror media offered at ${message.host}:${message.port} (${message.codec})")
                _state.update {
                    it.copy(
                        stats = it.stats.copy(codec = message.codec.uppercase()),
                        transport = WindowMirrorTransport.DirectTcp,
                    )
                }
                media.connect(message)
            }
            WindowMirrorMessage.Ping -> connection.write(WindowMirrorProtocol.pongFrame())
            WindowMirrorMessage.Pong, WindowMirrorMessage.Ready, WindowMirrorMessage.Reset -> Unit
            is WindowMirrorMessage.Metadata -> {
                val previousMetadataId = _state.value.metadata?.id
                _state.update { previous ->
                    WindowMirrorState(
                        status = WindowMirrorStatus.Streaming,
                        detail = "Mirroring ${message.metadata.displayTitle}.",
                        metadata = message.metadata,
                        interactive = message.metadata.interactive,
                        pointerOwner = if (previous.metadata?.id == message.metadata.id) {
                            previous.pointerOwner
                        } else {
                            SymbiosisPointerOwner.Mac
                        },
                        transport = WindowMirrorTransport.DirectTcp,
                        stats = previous.stats,
                    )
                }
                if (previousMetadataId != message.metadata.id) {
                    GxrLog.d(TAG) { "window mirror metadata: ${message.metadata.displayTitle}" }
                }
            }
            is WindowMirrorMessage.StreamState -> updateStreamState(message)
            is WindowMirrorMessage.PointerHandoff -> {
                _state.update { it.copy(pointerOwner = SymbiosisPointerOwner.Xr) }
                _pointerEvents.tryEmit(
                    SymbiosisPointerEvent.Handoff(message.seq, message.edge, message.y),
                )
            }
            WindowMirrorMessage.PointerReturned ->
                _state.update { it.copy(pointerOwner = SymbiosisPointerOwner.Mac) }
            is WindowMirrorMessage.MouseDelta ->
                _pointerEvents.tryEmit(SymbiosisPointerEvent.Delta(message.seq, message.dx, message.dy))
            is WindowMirrorMessage.MouseButton ->
                _pointerEvents.tryEmit(SymbiosisPointerEvent.Button(message.seq, message.button, message.action))
            is WindowMirrorMessage.MouseScroll ->
                _pointerEvents.tryEmit(SymbiosisPointerEvent.Scroll(message.seq, message.dx, message.dy))
            is WindowMirrorMessage.Close -> {
                _state.update { current ->
                    WindowMirrorState(
                        status = WindowMirrorStatus.WaitingForWindow,
                        detail = message.reason ?: "Window mirror closed.",
                        transport = WindowMirrorTransport.DirectTcp,
                        stats = current.stats,
                    )
                }
            }
            else -> Unit
        }
    }

    private fun updateStreamState(message: WindowMirrorMessage.StreamState) {
        _state.update { current ->
            val mapped = when (message.status) {
                "streaming" ->
                    if (current.metadata != null) WindowMirrorStatus.Streaming else WindowMirrorStatus.WaitingForWindow
                "permission" -> WindowMirrorStatus.PermissionNeeded
                "error" -> WindowMirrorStatus.Error
                else -> WindowMirrorStatus.WaitingForWindow
            }
            WindowMirrorState(
                status = mapped,
                detail = message.detail,
                metadata = if (mapped == WindowMirrorStatus.Streaming) current.metadata else null,
                interactive = message.interactive,
                pointerOwner = if (mapped == WindowMirrorStatus.Streaming) {
                    current.pointerOwner
                } else {
                    SymbiosisPointerOwner.Mac
                },
                transport = WindowMirrorTransport.DirectTcp,
                stats = current.stats,
            )
        }
    }
}

/**
 * Pre-API 29 equivalent of [android.media.MediaCodecInfo.isHardwareAccelerated].
 *
 * Codec names were the only platform signal on API 24–28. This mirrors the
 * conservative AndroidX classification: known platform/vendor software codecs
 * (and unknown non-OMX/C2 codecs) are rejected, while established vendor OMX/C2
 * codecs and ARC's video codec are treated as hardware accelerated.
 */
internal fun isLegacyHardwareCodec(codecName: String): Boolean {
    val name = codecName.lowercase()
    if (name.startsWith("arc.")) return name == "arc.video"
    val isKnownSoftware =
        name.startsWith("omx.google.") ||
            name.startsWith("omx.ffmpeg.") ||
            (name.startsWith("omx.sec.") && name.contains(".sw.")) ||
            name == "omx.qcom.video.decoder.hevcswvdec" ||
            name.startsWith("c2.android.") ||
            name.startsWith("c2.google.") ||
            (!name.startsWith("omx.") && !name.startsWith("c2."))
    return !isKnownSoftware
}
