package com.ghosttyxr.app.terminal.session

import com.ghosttyxr.app.terminal.spatial.PaneOffset
import com.ghosttyxr.app.terminal.spatial.PaneSize
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSnapshotTest {

    @Test
    fun roundTripsPaneLayoutAndMetadata() {
        val snapshot = SessionSnapshot(
            id = "cockpit1",
            savedAtEpochMs = 1234L,
            focusedPaneIndex = 1,
            fullSpace = true,
            panes = listOf(
                PaneSnapshot(
                    paneKey = 7L,
                    offset = PaneOffset(1.5f, -2f, 3.25f),
                    size = PaneSize(widthPx = 820f, heightPx = 640f),
                    title = "Claude Code",
                    workingDirectory = "/Users/me/project",
                    agentResume = AgentResume(AgentResume.Kind.Droid, "session-1"),
                ),
            ),
        )

        val restored = sessionSnapshotFromJson(snapshot.toJson())

        assertNotNull(restored)
        assertEquals(snapshot, restored)
        assertTrue(restored!!.fullSpace)
        assertEquals(PaneSize(820f, 640f), restored.panes.single().size)
    }

    @Test
    fun roundTripsFullSpaceFalse() {
        val snapshot = SessionSnapshot(
            id = "cockpit1",
            savedAtEpochMs = 1234L,
            focusedPaneIndex = 0,
            fullSpace = false,
            panes = listOf(
                PaneSnapshot(
                    paneKey = 1L,
                    offset = PaneOffset(0f, 0f, 0f),
                    title = null,
                    workingDirectory = null,
                    agentResume = null,
                ),
            ),
        )

        val restored = sessionSnapshotFromJson(snapshot.toJson())

        assertNotNull(restored)
        assertFalse(restored!!.fullSpace)
        assertNull(restored.panes.single().size)
    }

    @Test
    fun missingFullSpaceDefaultsToFalse() {
        // Pre-field snapshots omit fullSpace; resume should stay in Home Space.
        val legacy = buildJsonObject {
            put("id", "legacy")
            put("savedAtEpochMs", 99L)
            put("focusedPaneIndex", 0)
            put("panes", kotlinx.serialization.json.buildJsonArray {
                add(
                    buildJsonObject {
                        put("paneKey", 1L)
                        put("x", 0f)
                        put("y", 0f)
                        put("z", 0f)
                    },
                )
            })
        }

        val restored = sessionSnapshotFromJson(legacy)

        assertNotNull(restored)
        assertFalse(restored!!.fullSpace)
        assertNull(restored.panes.single().size)
    }

    @Test
    fun missingSizeDefaultsToNull() {
        // Pre-field snapshots omit width/height; resume uses grid default size.
        val legacy = buildJsonObject {
            put("id", "legacy-size")
            put("savedAtEpochMs", 99L)
            put("focusedPaneIndex", 0)
            put("fullSpace", true)
            put("panes", kotlinx.serialization.json.buildJsonArray {
                add(
                    buildJsonObject {
                        put("paneKey", 2L)
                        put("x", 1f)
                        put("y", 2f)
                        put("z", 3f)
                    },
                )
            })
        }

        val restored = sessionSnapshotFromJson(legacy)

        assertNotNull(restored)
        assertNull(restored!!.panes.single().size)
        assertEquals(PaneOffset(1f, 2f, 3f), restored.panes.single().offset)
    }

    @Test
    fun partialSizeFieldsIgnored() {
        // Only width without height (or vice versa) is not a valid size.
        val partial = buildJsonObject {
            put("id", "partial")
            put("savedAtEpochMs", 1L)
            put("focusedPaneIndex", 0)
            put("panes", kotlinx.serialization.json.buildJsonArray {
                add(
                    buildJsonObject {
                        put("paneKey", 1L)
                        put("x", 0f)
                        put("y", 0f)
                        put("z", 0f)
                        put("width", 900f)
                    },
                )
            })
        }

        val restored = sessionSnapshotFromJson(partial)

        assertNotNull(restored)
        assertNull(restored!!.panes.single().size)
    }

    @Test
    fun rejectsSnapshotsWithoutPanes() {
        val json = SessionSnapshot(
            id = "cockpit1",
            savedAtEpochMs = 1234L,
            focusedPaneIndex = 0,
            panes = emptyList(),
        ).toJson()

        assertEquals(null, sessionSnapshotFromJson(json))
    }
}
