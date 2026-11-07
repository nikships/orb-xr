package com.ghosttyxr.app.terminal.session

import com.ghosttyxr.app.qr.QrPlacementAnchor
import com.ghosttyxr.app.qr.QrPlacementSeed
import com.ghosttyxr.app.terminal.spatial.PaneLayoutStore
import com.ghosttyxr.app.terminal.spatial.PaneOffset
import com.ghosttyxr.app.terminal.ssh.FakeSshTransport
import com.ghosttyxr.app.terminal.ssh.VerifiedHostAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Pure-JVM unit tests for [DefaultCockpitLifecycle] — the core cockpit
 * session-layer facade. Uses real [TerminalSessionEngine]s (their `connect()`
 * fails fast and harmlessly because `libghosttyxr.so` isn't loaded on the
 * host JVM) alongside in-memory [SnapshotPersistence]/[HostTermination]/
 * [NetworkMonitor] fakes, so no Android runtime or SSH round-trip is needed.
 */
class DefaultCockpitLifecycleTest {

    private fun buildLifecycle(
        persistence: FakeSnapshotPersistence = FakeSnapshotPersistence(),
        hostTermination: FakeHostTermination = FakeHostTermination(),
    ): DefaultCockpitLifecycle {
        val hostAccess = VerifiedHostAccess(
            transportFactory = {
                // Fail fast at handshake so connect() never reaches the native
                // bridge (no libghosttyxr.so on the host JVM) — mirrors the
                // pre-merge LibSsh2Transport fail-fast the state-machine
                // assertions relied on.
                FakeSshTransport(handshakeFailure = IllegalStateException("no SSH in JVM lifecycle tests"))
            },
        )
        return DefaultCockpitLifecycle(
            hostAccess = hostAccess,
            persistence = persistence,
            hostTermination = hostTermination,
            networkMonitor = FakeNetworkMonitor(),
            engineFactory = { paneId -> TerminalSessionEngine(paneId = paneId, hostAccess = hostAccess) },
            lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    private fun activeOf(lifecycle: DefaultCockpitLifecycle): CockpitState.Active =
        lifecycle.state.value as CockpitState.Active

    // ---- startup state selection ----------------------------------------

    @Test
    fun `start with no paired credentials selects NeedsPairing`() {
        val lifecycle = buildLifecycle()

        lifecycle.start(null)

        assertEquals(CockpitState.NeedsPairing, lifecycle.state.value)
        lifecycle.close()
    }

    @Test
    fun `start with paired credentials and durable snapshots selects ResumePicker`() {
        val existing = SessionSnapshot(
            id = "old-session",
            savedAtEpochMs = 1L,
            focusedPaneIndex = 0,
            fullSpace = false,
            panes = listOf(fakePaneSnapshot(paneKey = 0L)),
        )
        val lifecycle = buildLifecycle(persistence = FakeSnapshotPersistence(seed = listOf(existing)))

        lifecycle.start(PairedCredentials(testPairingPayload()))

        val state = lifecycle.state.value
        assertTrue("expected ResumePicker, got $state", state is CockpitState.ResumePicker)
        assertEquals(1, (state as CockpitState.ResumePicker).sessions.size)
        assertEquals("old-session", state.sessions.first().id)
        lifecycle.close()
    }

    @Test
    fun `start with paired credentials and no durable snapshots connects a fresh cockpit`() {
        val lifecycle = buildLifecycle()

        lifecycle.start(PairedCredentials(testPairingPayload()))

        val active = activeOf(lifecycle)
        assertEquals(1, active.panes.size)
        assertNotNull(active.focusedPaneId)
        assertEquals(active.panes.single().id, active.focusedPaneId)
        lifecycle.close()
    }

    @Test
    fun `fresh cockpit defaults to Full Space and primes the pending restore`() {
        val persistence = FakeSnapshotPersistence()
        val lifecycle = buildLifecycle(persistence = persistence)

        lifecycle.startFresh(PairedCredentials(testPairingPayload()))

        // Fresh sessions must request Full Space: the UI's pending-restore effect
        // consumes this to call scene.requestFullSpace() once the XR scene is ready.
        assertTrue(
            "fresh cockpit must prime pendingFullSpaceRestore",
            lifecycle.pendingFullSpaceRestore.value,
        )
        // The first capture must persist fullSpace=true so a subsequent resume
        // re-enters Full Space even if the system transition hasn't completed yet.
        lifecycle.flushCapture()
        assertTrue(
            "first fresh capture must save fullSpace=true",
            persistence.saved.last().fullSpace,
        )
        lifecycle.close()
    }

    // ---- resume + Full Space seeding order --------------------------------

    @Test
    fun `resume seeds fullSpace before capture can flush a stale value`() {
        val saved = SessionSnapshot(
            id = "resumed-session",
            savedAtEpochMs = 1L,
            focusedPaneIndex = 1,
            fullSpace = true,
            panes = listOf(fakePaneSnapshot(paneKey = 10L), fakePaneSnapshot(paneKey = 11L)),
        )
        val persistence = FakeSnapshotPersistence(seed = listOf(saved))
        val lifecycle = buildLifecycle(persistence = persistence)

        lifecycle.resume(PairedCredentials(testPairingPayload()), "resumed-session")

        // pendingFullSpaceRestore must reflect the saved flag immediately.
        assertTrue(lifecycle.pendingFullSpaceRestore.value)

        val capturesBeforeHomeSpaceReport = persistence.saved.size
        lifecycle.onFullSpaceChanged(false)
        assertEquals(
            "Home Space must not overwrite a pending Full Space restore",
            capturesBeforeHomeSpaceReport,
            persistence.saved.size,
        )

        // The very first capture after resume must already carry fullSpace=true —
        // it must never be seeded false and then flipped later.
        lifecycle.flushCapture()
        assertTrue("first post-resume capture must save fullSpace=true", persistence.saved.last().fullSpace)

        val active = activeOf(lifecycle)
        assertEquals(2, active.panes.size)
        assertEquals(active.panes[1].id, active.focusedPaneId)
        lifecycle.close()
    }

    @Test
    fun `consumePendingFullSpaceRestore clears the one shot flag`() {
        val saved = SessionSnapshot(
            id = "s",
            savedAtEpochMs = 1L,
            focusedPaneIndex = 0,
            fullSpace = true,
            panes = listOf(fakePaneSnapshot(paneKey = 0L)),
        )
        val lifecycle = buildLifecycle(persistence = FakeSnapshotPersistence(seed = listOf(saved)))
        lifecycle.resume(PairedCredentials(testPairingPayload()), "s")

        lifecycle.consumePendingFullSpaceRestore()

        assertFalse(lifecycle.pendingFullSpaceRestore.value)
        lifecycle.close()
    }

    // ---- pane floor / cap --------------------------------------------------

    @Test
    fun `addPane is capped at MAX_PANES`() {
        val lifecycle = buildLifecycle()
        lifecycle.start(PairedCredentials(testPairingPayload()))

        val ids = mutableListOf<PaneId>()
        repeat(DefaultCockpitLifecycle.MAX_PANES - 1) {
            ids += requireNotNull(lifecycle.addPane())
        }
        assertEquals(DefaultCockpitLifecycle.MAX_PANES, activeOf(lifecycle).panes.size)

        assertNull("cap of ${DefaultCockpitLifecycle.MAX_PANES} must reject a 7th pane", lifecycle.addPane())
        assertEquals(DefaultCockpitLifecycle.MAX_PANES, activeOf(lifecycle).panes.size)
        lifecycle.close()
    }

    @Test
    fun `removePane never drops below the floor of one pane`() {
        val lifecycle = buildLifecycle()
        lifecycle.start(PairedCredentials(testPairingPayload()))
        val onlyPane = activeOf(lifecycle).panes.single().id

        lifecycle.removePane(onlyPane)

        assertEquals(1, activeOf(lifecycle).panes.size)
        assertEquals(onlyPane, activeOf(lifecycle).panes.single().id)
        lifecycle.close()
    }

    // ---- focus clamping on removal -----------------------------------------

    @Test
    fun `removing the focused pane clamps focus to the pane at the same position`() {
        val lifecycle = buildLifecycle()
        lifecycle.start(PairedCredentials(testPairingPayload()))
        val first = activeOf(lifecycle).panes.single().id
        val second = requireNotNull(lifecycle.addPane())
        val third = requireNotNull(lifecycle.addPane())
        // panes are now [first, second, third]; focus the middle one.
        lifecycle.focusPane(second)
        assertEquals(second, activeOf(lifecycle).focusedPaneId)

        lifecycle.removePane(second)

        // second's slot is now occupied by what was `third`; focus must follow the slot.
        val active = activeOf(lifecycle)
        assertEquals(listOf(first, third), active.panes.map { it.id })
        assertEquals(third, active.focusedPaneId)
        lifecycle.close()
    }

    @Test
    fun `removing a pane that is not focused leaves focus untouched`() {
        val lifecycle = buildLifecycle()
        lifecycle.start(PairedCredentials(testPairingPayload()))
        val first = activeOf(lifecycle).panes.single().id
        val second = requireNotNull(lifecycle.addPane())
        lifecycle.focusPane(first)

        lifecycle.removePane(second)

        assertEquals(first, activeOf(lifecycle).focusedPaneId)
        lifecycle.close()
    }

    // ---- host termination + local snapshot deletion ------------------------

    @Test
    fun `terminateSession removes the local snapshot only when the host termination succeeds`() {
        val snapshot = SessionSnapshot(
            id = "s1",
            savedAtEpochMs = 1L,
            focusedPaneIndex = 0,
            fullSpace = false,
            panes = listOf(fakePaneSnapshot(paneKey = 0L)),
        )
        val persistence = FakeSnapshotPersistence(seed = listOf(snapshot))
        val hostTermination = FakeHostTermination(terminateResult = true)
        val lifecycle = buildLifecycle(persistence = persistence, hostTermination = hostTermination)

        val ok = lifecycle.terminateSession(PairedCredentials(testPairingPayload()), "s1")

        assertTrue(ok)
        assertEquals(listOf("s1"), persistence.removed)
        lifecycle.close()
    }

    @Test
    fun `terminateSession keeps the local snapshot when host termination fails`() {
        val snapshot = SessionSnapshot(
            id = "s1",
            savedAtEpochMs = 1L,
            focusedPaneIndex = 0,
            fullSpace = false,
            panes = listOf(fakePaneSnapshot(paneKey = 0L)),
        )
        val persistence = FakeSnapshotPersistence(seed = listOf(snapshot))
        val hostTermination = FakeHostTermination(terminateResult = false)
        val lifecycle = buildLifecycle(persistence = persistence, hostTermination = hostTermination)

        val ok = lifecycle.terminateSession(PairedCredentials(testPairingPayload()), "s1")

        assertFalse(ok)
        assertTrue("snapshot must survive a failed host termination", persistence.removed.isEmpty())
        assertNotNull(persistence.load("s1"))
        lifecycle.close()
    }

    @Test
    fun `terminateAllSessions clears every local snapshot only on success`() {
        val a = SessionSnapshot("a", 1L, 0, false, listOf(fakePaneSnapshot(0L)))
        val b = SessionSnapshot("b", 1L, 0, false, listOf(fakePaneSnapshot(0L)))
        val persistence = FakeSnapshotPersistence(seed = listOf(a, b))
        val hostTermination = FakeHostTermination(terminateAllResult = true)
        val lifecycle = buildLifecycle(persistence = persistence, hostTermination = hostTermination)

        val ok = lifecycle.terminateAllSessions(PairedCredentials(testPairingPayload()))

        assertTrue(ok)
        assertTrue(persistence.cleared)
        lifecycle.close()
    }

    @Test
    fun `terminateAllSessions keeps snapshots when the host termination fails`() {
        val a = SessionSnapshot("a", 1L, 0, false, listOf(fakePaneSnapshot(0L)))
        val persistence = FakeSnapshotPersistence(seed = listOf(a))
        val hostTermination = FakeHostTermination(terminateAllResult = false)
        val lifecycle = buildLifecycle(persistence = persistence, hostTermination = hostTermination)

        val ok = lifecycle.terminateAllSessions(PairedCredentials(testPairingPayload()))

        assertFalse(ok)
        assertFalse(persistence.cleared)
        lifecycle.close()
    }

    private fun fakePaneSnapshot(paneKey: Long) = PaneSnapshot(
        paneKey = paneKey,
        offset = com.ghosttyxr.app.terminal.spatial.PaneOffset(0f, 0f, 0f),
        size = null,
        title = null,
        workingDirectory = null,
        agentResume = null,
    )

    // ---- QR placement seed (cockpit auto-alignment) ---------------------

    @Test
    fun `fresh cockpit without a placement seed uses grid defaults`() {
        QrPlacementAnchor.clear()
        PaneLayoutStore.clear()
        val lifecycle = buildLifecycle()

        lifecycle.startFresh(PairedCredentials(testPairingPayload()))

        val active = activeOf(lifecycle)
        val primaryKey = active.panes.single().id.value
        assertEquals(
            PaneOffset(),
            PaneLayoutStore.offsetFor(primaryKey),
        )
        lifecycle.close()
    }

    @Test
    fun `fresh cockpit consumes the placement seed onto the primary pane`() {
        PaneLayoutStore.clear()
        QrPlacementAnchor.seed(
            QrPlacementSeed(
                xMeters = 0.5f,
                yMeters = 1.2f,
                zMeters = -1.0f,
                qrWidthMeters = 0.2f,
                qrHeightMeters = 0.2f,
            ),
        )
        val lifecycle = buildLifecycle()

        lifecycle.startFresh(PairedCredentials(testPairingPayload()))

        val active = activeOf(lifecycle)
        val primaryKey = active.panes.single().id.value
        val offset = PaneLayoutStore.offsetFor(primaryKey)
        assertEquals(0.5f + 0.1f + 0.35f, offset.x, 1e-6f)
        assertEquals(1.2f, offset.y, 1e-6f)
        assertEquals(-1.0f, offset.z, 1e-6f)
        // The seed is one-shot: a second fresh start falls back to grid defaults.
        lifecycle.close()
        PaneLayoutStore.clear()
        val second = buildLifecycle()
        second.startFresh(PairedCredentials(testPairingPayload()))
        val secondKey = (second.state.value as CockpitState.Active).panes.single().id.value
        assertEquals(
            PaneOffset(),
            PaneLayoutStore.offsetFor(secondKey),
        )
        second.close()
    }

    @Test
    fun `resume drops a stale placement seed and keeps snapshot layout`() {
        PaneLayoutStore.clear()
        QrPlacementAnchor.seed(
            QrPlacementSeed(
                xMeters = 9f,
                yMeters = 9f,
                zMeters = 9f,
                qrWidthMeters = 0.2f,
                qrHeightMeters = 0.2f,
            ),
        )
        val existing = SessionSnapshot(
            id = "old-session",
            savedAtEpochMs = 1L,
            focusedPaneIndex = 0,
            fullSpace = false,
            panes = listOf(fakePaneSnapshot(paneKey = 0L)),
        )
        val lifecycle = buildLifecycle(persistence = FakeSnapshotPersistence(seed = listOf(existing)))

        lifecycle.resume(PairedCredentials(testPairingPayload()), "old-session")

        assertNull(
            "resume must consume a stale placement seed",
            QrPlacementAnchor.take(),
        )
        lifecycle.close()
    }

    // ---- durable-capture stop barrier (ported from #78) ------------------
    //
    // Guards the handoff landmine: after [stopCapture] returns, no pre-stop
    // snapshot may still be persisted, so [TerminalHandoffController] can
    // remove() the handed-off cockpit without a late save resurrecting it in
    // the resume picker. These need a *real* multi-threaded dispatcher (a
    // blocked persist parks a thread on the monitor); [Dispatchers.Unconfined]
    // would inline the save and deadlock. Hence a dedicated barrier lifecycle.

    private val barrierScopes = mutableListOf<CoroutineScope>()

    private fun buildBarrierLifecycle(persistence: SnapshotPersistence): DefaultCockpitLifecycle {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        barrierScopes += scope
        val hostAccess = VerifiedHostAccess(
            transportFactory = {
                FakeSshTransport(handshakeFailure = IllegalStateException("no SSH in JVM barrier tests"))
            },
        )
        return DefaultCockpitLifecycle(
            hostAccess = hostAccess,
            persistence = persistence,
            hostTermination = FakeHostTermination(),
            networkMonitor = FakeNetworkMonitor(),
            engineFactory = { paneId -> TerminalSessionEngine(paneId = paneId, hostAccess = hostAccess) },
            lifecycleScope = scope,
            ioDispatcher = Dispatchers.IO,
            // Huge debounce so the auto-capture collector never fires on its
            // own — only the explicit connectFresh/flushCapture saves are in
            // flight, keeping the save count deterministic.
            captureDebounceMs = 10 * 60 * 1_000L,
        )
    }

    @org.junit.After
    fun cancelBarrierScopes() {
        barrierScopes.forEach { it.cancel() }
        barrierScopes.clear()
        // Placement-seed tests mutate process-wide state; clear so ordering can't leak it.
        QrPlacementAnchor.clear()
        PaneLayoutStore.clear()
    }

    @Test
    fun `stopCapture barriers past an in-flight persist`() {
        val persistence = BlockingSnapshotPersistence()
        val lifecycle = buildBarrierLifecycle(persistence)
        // connectFresh() ends with flushCapture() -> save A enters the gate and blocks.
        lifecycle.startFresh(PairedCredentials(testPairingPayload()))
        assertTrue("save A should have entered the gate", persistence.awaitSaveEntered())

        val stopReturned = CountDownLatch(1)
        thread { lifecycle.stopCapture(); stopReturned.countDown() }

        assertFalse(
            "stop must not return while a persist is in flight",
            stopReturned.await(200, TimeUnit.MILLISECONDS),
        )
        persistence.release()
        assertTrue("stop must return once the persist completes", stopReturned.await(5, TimeUnit.SECONDS))
        assertEquals(1, persistence.saveCount)
        lifecycle.close()
    }

    @Test
    fun `capture while running persists a snapshot`() {
        val persistence = BlockingSnapshotPersistence()
        val lifecycle = buildBarrierLifecycle(persistence)
        // connectFresh()'s built-in flush is save A.
        lifecycle.startFresh(PairedCredentials(testPairingPayload()))
        assertTrue(persistence.awaitSaveEntered())
        persistence.release()
        assertTrue("the flushed snapshot must persist", persistence.awaitSaved(1))
        lifecycle.close()
    }

    @Test
    fun `save queued behind an in-flight persist is dropped by stopCapture`() {
        val persistence = BlockingSnapshotPersistence()
        val lifecycle = buildBarrierLifecycle(persistence)
        // Save A (from connectFresh's flush) enters the gate and blocks.
        lifecycle.startFresh(PairedCredentials(testPairingPayload()))
        assertTrue(persistence.awaitSaveEntered())
        // Save B is launched and parks on the monitor waiting for A.
        lifecycle.flushCapture()

        val stopReturned = CountDownLatch(1)
        thread { lifecycle.stopCapture(); stopReturned.countDown() }
        // Give B time to start and park, and stop to reach the barrier.
        assertFalse(stopReturned.await(200, TimeUnit.MILLISECONDS))
        persistence.release()
        assertTrue(stopReturned.await(5, TimeUnit.SECONDS))
        // A lands; B is dropped by the generation bump (its cancel is a no-op
        // once it's blocked on the non-suspending monitor).
        assertEquals("exactly one persist survives stop", 1, persistence.saveCount)
        lifecycle.close()
    }

    @Test
    fun `flushCapture after stopCapture is a no-op`() {
        val persistence = BlockingSnapshotPersistence()
        val lifecycle = buildBarrierLifecycle(persistence)
        lifecycle.startFresh(PairedCredentials(testPairingPayload()))
        assertTrue(persistence.awaitSaveEntered())
        persistence.release()
        assertTrue(persistence.awaitSaved(1))

        lifecycle.stopCapture()
        val before = persistence.saveCount
        lifecycle.flushCapture()
        // Nothing new should persist: capture is disarmed, so a handed-off
        // cockpit stays removed.
        Thread.sleep(100)
        assertEquals(before, persistence.saveCount)
        lifecycle.close()
    }
}

/**
 * [SnapshotPersistence] whose [save] blocks on a release latch and counts every
 * entry, so a test can hold one persist inside the gate while racing a second
 * save and [DefaultCockpitLifecycle.stopCapture] against it.
 */
private class BlockingSnapshotPersistence : SnapshotPersistence {
    private val _summaries = MutableStateFlow<List<SessionSummary>>(emptyList())
    override val summaries: StateFlow<List<SessionSummary>> = _summaries.asStateFlow()

    override var activeSessionId: String? = null
        private set

    private val saveEntered = CountDownLatch(1)
    private val releaseLatch = CountDownLatch(1)
    private val _saveCount = AtomicInteger(0)
    val saveCount: Int get() = _saveCount.get()

    fun awaitSaveEntered(): Boolean = saveEntered.await(2, TimeUnit.SECONDS)

    fun awaitSaved(expected: Int): Boolean {
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            if (_saveCount.get() >= expected) return true
            Thread.sleep(10)
        }
        return _saveCount.get() >= expected
    }

    fun release() = releaseLatch.countDown()

    override fun startFresh(): String {
        activeSessionId = "barrier-cockpit"
        return activeSessionId!!
    }

    override fun resume(id: String): SessionSnapshot? = null

    override fun load(id: String): SessionSnapshot? = null

    override fun save(snapshot: SessionSnapshot) {
        saveEntered.countDown()
        releaseLatch.await(2, TimeUnit.SECONDS)
        _saveCount.incrementAndGet()
        activeSessionId = snapshot.id
    }

    override fun remove(id: String) {
        if (activeSessionId == id) activeSessionId = null
    }

    override fun clear() {
        activeSessionId = null
    }
}
