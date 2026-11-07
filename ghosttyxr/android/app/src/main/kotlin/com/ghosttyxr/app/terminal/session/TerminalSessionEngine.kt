package com.ghosttyxr.app.terminal.session

import android.util.Log
import com.ghosttyxr.app.qr.PairingPayload
import com.ghosttyxr.app.terminal.GhosttyBridge
import com.ghosttyxr.app.terminal.TerminalSnapshot
import com.ghosttyxr.app.terminal.input.MappedKey
import com.ghosttyxr.app.terminal.ssh.EstablishedShell
import com.ghosttyxr.app.terminal.ssh.SshKey
import com.ghosttyxr.app.terminal.ssh.VerifiedHostAccess
import com.ghosttyxr.app.terminal.theme.GhosttyTheme
import com.ghosttyxr.app.terminal.theme.toBridgeColorArgs
import com.ghosttyxr.app.util.GxrLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val TAG = "TerminalSessionEngine"

private const val SSH_UNAVAILABLE_MESSAGE =
    "SSH not yet wired into libghosttyxr.so — waiting on Zig-side task #4 port."

// Snapshot tick. 16ms = ~60fps cap. Faster would just produce duplicate
// snapshots the renderer would diff away; slower is visible on fast typers.
private const val SNAPSHOT_INTERVAL_MS = 16L

// Adaptive idle backoff for the read loop. While the channel is open but
// idle we poll at [IDLE_POLL_FLOOR_MS] (= the 60fps tick) so the first byte
// of new output is picked up immediately. After [IDLE_BACKOFF_AFTER_EMPTY_READS]
// consecutive empty reads we ramp the poll interval up (doubling) to
// [IDLE_POLL_CAP_MS], cutting idle wakeups ~10x on a quiet pane. A non-empty
// read or user input snaps the interval straight back to the floor, so neither
// incoming output nor keystroke echo gains any perceptible latency.
internal const val IDLE_POLL_FLOOR_MS = SNAPSHOT_INTERVAL_MS
internal const val IDLE_POLL_CAP_MS = 160L
internal const val IDLE_BACKOFF_AFTER_EMPTY_READS = 8

// Quiescence gate for **command submission** (voice / Gemini). Unlike a
// keystroke — which is one byte that round-trips through the read loop and so
// naturally interleaves with terminal-protocol traffic — a command is a whole
// line injected in one shot. If it lands while the remote program is mid
// capability-query (e.g. a prompt re-querying Device Attributes / Kitty
// keyboard flags after a resize-driven redraw), libghostty's reply and our
// command share a write window and the shell splices the reply's printable tail
// ("0u", "62;…") onto the command line. So we hold a command until the channel
// has been inbound-idle for [COMMAND_QUIESCENCE_WINDOW_MS] (every in-flight
// query/reply has settled), polling at [COMMAND_QUIESCENCE_POLL_MS], and give up
// waiting after [COMMAND_QUIESCENCE_TIMEOUT_MS] so a continuously-streaming pane
// can't wedge submission forever.
internal const val COMMAND_QUIESCENCE_WINDOW_MS = 120L
internal const val COMMAND_QUIESCENCE_POLL_MS = SNAPSHOT_INTERVAL_MS
internal const val COMMAND_QUIESCENCE_TIMEOUT_MS = 1_500L

/**
 * Next idle poll interval given how many consecutive empty reads we've seen
 * and the [currentDelayMs] in force. Stays at the floor until the backoff
 * threshold, then doubles toward the cap. Pure so it can be unit-tested off
 * the device without the native bridge.
 */
internal fun nextIdlePollDelayMs(consecutiveEmptyReads: Int, currentDelayMs: Long): Long {
    if (consecutiveEmptyReads < IDLE_BACKOFF_AFTER_EMPTY_READS) return IDLE_POLL_FLOOR_MS
    return (currentDelayMs * 2).coerceIn(IDLE_POLL_FLOOR_MS, IDLE_POLL_CAP_MS)
}

// How long an SSH channel may sit idle before we probe it with a keepalive
// to catch a half-open connection (Mac asleep, Wi-Fi roamed, NAT dropped
// the flow with no FIN/RST). Mirrors a conservative OpenSSH
// ServerAliveInterval. Overridable per-engine for fast tests.
private const val DEFAULT_KEEPALIVE_INTERVAL_MS = 20_000L

/**
 * Per-pane terminal session. Owns:
 *   - one [GhosttyBridge] handle (libghostty surface for ANSI parsing + render),
 *   - one [EstablishedShell] per connect/reconnect episode, obtained from
 *     [VerifiedHostAccess] (libssh2 in prod; fake in tests) — the engine never
 *     drives a raw SSH transport's handshake/verify/authenticate state machine
 *     itself,
 *   - a single-thread coroutine dispatcher named `terminal-pane-<id>` so
 *     every JNI call into the engine is serialized — libssh2 and the
 *     libghostty surface are *not* internally thread-safe.
 *
 * Public surface follows team-lead's #4 spec:
 *   [connect] / [disconnect] / [resize] / [sendInput] / [snapshot] / [state].
 *
 * Read loop ticks at 60fps (16ms):
 *   shell.read() → bridge.nativeWriteRemote() → bridge.nativeDrainPtyWrites()
 *   → shell.write() → bridge.nativeSnapshot() → emit.
 *
 * **PENDING: zig-side ghosttyxr_ssh.zig port (task #4 zig side, owned by
 * zig-native).** The native transport's `external fun`s aren't in the .so
 * yet — until they land, every real-transport call throws
 * [UnsatisfiedLinkError]. The engine catches that at [connect] entry,
 * emits [SessionState.Failed], and surfaces a useful message instead
 * of crashing. Test paths use a fake transport (via [VerifiedHostAccess])
 * to exercise the snapshot pipeline without that dependency.
 */
class TerminalSessionEngine(
    val paneId: Int,
    private val hostAccess: VerifiedHostAccess,
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
    private val keepAliveIntervalMs: Long = DEFAULT_KEEPALIVE_INTERVAL_MS,
) {
    /**
     * Stable host-side tmux identity. It survives a repository rebuild even
     * when the local UI's ephemeral [paneId] is reassigned on app launch.
     */
    var hostSessionPaneKey: Long = paneId.toLong()
        private set

    fun setHostSessionPaneKey(key: Long) {
        hostSessionPaneKey = key
    }

    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "terminal-pane-$paneId").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val bridge = GhosttyBridge()

    private var ghosttyHandle: Long = 0L
    private var transportConnected: Boolean = false
    private var shell: EstablishedShell? = null
    private var readJob: Job? = null
    private var reconnectJob: Job? = null
    private var disconnectRequested = false

    // Wakes an in-flight reconnect backoff early (e.g. when connectivity
    // returns) so we retry now instead of sleeping out the delay. CONFLATED:
    // a wake delivered while we're not waiting is coalesced and consumed by
    // the next backoff — exactly the "retry promptly" semantics we want.
    private val reconnectWake = Channel<Unit>(Channel.CONFLATED)

    // Cuts an in-flight idle-backoff sleep short the instant the user sends
    // bytes upstream, so keystroke echo never waits out a backed-off poll
    // interval. CONFLATED: a signal delivered while the loop isn't sleeping
    // is coalesced and harmlessly consumed by the next idle wait.
    private val idleWake = Channel<Unit>(Channel.CONFLATED)

    // Wall-clock of the last proof the channel is alive: inbound bytes or a
    // successful keepalive. Drives the half-open probe in the read loop.
    private var lastLivenessMs = 0L

    // Wall-clock of the last *inbound bytes* from the remote (NOT bumped by
    // keepalives). The command-submission quiescence gate reads this to tell
    // when in-flight terminal-protocol traffic (query replies after a redraw)
    // has settled. @Volatile: written on the engine dispatcher, read by the
    // submit coroutine.
    @Volatile
    private var lastInboundBytesMs = 0L

    private var cols = 80
    private var rows = 24
    private var cellWidth = 1
    private var cellHeight = 1

    private var title: String? = null
    private var pwd: String? = null

    /** Last host working directory reported via OSC 7, if any. */
    fun workingDirectory(): String? = pwd

    private var lastSnapshotAtMs = 0L
    private var snapshotScheduled = false

    private var lastPayload: PairingPayload? = null
    private var lastKey: SshKey? = null
    private var bootstrapCommand: ByteArray? = null
    /**
     * One-shot command typed after the initial connect (and after [bootstrapCommand]).
     * Not re-run on reconnect — that would re-launch agents into an already-running pane.
     */
    private var postConnectCommand: ByteArray? = null

    // One-shot guard so the "SSH symbols not in .so" log fires exactly
    // once per engine lifetime. Without this, four panes × the reconnect
    // schedule would spam Logcat while task #4's Zig side is in flight.
    // Reset only on close() — re-construction is the user signal that
    // "things might be different now" (e.g. .so swapped in).
    private var sshUnavailableLogged = false

    private val _state = MutableStateFlow<SessionState>(SessionState.Disconnected)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _snapshot = MutableStateFlow(
        TerminalSnapshot.blank(
            cols = cols,
            rows = rows,
            defaultFgArgb = TerminalSnapshot.packArgb(0xE0, 0xE6, 0xEF),
            defaultBgArgb = TerminalSnapshot.packArgb(0x05, 0x07, 0x0A),
        ),
    )
    val snapshot: StateFlow<TerminalSnapshot> = _snapshot.asStateFlow()

    // Terminal-bell chime events drained from libghostty (`\a` / completion chimes via
    // `GhosttyBridge.nativeDrainBellCount`). Each emission is the coalesced chime count for
    // one snapshot tick (see [BellChimePolicy]) — the spatial layer plays one chime per
    // count from the emitting pane's 3D position. Buffer so a burst never suspends the
    // engine dispatcher; the UI collects latest-first and drops overflow.
    private val _bellEvents = MutableSharedFlow<Int>(extraBufferCapacity = 16)
    val bellEvents: SharedFlow<Int> = _bellEvents.asSharedFlow()

    fun connect(
        payload: PairingPayload,
        key: SshKey? = null,
        bootstrapCommand: String? = null,
        postConnectCommand: String? = null,
    ) {
        GxrLog.d(TAG) { "connect() called for pane $paneId to ${payload.host}:${payload.port}" }
        disconnectRequested = false
        lastPayload = payload
        lastKey = key
        this.bootstrapCommand = bootstrapCommand?.encodeToByteArray()
        // Accept with or without a trailing CR; always store a submittable line.
        this.postConnectCommand = postConnectCommand
            ?.takeIf { it.isNotBlank() }
            ?.let { cmd -> (if (cmd.endsWith("\r")) cmd else "$cmd\r").encodeToByteArray() }
        scope.launch {
            GxrLog.d(TAG) { "connect() scope.launch started for pane $paneId" }
            reconnectJob?.cancel()
            reconnectJob = null
            _state.value = SessionState.Connecting

            if (!bridge.isLoaded()) {
                Log.e(TAG, "bridge not loaded")
                _state.value = SessionState.Failed("libghosttyxr.so not loaded: ${bridge.nativeStatus()}")
                return@launch
            }

            try {
                openGhosttySurface()
                GxrLog.d(TAG) { "Ghostty surface opened, connecting transport..." }
                connectTransport(payload, key)
                GxrLog.d(TAG) { "Transport connected!" }
                _state.value = SessionState.Connected(title = null, pwd = null)
                startReadLoop()
                this@TerminalSessionEngine.bootstrapCommand?.let(::submitCommand)
                // One-shot agent launch (or similar) after host-session bootstrap.
                this@TerminalSessionEngine.postConnectCommand?.let { cmd ->
                    this@TerminalSessionEngine.postConnectCommand = null
                    submitCommand(cmd)
                }
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "UnsatisfiedLinkError in connect", e)
                logSshUnavailable(e)
                tearDown()
                lastPayload = null
                _state.value = SessionState.Failed(SSH_UNAVAILABLE_MESSAGE)
            } catch (e: Exception) {
                Log.e(TAG, "Connect failed with exception", e)
                tearDown()
                _state.value = SessionState.Failed("${e::class.simpleName}: ${e.message}")
            }
        }
    }

    fun disconnect() {
        disconnectRequested = true
        scope.launch {
            reconnectJob?.cancel()
            reconnectJob = null
            tearDown()
            _state.value = SessionState.Disconnected
        }
    }

    /**
     * Connectivity just returned. If this pane is mid-backoff waiting to
     * reconnect, cut the wait short and retry immediately; otherwise this is
     * a harmless no-op (the conflated signal is coalesced for the next wait).
     */
    fun onNetworkAvailable() {
        reconnectWake.trySend(Unit)
    }

    fun resize(cols: Int, rows: Int, cellW: Int, cellH: Int) {
        if (cols <= 0 || rows <= 0 || cellW <= 0 || cellH <= 0) return
        scope.launch {
            this@TerminalSessionEngine.cols = cols
            this@TerminalSessionEngine.rows = rows
            cellWidth = cellW
            cellHeight = cellH
            val widthPx = cols * cellW
            val heightPx = rows * cellH
            if (ghosttyHandle != 0L) {
                bridge.nativeResize(ghosttyHandle, cols, rows, cellW, cellH)
                bridge.nativeSetMouseEncodingSize(
                    ghosttyHandle,
                    widthPx,
                    heightPx,
                    cellW,
                    cellH,
                    0, 0, 0, 0,
                )
                requestSnapshot(force = true)
            }
            if (transportConnected) {
                runCatching { shell?.resize(cols, rows, widthPx, heightPx) }
                    .onFailure { Log.w(TAG, "shell.resize failed: ${it.message}") }
            }
        }
    }

    /**
     * Send raw bytes upstream to the Mac shell. Used by:
     *   - KeyMapper.encode → encoded escape sequences,
     *   - on-screen keyboard text input (already UTF-8),
     *   - the libghostty PTY drain (replies to DA/OSC queries).
     */
    fun sendInput(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        scope.launch {
            runCatching { writeRemote(bytes) }
                .onFailure { Log.w(TAG, "sendInput failed: ${it.message}") }
        }
    }

    /**
     * Inject a whole **command** (voice / Gemini) and submit it, gated on the
     * channel being quiescent first.
     *
     * A command is fundamentally different from a keystroke: it's a full line
     * written in one shot, with no per-character echo round-trip to interleave
     * it safely with terminal-protocol traffic. If it's written while the remote
     * program is mid capability-query — common right after a Gemini action
     * resizes/redraws the pane and a fancy prompt re-queries Device Attributes /
     * Kitty keyboard flags — libghostty's reply and the command share one write
     * window and the shell splices the reply's printable tail ("0u", "62;…")
     * onto the command line.
     *
     * So we wait until no inbound bytes have arrived for
     * [COMMAND_QUIESCENCE_WINDOW_MS] (every in-flight query/reply has settled),
     * then write the command. The wait is bounded by
     * [COMMAND_QUIESCENCE_TIMEOUT_MS] so a continuously-streaming pane still
     * accepts commands (it just can't guarantee perfect isolation then).
     *
     * [bytes] is the full payload including any submit terminator (e.g. a
     * trailing CR); this method does not append one.
     */
    fun submitCommand(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        scope.launch {
            awaitChannelQuiescent()
            runCatching { writeRemote(bytes) }
                .onFailure { Log.w(TAG, "submitCommand failed: ${it.message}") }
        }
    }

    /**
     * Suspend until the channel has had no inbound bytes for
     * [COMMAND_QUIESCENCE_WINDOW_MS], or [COMMAND_QUIESCENCE_TIMEOUT_MS] elapses.
     * Runs on the engine dispatcher; [delay] yields it so the read loop keeps
     * draining inbound bytes (and updating [lastInboundBytesMs]) while we wait.
     */
    private suspend fun awaitChannelQuiescent() {
        // Snap the read loop out of any idle backoff so pending inbound bytes
        // (e.g. a query reply still sitting in the socket) are read — and
        // [lastInboundBytesMs] updated — before we judge the channel quiet.
        // Otherwise a stale timestamp could read as "quiescent" while a reply
        // is still unread, and we'd inject ahead of it.
        idleWake.trySend(Unit)
        val deadline = System.currentTimeMillis() + COMMAND_QUIESCENCE_TIMEOUT_MS
        // Always poll at least once so a just-woken read has a chance to land.
        do {
            delay(COMMAND_QUIESCENCE_POLL_MS)
            val sinceInbound = System.currentTimeMillis() - lastInboundBytesMs
            if (sinceInbound >= COMMAND_QUIESCENCE_WINDOW_MS) return
        } while (System.currentTimeMillis() < deadline)
    }

    fun scroll(delta: Int) {
        if (delta == 0) return
        scope.launch {
            if (ghosttyHandle == 0L) return@launch
            bridge.nativeScroll(ghosttyHandle, delta)
            flushPtyWrites()
            requestSnapshot()
        }
    }

    fun scrollToActive() {
        scope.launch {
            if (ghosttyHandle == 0L) return@launch
            bridge.nativeScrollToActive(ghosttyHandle)
            requestSnapshot(force = true)
        }
    }

    fun sendFocusEvent(focused: Boolean) {
        scope.launch {
            if (ghosttyHandle == 0L) return@launch
            val encoded = bridge.nativeEncodeFocus(ghosttyHandle, focused) ?: return@launch
            if (encoded.isNotEmpty()) writeRemote(encoded)
        }
    }

    /**
     * Encode a [MappedKey] through libghostty and forward the resulting
     * escape-sequence bytes upstream. Called by `PaneInputController` when
     * the focused pane receives a `KeyEvent` from the physical keyboard,
     * controller, or on-screen keyboard.
     *
     * Fire-and-forget: keystrokes typed before [connect] (or during a
     * reconnect) are silently dropped at the bridge level — libghostty's
     * encoder returns an empty byte array if the surface isn't ready, and
     * the empty bytes short-circuit `writeRemote`. No blocking, no
     * accumulating ahead-of-connect buffer.
     */
    fun sendKey(key: MappedKey) {
        scope.launch {
            if (ghosttyHandle == 0L) return@launch
            val utf8 = key.utf8
            if (utf8 != null) {
                writeRemote(utf8.toByteArray(Charsets.UTF_8))
                return@launch
            }
            val encoded = bridge.nativeEncodeKey(
                ghosttyHandle,
                key.key,
                key.codepoint,
                key.mods,
                key.action,
                utf8 = null,
            ) ?: return@launch
            if (encoded.isNotEmpty()) writeRemote(encoded)
        }
    }

    /**
     * Push a [GhosttyTheme]'s fg/bg/cursor/palette into libghostty. Theme
     * changes only take visual effect after the next snapshot tick — the
     * caller doesn't need to force one; the read loop's next iteration
     * picks it up. We force a snapshot here anyway for the case where
     * the user changes theme on an idle connection (no incoming bytes to
     * trigger the next tick).
     */
    fun setColors(theme: GhosttyTheme) {
        val args = toBridgeColorArgs(theme)
        scope.launch {
            if (ghosttyHandle == 0L) return@launch
            bridge.nativeSetDefaultColors(
                ghosttyHandle,
                args.fg,
                args.bg,
                args.cursor,
                args.palette,
            )
            requestSnapshot(force = true)
        }
    }

    /**
     * Synchronous teardown. Drains libssh2 + libghostty resources held
     * by this engine on the engine's own dispatcher, then cancels the
     * scope and closes the dispatcher.
     *
     * Synchronous so back-to-back create/close cycles (typical in test
     * harnesses) actually free their native handles before the next
     * engine spins up — otherwise libghostty surfaces leak ~9MB each
     * and the test process OOMs.
     */
    fun close() {
        val latch = CountDownLatch(1)
        // We can't use runBlocking + withContext here because the read
        // loop may be in a `delay()` suspension when close() fires.
        // dispatcher.dispatch(...) puts our task at the head of the
        // queue without disturbing the suspended coroutine's resumption.
        val dispatched = runCatching {
            dispatcher.dispatch(scope.coroutineContext) {
                try {
                    disconnectRequested = true
                    reconnectJob?.cancel()
                    reconnectJob = null
                    tearDown()
                    _state.value = SessionState.Disconnected
                } finally {
                    latch.countDown()
                }
            }
        }
        if (dispatched.isSuccess) {
            // Bounded wait — if teardown is wedged on a JNI call we'd
            // rather leak a handle than hang the test forever.
            latch.await(2, TimeUnit.SECONDS)
        }
        scope.cancel()
        dispatcher.close()
    }

    private fun openGhosttySurface() {
        if (ghosttyHandle != 0L) {
            bridge.nativeDestroy(ghosttyHandle)
            ghosttyHandle = 0L
        }
        ghosttyHandle = bridge.nativeCreate(cols, rows, ScrollbackPolicy.LOCAL_HISTORY_LINES)
        check(ghosttyHandle != 0L) { "libghostty surface allocation failed" }
        bridge.nativeResize(ghosttyHandle, cols, rows, cellWidth, cellHeight)
    }

    private fun connectTransport(payload: PairingPayload, key: SshKey?) {
        // [VerifiedHostAccess.connectShell] owns the whole handshake → verify
        // (QR/persisted fingerprint pin check) → authenticate (only
        // after verification, so a MITM never sees the token) → open-shell
        // sequence and hands back only the narrow [EstablishedShell] — the
        // engine never touches a raw transport's state machine.
        shell = hostAccess.connectShell(
            host = payload.host,
            port = payload.port,
            username = payload.user,
            expectedFingerprint = payload.fingerprint,
            password = payload.token,
            key = key,
            cols = cols,
            rows = rows,
            widthPx = cols * cellWidth,
            heightPx = rows * cellHeight,
        )
        transportConnected = true
        _state.value = SessionState.Authenticated
    }

    private fun logSshUnavailable(error: Throwable) {
        if (sshUnavailableLogged) return
        sshUnavailableLogged = true
        Log.e(TAG, "SSH native exports missing in libghosttyxr.so — gating further logs to once/lifetime", error)
    }

    private fun startReadLoop() {
        readJob?.cancel()
        readJob = scope.launch {
            try {
                runReadLoop()
            } catch (e: Exception) {
                Log.w(TAG, "read loop ended: ${e.message}")
            }
            if (!disconnectRequested) {
                scheduleReconnect("connection interrupted")
            }
        }
    }

    private suspend fun runReadLoop() {
        // Chunked I/O semantics (copied from chuchu): read returns
        //   null              — channel EOF; stop loop, trigger reconnect,
        //   ByteArray(0)      — open but idle; back off one tick and poll,
        //   ByteArray(len>0)  — feed into libghostty.
        //
        // We drain libghostty's pty-write queue *before* and *after*
        // feeding remote bytes: before catches replies libghostty queued
        // from earlier writes; after catches immediate responses (e.g.
        // DA queries) the just-fed bytes triggered.
        val readSize = 65_536
        lastLivenessMs = System.currentTimeMillis()
        var consecutiveEmptyReads = 0
        var idleDelayMs = IDLE_POLL_FLOOR_MS
        while (currentCoroutineContext().isActive && transportConnected) {
            val chunk = runCatching { shell?.read(readSize) }
                .onFailure { Log.w(TAG, "shell.read threw: ${it.message}") }
                .getOrNull()
            when {
                chunk == null -> break
                chunk.isEmpty() -> {
                    // Open-but-idle. Probe for a half-open connection before
                    // sleeping; a failed keepalive throws and routes us to
                    // reconnect instead of polling a dead socket forever.
                    probeLivenessIfDue()
                    consecutiveEmptyReads += 1
                    idleDelayMs = nextIdlePollDelayMs(consecutiveEmptyReads, idleDelayMs)
                    // Sleep the backed-off interval, but wake instantly if the
                    // user sends input so keystroke echo stays on the 60fps tick.
                    val woken = withTimeoutOrNull(idleDelayMs) { idleWake.receive() } != null
                    if (woken) {
                        consecutiveEmptyReads = 0
                        idleDelayMs = IDLE_POLL_FLOOR_MS
                    }
                }
                else -> {
                    consecutiveEmptyReads = 0
                    idleDelayMs = IDLE_POLL_FLOOR_MS
                    val now = System.currentTimeMillis()
                    lastLivenessMs = now
                    lastInboundBytesMs = now
                    if (ghosttyHandle != 0L) {
                        flushPtyWrites()
                        bridge.nativeWriteRemote(ghosttyHandle, chunk)
                        flushPtyWrites()
                        requestSnapshot()
                    }
                }
            }
        }
    }

    /**
     * Half-open detection. After [keepAliveIntervalMs] with no proof of life
     * (no inbound bytes, no successful keepalive), ask the transport for an
     * SSH keepalive. A `false` return or a throw means the channel is dead —
     * we surface it as an exception so the read loop unwinds into
     * [scheduleReconnect] rather than polling a corpse at 60fps.
     */
    private fun probeLivenessIfDue() {
        val now = System.currentTimeMillis()
        if (now - lastLivenessMs < keepAliveIntervalMs) return
        val alive = try {
            shell?.sendKeepAlive() ?: false
        } catch (e: UnsatisfiedLinkError) {
            closeTransportQuietly("after keepalive failure")
            throw IllegalStateException("SSH keepalive failed", e)
        } catch (e: Exception) {
            closeTransportQuietly("after keepalive failure")
            throw e
        }
        if (!alive) {
            closeTransportQuietly("after keepalive failure")
            error("SSH keepalive reported a dead channel")
        }
        lastLivenessMs = now
    }

    /** Close the shell, logging (not propagating) any close failure, and mark it down. */
    private fun closeTransportQuietly(context: String) {
        runCatching { shell?.close() }
            .onFailure { Log.w(TAG, "shell.close $context failed: ${it.message}") }
        transportConnected = false
        shell = null
    }

    private fun writeRemote(bytes: ByteArray) {
        if (!transportConnected || bytes.isEmpty()) return
        // Upstream activity (keystroke, pasted text, pty reply) — wake a
        // backed-off idle poll so the echo round-trips on the 60fps tick.
        idleWake.trySend(Unit)
        runCatching { shell?.write(bytes) }
            .onFailure { e ->
                Log.w(TAG, "shell.write failed: ${e.message}")
                // A broken write is a dead-connection signal the idle read
                // poll might not notice for up to a keepalive interval. Mark
                // the transport down and kick reconnect right away. The read
                // loop's own exit then folds into the same (guarded) call.
                if (!disconnectRequested) {
                    closeTransportQuietly("after write failure")
                    scheduleReconnect("write failed: ${e.message}")
                }
            }
    }

    private fun flushPtyWrites() {
        if (ghosttyHandle == 0L) return
        // Bounded loop — libghostty can produce a lot of replies in one
        // burst (e.g. an OSC query storm), but more than 8 rounds is
        // pathological and we'd rather drop than livelock the read tick.
        repeat(8) {
            val ptyWrites = bridge.nativeDrainPtyWrites(ghosttyHandle)
            if (ptyWrites.isEmpty()) return
            writeRemote(ptyWrites)
        }
    }

    private fun requestSnapshot(force: Boolean = false) {
        if (ghosttyHandle == 0L) return
        val now = System.currentTimeMillis()
        val elapsed = now - lastSnapshotAtMs
        if (force || elapsed >= SNAPSHOT_INTERVAL_MS) {
            snapshotScheduled = false
            emitSnapshot()
            lastSnapshotAtMs = now
            return
        }
        if (snapshotScheduled) return
        snapshotScheduled = true
        val waitMs = (SNAPSHOT_INTERVAL_MS - elapsed).coerceAtLeast(1L)
        scope.launch {
            delay(waitMs)
            snapshotScheduled = false
            if (ghosttyHandle == 0L) return@launch
            emitSnapshot()
            lastSnapshotAtMs = System.currentTimeMillis()
        }
    }

    private fun emitSnapshot() {
        if (ghosttyHandle == 0L) return
        try {
            val raw = bridge.nativeSnapshot(ghosttyHandle)
            if (raw.capacity() == 0) return
            val rawImages = bridge.nativeSnapshotImages(ghosttyHandle)
            val images = TerminalSnapshot.parseImages(rawImages)
            val nextSnapshot = TerminalSnapshot.fromByteBuffer(raw, images)
            if (nextSnapshot != _snapshot.value) {
                _snapshot.value = nextSnapshot
            }

            // OSC-driven metadata is dirty-channel — null means "unchanged
            // since last poll", not "absent". Persist the last seen value.
            bridge.nativePollTitle(ghosttyHandle)?.let { title = it }
            bridge.nativePollPwd(ghosttyHandle)?.let { pwd = it }
            // Terminal bells are a pulse counter, not a level: drain every tick so a
            // burst can't accumulate, and emit a coalesced chime count for the
            // spatial layer (which plays it from this pane's 3D position).
            runCatching { bridge.nativeDrainBellCount(ghosttyHandle) }
                .getOrNull()
                ?.let(BellChimePolicy::chimesToEmit)
                ?.takeIf { it > 0 }
                ?.let { _bellEvents.tryEmit(it) }
            val current = _state.value
            if (current is SessionState.Connected) {
                _state.value = current.copy(title = title, pwd = pwd)
            }
        } catch (e: Exception) {
            Log.w(TAG, "emitSnapshot failed: ${e.message}")
        }
    }

    private fun scheduleReconnect(reason: String) {
        if (disconnectRequested) {
            _state.value = SessionState.Disconnected
            return
        }
        val payload = lastPayload ?: run {
            _state.value = SessionState.Disconnected
            return
        }
        if (reconnectJob?.isActive == true) return
        GxrLog.d(TAG) { "pane $paneId scheduling reconnect: $reason" }
        reconnectJob = scope.launch {
            readJob?.cancel()
            readJob = null
            // Drain any stale wake so the first backoff isn't instantly
            // skipped by a signal left over from an earlier cycle. The
            // channel is CONFLATED, so it holds at most one pending signal.
            reconnectWake.tryReceive()
            var attempt = 0
            while (currentCoroutineContext().isActive && !disconnectRequested) {
                attempt += 1
                _state.value = SessionState.Reconnecting(attempt)
                // Jittered exponential backoff (see [ReconnectPolicy]). The
                // wait is interruptible: onNetworkAvailable() wakes it so a
                // pane reconnects the instant Wi-Fi returns instead of
                // sleeping out the full delay.
                val delayMs = reconnectPolicy.delayMsFor(attempt)
                withTimeoutOrNull(delayMs) { reconnectWake.receive() }
                if (disconnectRequested) break
                try {
                    openGhosttySurface()
                    connectTransport(payload, lastKey)
                    lastLivenessMs = System.currentTimeMillis()
                    _state.value = SessionState.Connected(title = title, pwd = pwd)
                    startReadLoop()
                    bootstrapCommand?.let(::submitCommand)
                    return@launch
                } catch (e: UnsatisfiedLinkError) {
                    // SSH symbols vanished between attempts (or were never
                    // there). No point retrying — give up cleanly with
                    // the same one-shot log gating connect() uses.
                    logSshUnavailable(e)
                    lastPayload = null
                    _state.value = SessionState.Failed(SSH_UNAVAILABLE_MESSAGE)
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "reconnect attempt $attempt failed: ${e.message}")
                    closeTransportQuietly("after reconnect failure")
                    if (ghosttyHandle != 0L) {
                        bridge.nativeDestroy(ghosttyHandle)
                        ghosttyHandle = 0L
                    }
                    snapshotScheduled = false
                    if (!reconnectPolicy.shouldRetry(attempt)) {
                        _state.value =
                            SessionState.Failed("reconnect gave up after $attempt attempts: ${e.message}")
                        return@launch
                    }
                }
            }
            if (disconnectRequested) _state.value = SessionState.Disconnected
        }
    }

    private fun tearDown() {
        readJob?.cancel()
        readJob = null
        if (transportConnected) {
            closeTransportQuietly("during teardown")
        }
        if (ghosttyHandle != 0L) {
            bridge.nativeDestroy(ghosttyHandle)
            ghosttyHandle = 0L
        }
        snapshotScheduled = false
    }
}
