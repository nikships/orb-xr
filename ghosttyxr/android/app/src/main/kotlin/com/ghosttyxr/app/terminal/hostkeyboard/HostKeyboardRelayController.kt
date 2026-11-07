package com.ghosttyxr.app.terminal.hostkeyboard

import android.content.Context
import com.ghosttyxr.app.qr.PairingPayload
import com.ghosttyxr.app.terminal.input.AppShortcutAction
import com.ghosttyxr.app.terminal.input.AppShortcutRouter
import com.ghosttyxr.app.terminal.input.GhosttyKeyAction
import com.ghosttyxr.app.terminal.input.MappedKey
import com.ghosttyxr.app.terminal.input.TerminalInputSink
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

private const val TAG = "HostKeyboardRelay"
private const val HEARTBEAT_INTERVAL_MS = 1_000L
private const val WATCHDOG_TIMEOUT_MS = 3_000L
private const val IDLE_READ_DELAY_MS = 16L
private const val MAX_RETRY_DELAY_MS = 5_000L
private const val INITIAL_RETRY_DELAY_MS = 500L
private const val PAIRING_POLL_MS = 1_000L

/**
 * Connects to the Mac companion's loopback keyboard listener through a
 * separate authenticated SSH direct-tcpip channel and routes remote key events
 * into the focused XR terminal.
 */
class HostKeyboardRelayController(
    context: Context,
    private val scope: CoroutineScope,
    private val inputSink: TerminalInputSink,
    private val layoutLocked: () -> Boolean,
    private val triggerVoiceCapture: () -> Unit,
    private val focusPane: (Int) -> Unit,
    private val hostAccess: VerifiedHostAccess,
) {
    private val appContext = context.applicationContext
    private var job: Job? = null
    private val tunnelGuard = ChannelGenerationGuard<EstablishedTunnel>()
    @Volatile private var runningRequested = false

    private val _state = MutableStateFlow(HostKeyboardRelayState())
    val state: StateFlow<HostKeyboardRelayState> = _state.asStateFlow()

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
        cancelJobAndTunnel()
        _state.value = HostKeyboardRelayState(HostKeyboardRelayStatus.WaitingForPairing)
    }

    fun restart() {
        val shouldRestart = runningRequested
        cancelJobAndTunnel()
        if (shouldRestart) start() else _state.value = HostKeyboardRelayState()
    }

    private fun cancelJobAndTunnel() {
        job?.cancel()
        job = null
        // Bumps the generation first (so a connect currently in flight on the
        // cancelled job's thread finds itself superseded and closes its own
        // tunnel immediately — see connectAndPump), then closes whatever was
        // already claimed.
        runCatching { tunnelGuard.invalidate()?.close() }
    }

    private suspend fun runLoop(myGeneration: Long) {
        var retryDelayMs = INITIAL_RETRY_DELAY_MS
        while (currentCoroutineContext().isActive) {
            val pair = loadPairedMac()
            if (pair == null) {
                _state.value = HostKeyboardRelayState(
                    HostKeyboardRelayStatus.WaitingForPairing,
                    "Pair a Mac to enable host keyboard forwarding.",
                )
                delay(PAIRING_POLL_MS)
                continue
            }

            val (mac, key) = pair
            val outcome = runCatching {
                _state.value = HostKeyboardRelayState(
                    HostKeyboardRelayStatus.Connecting,
                    "Opening keyboard relay to ${mac.host}.",
                )
                connectAndPump(mac, key, myGeneration)
            }

            val failure = outcome.exceptionOrNull()
            if (failure is CancellationException) throw failure
            if (failure != null) {
                GxrLog.w(TAG, "relay stopped: ${failure.message}")
                val message = failure.message.orEmpty()
                _state.value = HostKeyboardRelayState(
                    status = companionOrErrorStatus(message),
                    detail = message.ifBlank { failure::class.simpleName },
                )
            }

            runCatching { tunnelGuard.release(myGeneration)?.close() }
            delay(retryDelayMs)
            retryDelayMs = min(retryDelayMs * 2, MAX_RETRY_DELAY_MS)
        }
    }

    private fun companionOrErrorStatus(message: String): HostKeyboardRelayStatus {
        val companionDown = message.contains("direct-tcpip", ignoreCase = true) ||
            message.contains("Connection refused", ignoreCase = true) ||
            message.contains("open failed", ignoreCase = true)
        return if (companionDown) {
            HostKeyboardRelayStatus.WaitingForCompanion
        } else {
            HostKeyboardRelayStatus.Error
        }
    }

    private fun loadPairedMac(): Pair<PairedMac, SshKey>? {
        PairedMacStore.init(appContext)
        return PairedMacStore.load()
    }

    private suspend fun connectAndPump(mac: PairedMac, key: SshKey, myGeneration: Long) {
        val payload = mac.toPayload()
        // Blocking — handshake/verify/authenticate/open all run before this
        // returns, so a stop/restart on another thread may already have
        // invalidated [myGeneration] by the time we get here.
        val tunnel = hostAccess.connectTunnel(
            host = payload.host,
            port = payload.port,
            username = payload.user,
            expectedFingerprint = payload.fingerprint,
            password = "",
            key = key,
            targetHost = "127.0.0.1",
            targetPort = HOST_KEYBOARD_RELAY_PORT,
        )
        if (!tunnelGuard.claim(myGeneration, tunnel)) {
            // Shutdown/restart beat us to it — nobody else will ever see
            // this tunnel, so close it immediately instead of leaking it.
            runCatching { tunnel.close() }
            return
        }

        _state.value = HostKeyboardRelayState(
            HostKeyboardRelayStatus.Connected,
            "Connected; arm capture from the Mac companion.",
        )
        tunnel.write(HostKeyboardProtocol.helloLine())
        pump(tunnel, payload)
    }

    private suspend fun pump(tunnel: EstablishedTunnel, payload: PairingPayload) {
        val framer = HostKeyboardLineFramer()
        val heldKeys = linkedMapOf<String, MappedKey>()
        var lastSeq = 0L
        var lastInboundAt = System.currentTimeMillis()
        var lastHeartbeatAt = 0L
        try {
            while (currentCoroutineContext().isActive) {
                val chunk = tunnel.read(HOST_KEYBOARD_MAX_FRAME_BYTES)
                    ?: error("keyboard relay channel closed")
                val now = System.currentTimeMillis()
                if (chunk.isNotEmpty()) {
                    lastInboundAt = now
                    for (line in framer.accept(chunk)) {
                        lastSeq = dispatchMessage(
                            message = HostKeyboardProtocol.parse(line),
                            tunnel = tunnel,
                            payload = payload,
                            heldKeys = heldKeys,
                            lastSeq = lastSeq,
                        )
                    }
                }

                if (now - lastHeartbeatAt >= HEARTBEAT_INTERVAL_MS) {
                    tunnel.write(HostKeyboardProtocol.pingLine())
                    lastHeartbeatAt = now
                }
                if (now - lastInboundAt > WATCHDOG_TIMEOUT_MS) {
                    error("keyboard relay watchdog timed out")
                }
                if (chunk.isEmpty()) delay(IDLE_READ_DELAY_MS)
            }
        } finally {
            framer.reset()
            releaseHeldKeys(heldKeys)
        }
    }

    private suspend fun dispatchMessage(
        message: HostKeyboardMessage,
        tunnel: EstablishedTunnel,
        payload: PairingPayload,
        heldKeys: MutableMap<String, MappedKey>,
        lastSeq: Long,
    ): Long =
        when (message) {
            is HostKeyboardMessage.Hello -> {
                tunnel.write(HostKeyboardProtocol.readyLine())
                _state.value = HostKeyboardRelayState(
                    HostKeyboardRelayStatus.Connected,
                    "Connected to Mac keyboard relay.",
                )
                lastSeq
            }
            HostKeyboardMessage.Ready, HostKeyboardMessage.Pong -> lastSeq
            HostKeyboardMessage.Ping -> {
                tunnel.write(HostKeyboardProtocol.pongLine())
                lastSeq
            }
            is HostKeyboardMessage.Reset -> {
                releaseHeldKeys(heldKeys)
                0L
            }
            is HostKeyboardMessage.CaptureState -> {
                updateCaptureState(message, payload)
                lastSeq
            }
            is HostKeyboardMessage.Key -> {
                if (message.seq != lastSeq + 1) {
                    tunnel.write(
                        HostKeyboardProtocol.resetLine("expected seq ${lastSeq + 1}, got ${message.seq}"),
                    )
                    releaseHeldKeys(heldKeys)
                    error("keyboard relay sequence gap")
                }
                handleKey(message, heldKeys)
                message.seq
            }
        }

    private fun updateCaptureState(message: HostKeyboardMessage.CaptureState, payload: PairingPayload) {
        _state.value = when {
            message.permission == "denied" || message.permission == "missing" ->
                HostKeyboardRelayState(
                    HostKeyboardRelayStatus.PermissionNeeded,
                    message.reason ?: "Grant Input Monitoring to Orb XR on ${payload.host}.",
                )
            message.capturing ->
                HostKeyboardRelayState(
                    HostKeyboardRelayStatus.Capturing,
                    "Forwarding Mac keyboard to focused XR terminal.",
                )
            message.armed ->
                HostKeyboardRelayState(
                    HostKeyboardRelayStatus.Connected,
                    message.reason ?: "Armed; waiting for active capture.",
                )
            else ->
                HostKeyboardRelayState(
                    HostKeyboardRelayStatus.Connected,
                    message.reason ?: "Connected; arm capture from the Mac companion.",
                )
        }
    }

    private suspend fun handleKey(
        message: HostKeyboardMessage.Key,
        heldKeys: MutableMap<String, MappedKey>,
    ) {
        val locked = layoutLocked()
        if (AppShortcutRouter.shouldConsumeRemote(message.code, message.mods, locked)) {
            when (val action = AppShortcutRouter.remote(message.code, message.mods, message.action, locked)) {
                AppShortcutAction.ToggleVoiceCapture -> withContext(Dispatchers.Main) { triggerVoiceCapture() }
                is AppShortcutAction.FocusPane -> withContext(Dispatchers.Main) { focusPane(action.paneId) }
                null -> Unit
            }
            return
        }

        val mapped = HostKeyboardMapper.map(message) ?: return
        when (message.action) {
            GhosttyKeyAction.Press -> heldKeys[message.code] = mapped
            GhosttyKeyAction.Release -> heldKeys.remove(message.code)
        }
        inputSink.onKey(mapped)
    }

    private fun releaseHeldKeys(heldKeys: MutableMap<String, MappedKey>) {
        if (heldKeys.isEmpty()) return
        val releases = heldKeys.values.map(HostKeyboardMapper::releaseFor)
        heldKeys.clear()
        releases.forEach(inputSink::onKey)
    }
}
