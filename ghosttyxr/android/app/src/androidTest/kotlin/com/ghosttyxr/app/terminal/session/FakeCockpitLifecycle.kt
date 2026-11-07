package com.ghosttyxr.app.terminal.session

import com.ghosttyxr.app.qr.PairingPayload
import com.ghosttyxr.app.terminal.TerminalSnapshot
import com.ghosttyxr.app.terminal.handoff.HostSessionRef
import com.ghosttyxr.app.terminal.input.MappedKey
import com.ghosttyxr.app.terminal.ssh.VerifiedHostAccess
import com.ghosttyxr.app.terminal.theme.GhosttyTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Capability-only cockpit fake for the flat-mode full-app Compose E2E suite. */
class FakeCockpitLifecycle(
    initialPanes: Int = 1,
) : CockpitLifecycle {
    private val lock = Any()
    private val transports = mutableListOf<FakeSshTransport>()
    private val panes = mutableListOf<FakeCockpitPane>()
    private var nextSeq = 0
    private var focusedPaneId: PaneId? = null
    private var credentials: PairedCredentials? = null
    private var activeCockpitId: String? = null

    override val hostAccess: VerifiedHostAccess =
        VerifiedHostAccess(transportFactory = { FakeSshTransport() })
    override val currentCockpitId: String? get() = activeCockpitId

    private val _state = MutableStateFlow<CockpitState>(CockpitState.NeedsPairing)
    override val state: StateFlow<CockpitState> = _state.asStateFlow()
    private val _pendingFullSpaceRestore = MutableStateFlow(false)
    override val pendingFullSpaceRestore: StateFlow<Boolean> = _pendingFullSpaceRestore.asStateFlow()

    init {
        synchronized(lock) { repeat(initialPanes) { newPaneLocked() } }
        publish()
    }

    fun transportFor(index: Int): FakeSshTransport = synchronized(lock) { transports[index] }

    fun stateFor(index: Int): StateFlow<SessionState> = activePanes()[index].state

    fun paneCount(): Int = activePanes().size

    fun showResumePicker(summaries: List<SessionSummary>) {
        _state.value = CockpitState.ResumePicker(summaries)
    }

    override fun start(paired: PairedCredentials?) {
        if (paired == null) {
            _state.value = CockpitState.NeedsPairing
        } else {
            startFresh(paired)
        }
    }

    override fun startFresh(paired: PairedCredentials) {
        credentials = paired
        synchronized(lock) { panes.toList() }.forEach { it.connect(paired.payload, paired.key) }
        publish()
    }

    override fun resume(paired: PairedCredentials, sessionId: String) = startFresh(paired)

    override fun addPane(launchCommand: String?, inheritFrom: PaneId?): PaneId? {
        val inheritedCwd = synchronized(lock) {
            val source = panes.firstOrNull { it.id == inheritFrom } ?: panes.lastOrNull()
            source?.workingDirectory()
        }
        val pane = synchronized(lock) {
            if (panes.size >= CockpitLifecycle.MAX_PANES) return null
            newPaneLocked()
        }
        credentials?.let { paired ->
            pane.connect(
                paired.payload,
                paired.key,
                WorkingDirectory.postConnectCommand(inheritedCwd, launchCommand),
            )
        }
        publish()
        return pane.id
    }

    override fun removePane(id: PaneId) {
        val removed = synchronized(lock) {
            if (panes.size <= 1) return
            val index = panes.indexOfFirst { it.id == id }
            if (index < 0) return
            transports.removeAt(index)
            panes.removeAt(index).also {
                if (focusedPaneId == id) {
                    focusedPaneId = panes[index.coerceIn(0, panes.lastIndex)].id
                }
            }
        }
        removed.close()
        publish()
    }

    override fun focusPane(id: PaneId) {
        if (activePanes().any { it.id == id }) {
            focusedPaneId = id
            publish()
        }
    }

    override fun disconnectPane(id: PaneId) {
        activePanes().firstOrNull { it.id == id }?.disconnect()
    }

    override fun disconnectAllPanes() {
        activePanes().forEach { it.disconnect() }
        _state.value = CockpitState.NeedsPairing
    }

    override fun terminateSession(paired: PairedCredentials, sessionId: String): Boolean = true

    override fun terminateAllSessions(paired: PairedCredentials): Boolean = true

    override fun onFullSpaceChanged(isFullSpace: Boolean) = Unit

    override fun consumePendingFullSpaceRestore() {
        _pendingFullSpaceRestore.value = false
    }

    override fun flushCapture() = Unit

    override fun liveHostSessions(): List<HostSessionRef> = emptyList()

    override fun preparePostHandoffCockpit(newCockpitId: String) {
        activePanes().forEach { it.disconnect() }
        val removed = synchronized(lock) {
            val out = mutableListOf<FakeCockpitPane>()
            while (panes.size > 1) {
                transports.removeAt(transports.lastIndex)
                out += panes.removeAt(panes.lastIndex)
            }
            focusedPaneId = panes.firstOrNull()?.id
            out
        }
        removed.forEach { it.close() }
        activeCockpitId = newCockpitId
        publish()
    }

    override fun stopCapture() = Unit

    override fun close() {
        synchronized(lock) { panes.toList() }.forEach { it.close() }
    }

    private fun newPaneLocked(): FakeCockpitPane {
        val transport = FakeSshTransport()
        return FakeCockpitPane(
            TerminalSessionEngine(
                paneId = nextSeq++,
                hostAccess = VerifiedHostAccess(
                    transportFactory = { transport },
                ),
            ),
        ).also {
            transports += transport
            panes += it
            if (focusedPaneId == null) focusedPaneId = it.id
        }
    }

    private fun activePanes(): List<FakeCockpitPane> = synchronized(lock) { panes.toList() }

    private fun publish() {
        val current = activePanes()
        val focused = focusedPaneId?.takeIf { id -> current.any { it.id == id } } ?: current.firstOrNull()?.id
        focusedPaneId = focused
        _state.value = CockpitState.Active(current, focused ?: return)
    }
}

private class FakeCockpitPane(
    private val engine: TerminalSessionEngine,
) : CockpitPane {
    override val id = PaneId(engine.paneId.toLong())
    override val state: StateFlow<SessionState> = engine.state
    override val snapshot: StateFlow<TerminalSnapshot> = engine.snapshot
    override val bellEvents: SharedFlow<Int> = engine.bellEvents

    fun connect(payload: PairingPayload, key: com.ghosttyxr.app.terminal.ssh.SshKey?, command: String? = null) =
        engine.connect(payload, key, command)

    fun disconnect() = engine.disconnect()

    fun close() = engine.close()

    fun workingDirectory() = WorkingDirectory.normalize(engine.workingDirectory())

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
