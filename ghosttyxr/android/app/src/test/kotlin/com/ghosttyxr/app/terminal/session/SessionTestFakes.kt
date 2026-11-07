package com.ghosttyxr.app.terminal.session

import com.ghosttyxr.app.qr.PairingPayload
import com.ghosttyxr.app.terminal.ssh.SshKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared, host-JVM-only test doubles for [DefaultCockpitLifecycle] and
 * [EngineCockpitPane] tests — no Android runtime, no
 * network, no native `libghosttyxr.so` required.
 */

/** Deterministic, never-online [NetworkMonitor] — start/stop are no-ops. */
internal class FakeNetworkMonitor : NetworkMonitor {
    private val _isOnline = MutableStateFlow(false)
    override val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    override fun start() = Unit

    override fun stop() = Unit
}

/** In-memory [SnapshotPersistence] fake. */
internal class FakeSnapshotPersistence(
    seed: List<SessionSnapshot> = emptyList(),
) : SnapshotPersistence {
    private val snapshots = mutableMapOf<String, SessionSnapshot>().apply {
        seed.forEach { put(it.id, it) }
    }

    private val _summaries = MutableStateFlow(seed.map(::summaryOf))
    override val summaries: StateFlow<List<SessionSummary>> = _summaries.asStateFlow()

    override var activeSessionId: String? = null
        private set

    var nextFreshId: String = "fresh-1"

    val saved = mutableListOf<SessionSnapshot>()
    val removed = mutableListOf<String>()
    var cleared = false

    override fun startFresh(): String {
        activeSessionId = nextFreshId
        return nextFreshId
    }

    override fun resume(id: String): SessionSnapshot? {
        val snapshot = snapshots[id] ?: return null
        activeSessionId = id
        return snapshot
    }

    override fun load(id: String): SessionSnapshot? = snapshots[id]

    override fun save(snapshot: SessionSnapshot) {
        saved += snapshot
        snapshots[snapshot.id] = snapshot
        activeSessionId = snapshot.id
        _summaries.value = snapshots.values.map(::summaryOf)
    }

    override fun remove(id: String) {
        removed += id
        snapshots.remove(id)
        if (activeSessionId == id) activeSessionId = null
        _summaries.value = snapshots.values.map(::summaryOf)
    }

    override fun clear() {
        cleared = true
        snapshots.clear()
        activeSessionId = null
        _summaries.value = emptyList()
    }

    private fun summaryOf(snapshot: SessionSnapshot) = SessionSummary(
        id = snapshot.id,
        savedAtEpochMs = snapshot.savedAtEpochMs,
        paneCount = snapshot.paneCount,
    )
}

/** Configurable-outcome [HostTermination] fake that records every call. */
internal class FakeHostTermination(
    var terminateResult: Boolean = true,
    var terminateAllResult: Boolean = true,
) : HostTermination {
    val terminateCalls = mutableListOf<List<SessionSnapshot>>()
    var terminateAllCalls = 0

    override fun terminate(payload: PairingPayload, key: SshKey?, snapshots: List<SessionSnapshot>): Boolean {
        terminateCalls += snapshots
        return terminateResult
    }

    override fun terminateAll(payload: PairingPayload, key: SshKey?): Boolean {
        terminateAllCalls += 1
        return terminateAllResult
    }
}

internal fun testPairingPayload(host: String = "192.168.1.50") = PairingPayload(
    host = host,
    port = 22,
    user = "headset",
    token = "test-token",
    fingerprint = null,
)
