package com.ghosttyxr.app.terminal.session

import com.ghosttyxr.app.terminal.spatial.PaneOffset
import com.ghosttyxr.app.terminal.spatial.PaneSize
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class SessionSnapshot(
    val id: String,
    val savedAtEpochMs: Long,
    val focusedPaneIndex: Int,
    /** Whether the cockpit was in XR Full Space (vs Home Space windowed) when saved. */
    val fullSpace: Boolean = false,
    val panes: List<PaneSnapshot>,
) {
    val paneCount: Int get() = panes.size
}

data class PaneSnapshot(
    val paneKey: Long,
    val offset: PaneOffset,
    /** Null means use the spatial grid default size (legacy snapshots omit size). */
    val size: PaneSize? = null,
    val title: String?,
    val workingDirectory: String?,
    val agentResume: AgentResume?,
)

fun SessionSnapshot.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("savedAtEpochMs", savedAtEpochMs)
    put("focusedPaneIndex", focusedPaneIndex)
    put("fullSpace", fullSpace)
    put("panes", buildJsonArray {
        panes.forEach { pane ->
            add(buildJsonObject {
                put("paneKey", pane.paneKey)
                put("x", pane.offset.x)
                put("y", pane.offset.y)
                put("z", pane.offset.z)
                pane.size?.let { size ->
                    put("width", size.widthPx)
                    put("height", size.heightPx)
                }
                pane.title?.let { put("title", it) }
                pane.workingDirectory?.let { put("workingDirectory", it) }
                pane.agentResume?.let {
                    put("agentKind", it.kind.name)
                    put("agentSessionId", it.sessionId)
                }
            })
        }
    })
}

fun sessionSnapshotFromJson(json: JsonObject): SessionSnapshot? {
    val id = json.string("id") ?: return null
    val savedAtEpochMs = json.long("savedAtEpochMs") ?: return null
    val focusedPaneIndex = json.long("focusedPaneIndex")?.toInt() ?: return null
    // Pre-field snapshots omit fullSpace; default to Home Space (safe launch mode).
    val fullSpace = json.boolean("fullSpace") ?: false
    val panes = json["panes"]?.jsonArray?.mapNotNull { element ->
        val pane = element.jsonObject
        val paneKey = pane.long("paneKey") ?: return@mapNotNull null
        val width = pane.float("width")
        val height = pane.float("height")
        val size = if (width != null && height != null) PaneSize(width, height) else null
        PaneSnapshot(
            paneKey = paneKey,
            offset = PaneOffset(
                x = pane.float("x") ?: 0f,
                y = pane.float("y") ?: 0f,
                z = pane.float("z") ?: 0f,
            ),
            size = size,
            title = pane.string("title"),
            workingDirectory = pane.string("workingDirectory"),
            agentResume = run {
                val kind = pane.string("agentKind") ?: return@run null
                val sessionId = pane.string("agentSessionId") ?: return@run null
                runCatching { AgentResume(AgentResume.Kind.valueOf(kind), sessionId) }.getOrNull()
            },
        )
    } ?: return null
    if (panes.isEmpty()) return null
    return SessionSnapshot(id, savedAtEpochMs, focusedPaneIndex, fullSpace, panes)
}

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

private fun JsonObject.float(key: String): Float? = string(key)?.toFloatOrNull()

private fun JsonObject.boolean(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull
