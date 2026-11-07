package com.ghosttyxr.app.terminal.session

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.util.UUID

data class SessionSummary(
    val id: String,
    val savedAtEpochMs: Long,
    val paneCount: Int,
)

/** Durable, local-only session snapshots. SSH credentials stay in [PairedMacStore]. */
object SessionSnapshotStore {
    private const val PREFS_NAME = "ghosttyxr_session_snapshots"
    private const val SNAPSHOTS_KEY = "snapshots"
    private const val ACTIVE_ID_KEY = "active_id"
    private const val MAX_SNAPSHOTS = 10

    private val json = Json { ignoreUnknownKeys = true }
    private var prefs: SharedPreferences? = null
    private val _summaries = MutableStateFlow<List<SessionSummary>>(emptyList())
    val summaries: StateFlow<List<SessionSummary>> = _summaries.asStateFlow()

    @Volatile
    var activeSessionId: String? = null
        private set

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val loadedPrefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = loadedPrefs
        activeSessionId = loadedPrefs.getString(ACTIVE_ID_KEY, null)
        publish(loadAll(loadedPrefs))
    }

    fun startFresh(): String {
        val id = UUID.randomUUID().toString()
        activeSessionId = id
        prefs?.edit()?.putString(ACTIVE_ID_KEY, id)?.apply()
        return id
    }

    fun resume(id: String): SessionSnapshot? {
        val snapshot = load(id) ?: return null
        activeSessionId = id
        prefs?.edit()?.putString(ACTIVE_ID_KEY, id)?.apply()
        return snapshot
    }

    @Synchronized
    fun save(snapshot: SessionSnapshot) {
        val current = prefs ?: return
        val snapshots = loadAll(current)
        val existingSavedAt = snapshots.firstOrNull { it.id == snapshot.id }?.savedAtEpochMs
        if (existingSavedAt != null && existingSavedAt > snapshot.savedAtEpochMs) {
            return
        }
        val updated = (snapshots.filterNot { it.id == snapshot.id } + snapshot)
            .sortedByDescending { it.savedAtEpochMs }
            .take(MAX_SNAPSHOTS)
        current.edit()
            .putString(SNAPSHOTS_KEY, encodeSnapshots(updated))
            .putString(ACTIVE_ID_KEY, snapshot.id)
            .apply()
        activeSessionId = snapshot.id
        publish(updated)
    }

    fun load(id: String): SessionSnapshot? = prefs?.let(::loadAll)?.firstOrNull { it.id == id }

    @Synchronized
    fun remove(id: String) {
        val current = prefs ?: return
        val updated = loadAll(current).filterNot { it.id == id }
        val editor = current.edit().putString(SNAPSHOTS_KEY, encodeSnapshots(updated))
        if (activeSessionId == id) {
            editor.remove(ACTIVE_ID_KEY)
            activeSessionId = null
        }
        editor.apply()
        publish(updated)
    }

    @Synchronized
    fun clear() {
        prefs?.edit()?.remove(SNAPSHOTS_KEY)?.remove(ACTIVE_ID_KEY)?.apply()
        activeSessionId = null
        publish(emptyList())
    }

    private fun loadAll(prefs: SharedPreferences): List<SessionSnapshot> {
        val raw = prefs.getString(SNAPSHOTS_KEY, null) ?: return emptyList()
        return runCatching {
            json.parseToJsonElement(raw).jsonArray.mapNotNull { sessionSnapshotFromJson(it.jsonObject) }
        }.getOrDefault(emptyList()).sortedByDescending { it.savedAtEpochMs }
    }

    private fun encodeSnapshots(snapshots: List<SessionSnapshot>): String =
        JsonArray(snapshots.map(SessionSnapshot::toJson)).toString()

    private fun publish(snapshots: List<SessionSnapshot>) {
        _summaries.value = snapshots.map {
            SessionSummary(id = it.id, savedAtEpochMs = it.savedAtEpochMs, paneCount = it.paneCount)
        }
    }
}
