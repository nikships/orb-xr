package com.ghosttyxr.app.terminal.session

import kotlinx.coroutines.flow.StateFlow

/**
 * Durable-session persistence port used by [DefaultCockpitLifecycle]. Narrow
 * enough that host-JVM tests can supply an in-memory fake instead of the real
 * [SessionSnapshotStore] (which needs `SharedPreferences`/Android to init).
 */
interface SnapshotPersistence {
    val activeSessionId: String?

    val summaries: StateFlow<List<SessionSummary>>

    fun startFresh(): String

    fun resume(id: String): SessionSnapshot?

    fun load(id: String): SessionSnapshot?

    fun save(snapshot: SessionSnapshot)

    fun remove(id: String)

    fun clear()
}

/** Production [SnapshotPersistence] wrapping the [SessionSnapshotStore] singleton. */
class SessionSnapshotStorePersistence : SnapshotPersistence {
    override val activeSessionId: String?
        get() = SessionSnapshotStore.activeSessionId

    override val summaries: StateFlow<List<SessionSummary>>
        get() = SessionSnapshotStore.summaries

    override fun startFresh(): String = SessionSnapshotStore.startFresh()

    override fun resume(id: String): SessionSnapshot? = SessionSnapshotStore.resume(id)

    override fun load(id: String): SessionSnapshot? = SessionSnapshotStore.load(id)

    override fun save(snapshot: SessionSnapshot) = SessionSnapshotStore.save(snapshot)

    override fun remove(id: String) = SessionSnapshotStore.remove(id)

    override fun clear() = SessionSnapshotStore.clear()
}
