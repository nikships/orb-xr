package com.ghosttyxr.app.terminal.session

import android.app.Application
import android.content.Context
import com.ghosttyxr.app.qr.PairingPayload
import com.ghosttyxr.app.qr.QrPlacementAnchor
import com.ghosttyxr.app.terminal.TerminalSnapshot
import com.ghosttyxr.app.terminal.input.MappedKey
import com.ghosttyxr.app.terminal.handoff.HostSessionRef
import com.ghosttyxr.app.terminal.ssh.SshKey
import com.ghosttyxr.app.terminal.ssh.VerifiedHostAccess
import com.ghosttyxr.app.terminal.spatial.PaneLayoutStore
import com.ghosttyxr.app.terminal.theme.GhosttyTheme
import com.ghosttyxr.app.util.GxrLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "DefaultCockpitLifecycle"

/**
 * Production [CockpitLifecycle] facade. Holds the live [TerminalSessionEngine]
 * instances backing the adaptive grid (kept as an internal implementation
 * detail — never exposed) and wraps each in an [EngineCockpitPane].
 *
 * Owns the cockpit lifecycle and the session-related work formerly split between
 * repository, capture, and activity layers:
 * fresh/resume startup selection, pane add/remove/focus/disconnect, host
 * termination, and durable snapshot capture scheduling (including seeding the
 * Full Space flag *before* capture starts on resume, so a debounced flush
 * can't clobber it with the still-windowed live value).
 *
 * Starts with [INITIAL_PANES]; the user can spawn up to [MAX_PANES] and remove
 * down to a floor of one. Engines are constructed eagerly so each one's
 * snapshot/state flows exist before any connection is attempted.
 *
 * The [persistence] and [hostTermination] ports keep this class testable with
 * in-memory fakes — no `SharedPreferences`/Android runtime required to unit
 * test lifecycle/state-selection behavior.
 */
class DefaultCockpitLifecycle(
    override val hostAccess: VerifiedHostAccess,
    private val persistence: SnapshotPersistence,
    private val hostTermination: HostTermination,
    private val networkMonitor: NetworkMonitor,
    private val engineFactory: (Int) -> TerminalSessionEngine,
    private val lifecycleScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    // Overridable so tests can make background work (snapshot save, cwd probe,
    // agent probe) synchronous/deterministic instead of racing a real IO thread.
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val captureDebounceMs: Long = CAPTURE_DEBOUNCE_MS,
) : CockpitLifecycle {

    // Mutable engine list guarded by [enginesLock]. Each engine is built with a stable
    // monotonic id (its [TerminalSessionEngine.paneId], never reused) which becomes the
    // pane's public [PaneId] — the UI addresses panes by that identity, not by their
    // (shifting) position in the grid.
    private val enginesLock = Any()
    private val jobsLock = Any()
    private var nextSeq: Int = 0
    private val engines: MutableList<TerminalSessionEngine> = mutableListOf()

    // Last credentials + cockpit id used to connect; new panes auto-connect with them.
    @Volatile
    private var lastPayload: PairingPayload? = null

    @Volatile
    private var lastKey: SshKey? = null

    @Volatile
    private var activeCockpitId: String? = null

    override val currentCockpitId: String? get() = activeCockpitId

    private val _agentResumes = MutableStateFlow<Map<Long, AgentResume>>(emptyMap())
    private val agentSessionProbe = AgentSessionProbe(hostAccess)
    private val paneCwdProbe = PaneCwdProbe(hostAccess)
    private var agentProbeJob: Job? = null

    private val _state = MutableStateFlow<CockpitState>(CockpitState.NeedsPairing)
    override val state: StateFlow<CockpitState> = _state.asStateFlow()

    private val _focusedPaneId = MutableStateFlow<PaneId?>(null)

    // Live Home/Full Space flag (persisted into every capture) and the one-shot
    // restore signal consumed by the UI after [resume].
    private val _liveFullSpace = MutableStateFlow(false)
    private val _pendingFullSpaceRestore = MutableStateFlow(false)
    override val pendingFullSpaceRestore: StateFlow<Boolean> = _pendingFullSpaceRestore.asStateFlow()

    private var captureJob: Job? = null

    // Stop-barrier for durable capture (ported from #78): after [stopCapture]
    // returns, no pre-stop snapshot can still land — a late fire-and-forget
    // save would otherwise resurrect a handed-off cockpit in the resume picker.
    private val saveJobsLock = Any()
    private val saveJobs = mutableListOf<Job>()
    private val persistGate = Any()
    private val generation = AtomicInteger(0)

    init {
        synchronized(enginesLock) {
            repeat(INITIAL_PANES) { engines.add(newEngine()) }
        }
        networkMonitor.start()
        val wasOnline = AtomicBoolean(networkMonitor.isOnline.value)
        networkMonitor.isOnline
            .onEach { online ->
                val wentOnline = online && wasOnline.compareAndSet(false, true)
                if (wentOnline) {
                    enginesSnapshot().forEach { it.onNetworkAvailable() }
                } else if (!online) {
                    wasOnline.set(false)
                }
            }
            .launchIn(lifecycleScope)
    }

    private fun newEngine(): TerminalSessionEngine = engineFactory(nextSeq++)

    private fun enginesSnapshot(): List<TerminalSessionEngine> =
        synchronized(enginesLock) { engines.toList() }

    private fun engineFor(id: PaneId): TerminalSessionEngine? =
        enginesSnapshot().firstOrNull { it.paneId.toLong() == id.value }

    private fun publishActive() {
        val panes = enginesSnapshot().map { EngineCockpitPane(it) }
        _state.value = CockpitState.Active(panes = panes, focusedPaneId = _focusedPaneId.value)
    }

    override fun start(paired: PairedCredentials?) {
        if (paired == null) {
            _state.value = CockpitState.NeedsPairing
            return
        }
        val summaries = persistence.summaries.value
        if (summaries.isNotEmpty()) {
            _state.value = CockpitState.ResumePicker(summaries)
        } else {
            startFresh(paired)
        }
    }

    override fun startFresh(paired: PairedCredentials) {
        val sessionId = persistence.startFresh()
        connectFresh(paired, sessionId)
    }

    private fun connectFresh(paired: PairedCredentials, cockpitId: String) {
        GxrLog.d(TAG) { "connectFresh cockpitId=$cockpitId" }
        lastPayload = paired.payload
        lastKey = paired.key
        activeCockpitId = cockpitId
        _agentResumes.value = emptyMap()
        // Fresh cockpits launch straight into XR Full Space (the immersive cockpit
        // is the product default). Resume sessions instead honor the saved flag.
        // Seed both fields before capture starts so the first debounced flush
        // records fullSpace=true and the UI's pending-restore effect calls
        // scene.requestFullSpace() once the XR scene is ready.
        _liveFullSpace.value = true
        _pendingFullSpaceRestore.value = true
        // Fresh cockpit: drop prior drag/resize state, then apply the QR placement seed (when a
        // scan captured the Mac display's 3D pose) so the primary pane starts next to the
        // physical display instead of the grid origin. A stale seed never survives: take()
        // consumes it, and resume() clears it so saved-snapshot layout always wins there.
        val placementSeed = QrPlacementAnchor.take()
        if (placementSeed == null) {
            PaneLayoutStore.restore(offsets = emptyMap(), sizes = emptyMap())
        } else {
            GxrLog.d(TAG) {
                "applying QR placement seed x=${placementSeed.xMeters} " +
                    "y=${placementSeed.yMeters} z=${placementSeed.zMeters}"
            }
            val current = enginesSnapshot()
            val primaryKey = current.firstOrNull()?.paneId?.toLong()
            PaneLayoutStore.restore(
                offsets = if (primaryKey != null) {
                    mapOf(primaryKey to placementSeed.primaryPaneOffset())
                } else {
                    emptyMap()
                },
                sizes = emptyMap(),
            )
        }
        val current = enginesSnapshot()
        current.forEach { engine ->
            engine.setHostSessionPaneKey(engine.paneId.toLong())
            val bootstrap = HostSessionBootstrap.command(
                HostSessionBootstrap.sessionName(cockpitId, engine.hostSessionPaneKey),
            )
            engine.connect(paired.payload, paired.key, bootstrap)
        }
        synchronized(enginesLock) {
            _focusedPaneId.value = current.firstOrNull()?.let { PaneId(it.paneId.toLong()) }
        }
        restartAgentSessionProbe()
        publishActive()
        startCapture()
        flushCapture()
    }

    override fun resume(paired: PairedCredentials, sessionId: String) {
        val snapshot = persistence.resume(sessionId) ?: return
        // A scan that led to resume (not a fresh cockpit) leaves a stale placement seed behind;
        // the saved snapshot layout is authoritative, so drop the seed unconditionally.
        QrPlacementAnchor.clear()
        restoreEngines(snapshot, paired)
        val current = enginesSnapshot()
        val focusIndex = snapshot.focusedPaneIndex.coerceIn(0, (current.size - 1).coerceAtLeast(0))
        synchronized(enginesLock) {
            _focusedPaneId.value = current.getOrNull(focusIndex)?.let { PaneId(it.paneId.toLong()) }
        }
        // Seed the live Full Space flag from the snapshot *before* capture starts —
        // otherwise the first debounced flush would clobber a saved fullSpace=true
        // with the still-windowed live value before the system finishes expanding.
        _liveFullSpace.value = snapshot.fullSpace
        _pendingFullSpaceRestore.value = snapshot.fullSpace
        restartAgentSessionProbe()
        publishActive()
        startCapture()
        flushCapture()
    }

    private fun restoreEngines(snapshot: SessionSnapshot, paired: PairedCredentials) {
        val targetCount = snapshot.panes.size.coerceIn(1, MAX_PANES)
        val removed = mutableListOf<TerminalSessionEngine>()
        synchronized(enginesLock) {
            while (engines.size > targetCount) removed += engines.removeAt(engines.lastIndex)
            while (engines.size < targetCount) engines += newEngine()
            nextSeq = maxOf(
                nextSeq,
                (snapshot.panes.maxOfOrNull(PaneSnapshot::paneKey)?.plus(1) ?: 0L)
                    .coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            )
        }
        removed.forEach(TerminalSessionEngine::close)
        lastPayload = paired.payload
        lastKey = paired.key
        activeCockpitId = snapshot.id
        _agentResumes.value = snapshot.panes.mapNotNull { pane ->
            pane.agentResume?.let { pane.paneKey to it }
        }.toMap()
        val current = enginesSnapshot()
        PaneLayoutStore.restore(
            offsets = current.zip(snapshot.panes).associate { (engine, pane) ->
                engine.paneId.toLong() to pane.offset
            },
            sizes = current.zip(snapshot.panes).mapNotNull { (engine, pane) ->
                pane.size?.let { engine.paneId.toLong() to it }
            }.toMap(),
        )
        current.zip(snapshot.panes).forEach { (engine, pane) ->
            engine.setHostSessionPaneKey(pane.paneKey)
            engine.connect(
                payload = paired.payload,
                key = paired.key,
                bootstrapCommand = HostSessionBootstrap.restoreCommand(
                    HostSessionBootstrap.sessionName(snapshot.id, pane.paneKey),
                    pane.agentResume,
                ),
            )
        }
    }

    override fun addPane(launchCommand: String?, inheritFrom: PaneId?): PaneId? {
        // Resolve the source pane *before* inserting the new engine so it still
        // points at an existing pane (given, or the previous last pane as fallback).
        val (source, engine) = synchronized(enginesLock) {
            val source = if (engines.isEmpty()) {
                null
            } else {
                val sourceEngine = inheritFrom
                    ?.let { id -> engines.firstOrNull { it.paneId.toLong() == id.value } }
                    ?: engines.last()
                SourcePane(
                    hostSessionPaneKey = sourceEngine.hostSessionPaneKey,
                    oscWorkingDirectory = WorkingDirectory.normalize(sourceEngine.workingDirectory()),
                )
            }
            if (engines.size >= MAX_PANES) return null
            val e = newEngine()
            engines.add(e)
            source to e
        }
        val payload = lastPayload
        val cockpitId = activeCockpitId
        if (payload != null) {
            val key = lastKey
            // Connect off the caller thread: the source pane's live cwd comes from a
            // short-lived SSH round-trip to the Mac's tmux server. OSC 7 is only a
            // fallback (docs/cwd-routing-handoff.md).
            lifecycleScope.launch(ioDispatcher) {
                val probedCwd = if (cockpitId != null && source != null) {
                    try {
                        paneCwdProbe.probe(
                            payload = payload,
                            key = key,
                            sessionName = HostSessionBootstrap.sessionName(cockpitId, source.hostSessionPaneKey),
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        GxrLog.w(TAG, "Failed to probe working directory", error)
                        null
                    }
                } else {
                    null
                }
                val inheritedCwd = probedCwd ?: source?.oscWorkingDirectory
                val bootstrap = cockpitId?.let { id ->
                    HostSessionBootstrap.command(
                        sessionName = HostSessionBootstrap.sessionName(id, engine.hostSessionPaneKey),
                        workingDirectory = inheritedCwd,
                    )
                }
                engine.connect(
                    payload = payload,
                    key = key,
                    bootstrapCommand = bootstrap,
                    // The tmux bootstrap already carries the cwd via `new-session -c`;
                    // only the plain-shell path (no bootstrap) needs the typed `cd`.
                    postConnectCommand = WorkingDirectory.postConnectCommand(
                        workingDirectory = inheritedCwd.takeIf { bootstrap == null },
                        launchCommand = launchCommand,
                    ),
                )
            }
        }
        restartAgentSessionProbe()
        publishActive()
        return PaneId(engine.paneId.toLong())
    }

    override fun removePane(id: PaneId) {
        val removed = synchronized(enginesLock) {
            if (engines.size <= 1) return
            val idx = engines.indexOfFirst { it.paneId.toLong() == id.value }
            if (idx < 0) return
            val removedEngine = engines.removeAt(idx)
            if (_focusedPaneId.value == id) {
                val clampedIdx = idx.coerceIn(0, engines.size - 1)
                _focusedPaneId.value = engines.getOrNull(clampedIdx)?.let { PaneId(it.paneId.toLong()) }
            }
            removedEngine
        }
        GxrLog.d(TAG) { "removePane $id" }
        PaneLayoutStore.remove(removed.paneId.toLong())
        removed.close()
        restartAgentSessionProbe()
        publishActive()
    }

    override fun focusPane(id: PaneId) {
        val isActive = synchronized(enginesLock) {
            if (engines.none { it.paneId.toLong() == id.value }) return
            _focusedPaneId.value = id
            _state.value is CockpitState.Active
        }
        if (isActive) publishActive()
        flushCapture()
    }

    override fun disconnectPane(id: PaneId) {
        engineFor(id)?.disconnect()
    }

    override fun disconnectAllPanes() {
        synchronized(jobsLock) {
            agentProbeJob?.cancel()
            agentProbeJob = null
        }
        enginesSnapshot().forEach { it.disconnect() }
        _state.value = CockpitState.NeedsPairing
    }

    override fun liveHostSessions(): List<HostSessionRef> {
        val cockpitId = activeCockpitId ?: return emptyList()
        return enginesSnapshot().mapNotNull { engine ->
            val state = engine.state.value as? SessionState.Connected ?: return@mapNotNull null
            HostSessionRef(
                name = HostSessionBootstrap.sessionName(cockpitId, engine.hostSessionPaneKey),
                title = state.title,
            )
        }
    }

    override fun preparePostHandoffCockpit(newCockpitId: String) {
        // Detach-only reset: the Mac now owns the attaches. Unlike
        // disconnectAllPanes(), the cockpit stays Active/paired with one
        // disconnected pane so the next spawn auto-connects, and we never call
        // HostSessionTerminator — handoff must never destroy host agent work.
        synchronized(jobsLock) {
            agentProbeJob?.cancel()
            agentProbeJob = null
        }
        enginesSnapshot().forEach { it.disconnect() }
        _agentResumes.value = emptyMap()
        val removed = synchronized(enginesLock) {
            val out = mutableListOf<TerminalSessionEngine>()
            while (engines.size > 1) out += engines.removeAt(engines.lastIndex)
            _focusedPaneId.value = engines.firstOrNull()?.let { PaneId(it.paneId.toLong()) }
            out
        }
        removed.forEach { engine ->
            PaneLayoutStore.remove(engine.paneId.toLong())
            engine.close()
        }
        PaneLayoutStore.restore(offsets = emptyMap(), sizes = emptyMap())
        activeCockpitId = newCockpitId
        GxrLog.d(TAG) { "post-handoff reset: removed ${removed.size} pane(s), cockpit -> $newCockpitId" }
        publishActive()
    }

    override fun terminateSession(paired: PairedCredentials, sessionId: String): Boolean {
        val snapshot = persistence.load(sessionId) ?: return false
        val ended = hostTermination.terminate(paired.payload, paired.key, listOf(snapshot))
        if (ended) persistence.remove(sessionId)
        return ended
    }

    override fun terminateAllSessions(paired: PairedCredentials): Boolean {
        val ended = hostTermination.terminateAll(paired.payload, paired.key)
        if (ended) persistence.clear()
        return ended
    }

    override fun onFullSpaceChanged(isFullSpace: Boolean) {
        if (pendingFullSpaceRestore.value && !isFullSpace) return
        if (_liveFullSpace.value == isFullSpace) return
        _liveFullSpace.value = isFullSpace
        // Space toggles are not part of the capture combine below, so flush now.
        flushCapture()
    }

    override fun consumePendingFullSpaceRestore() {
        _pendingFullSpaceRestore.value = false
    }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private fun startCapture() {
        synchronized(jobsLock) {
            if (captureJob != null) return
            captureJob = lifecycleScope.launch {
                val sessionChanges = state.filterIsInstance<CockpitState.Active>().flatMapLatest { active ->
                    if (active.panes.isEmpty()) flowOf(Unit) else merge(*active.panes.map { it.state }.toTypedArray())
                }
                combine(
                    sessionChanges,
                    PaneLayoutStore.offsets,
                    PaneLayoutStore.sizes,
                    _agentResumes,
                ) { _, _, _, _ -> Unit }
                    .debounce(captureDebounceMs)
                    .collect { doCapture() }
            }
        }
    }

    override fun flushCapture() {
        if (synchronized(jobsLock) { captureJob == null }) return
        doCapture()
    }

    override fun stopCapture() {
        // Advance the generation first so any save that slips past the gate
        // sees a stale token and drops itself.
        generation.incrementAndGet()
        synchronized(jobsLock) {
            captureJob?.cancel()
            captureJob = null
        }
        val pending = synchronized(saveJobsLock) {
            saveJobs.toList().also { saveJobs.clear() }
        }
        pending.forEach { it.cancel() }
        // Barrier: block until any in-flight persist finishes, so a save that
        // was already inside the gate cannot land after we return.
        synchronized(persistGate) { /* wait out an in-flight persist */ }
    }

    private fun doCapture() {
        val id = persistence.activeSessionId ?: return
        val snapshot = buildSnapshot(id)
        val gen = generation.get()
        val saveJob = lifecycleScope.launch(ioDispatcher) {
            synchronized(persistGate) {
                if (gen != generation.get()) return@launch
                persistence.save(snapshot)
            }
        }
        synchronized(saveJobsLock) {
            saveJobs.removeAll { !it.isActive }
            saveJobs += saveJob
        }
    }

    private fun buildSnapshot(sessionId: String): SessionSnapshot {
        val current = enginesSnapshot()
        val focusIndex = current.indexOfFirst { it.paneId.toLong() == _focusedPaneId.value?.value }
            .let { if (it < 0) 0 else it }
        val panes = current.map { engine ->
            val connected = engine.state.value as? SessionState.Connected
            PaneSnapshot(
                paneKey = engine.hostSessionPaneKey,
                offset = PaneLayoutStore.offsetFor(engine.paneId.toLong()),
                size = PaneLayoutStore.sizeFor(engine.paneId.toLong()),
                title = connected?.title,
                workingDirectory = connected?.pwd,
                agentResume = _agentResumes.value[engine.hostSessionPaneKey],
            )
        }
        return SessionSnapshot(
            id = sessionId,
            savedAtEpochMs = System.currentTimeMillis(),
            focusedPaneIndex = focusIndex.coerceIn(0, (panes.size - 1).coerceAtLeast(0)),
            fullSpace = _liveFullSpace.value,
            panes = panes,
        )
    }

    private fun restartAgentSessionProbe() {
        synchronized(jobsLock) {
            agentProbeJob?.cancel()
            val payload = lastPayload
            val cockpitId = activeCockpitId
            if (payload == null || cockpitId == null) {
                agentProbeJob = null
                return
            }
            agentProbeJob = lifecycleScope.launch(ioDispatcher) {
                delay(AGENT_PROBE_INITIAL_DELAY_MS)
                while (isActive) {
                    val panes = enginesSnapshot().associate { engine ->
                        val paneKey = engine.hostSessionPaneKey
                        paneKey to HostSessionBootstrap.sessionName(cockpitId, paneKey)
                    }
                    try {
                        val discovered = agentSessionProbe.probe(payload, lastKey, panes)
                        if (discovered.isNotEmpty()) {
                            _agentResumes.value = _agentResumes.value + discovered
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        GxrLog.w(TAG, "Agent session probe failed", error)
                    }
                    delay(AGENT_PROBE_INTERVAL_MS)
                }
            }
        }
    }

    override fun close() {
        synchronized(jobsLock) {
            agentProbeJob?.cancel()
            agentProbeJob = null
            captureJob?.cancel()
            captureJob = null
        }
        networkMonitor.stop()
        lifecycleScope.cancel()
        enginesSnapshot().forEach { it.close() }
        synchronized(DefaultCockpitLifecycle) {
            if (instance === this) {
                instance = null
            }
        }
    }

    companion object {
        const val INITIAL_PANES = 1
        const val MAX_PANES = CockpitLifecycle.MAX_PANES
        private const val CAPTURE_DEBOUNCE_MS = 1_000L
        private const val AGENT_PROBE_INITIAL_DELAY_MS = 2_000L
        private const val AGENT_PROBE_INTERVAL_MS = 30_000L

        @Volatile
        private var instance: DefaultCockpitLifecycle? = null

        private fun create(application: Application): DefaultCockpitLifecycle {
            val appContext: Context = application.applicationContext
            SessionSnapshotStore.init(appContext)
            val hostAccess = VerifiedHostAccess()
            return DefaultCockpitLifecycle(
                hostAccess = hostAccess,
                persistence = SessionSnapshotStorePersistence(),
                hostTermination = HostSessionTermination(HostSessionTerminator(hostAccess)),
                networkMonitor = ConnectivityNetworkMonitor(appContext),
                engineFactory = { paneId -> TerminalSessionEngine(paneId = paneId, hostAccess = hostAccess) },
            )
        }

        fun getInstance(application: Application): DefaultCockpitLifecycle {
            return instance ?: synchronized(this) {
                instance ?: create(application).also { instance = it }
            }
        }
    }
}

/**
 * Identity + OSC 7 fallback cwd of the pane a new terminal inherits its working
 * directory from, captured under the engines lock before the new engine is added.
 */
private data class SourcePane(
    val hostSessionPaneKey: Long,
    val oscWorkingDirectory: String?,
)

/** [CockpitPane] wrapping a [TerminalSessionEngine]. Internal so tests in this
 *  module can exercise capability forwarding without a full [DefaultCockpitLifecycle]. */
internal class EngineCockpitPane(private val engine: TerminalSessionEngine) : CockpitPane {
    override val id: PaneId = PaneId(engine.paneId.toLong())

    override val state: StateFlow<SessionState> get() = engine.state

    override val snapshot: StateFlow<TerminalSnapshot> get() = engine.snapshot

    override val bellEvents: SharedFlow<Int> get() = engine.bellEvents

    override fun sendKey(key: MappedKey) = engine.sendKey(key)

    override fun sendInput(bytes: ByteArray) = engine.sendInput(bytes)

    override fun submitCommand(bytes: ByteArray) = engine.submitCommand(bytes)

    override fun scroll(delta: Int) = engine.scroll(delta)

    override fun scrollToActive() = engine.scrollToActive()

    override fun sendFocusEvent(focused: Boolean) = engine.sendFocusEvent(focused)

    override fun resize(cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) =
        engine.resize(cols, rows, cellWidthPx, cellHeightPx)

    override fun setColors(theme: GhosttyTheme) = engine.setColors(theme)
}
