package com.ghosttyxr.app.terminal.spatial

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PaneOffset(
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
) {
    operator fun plus(other: PaneOffset): PaneOffset = PaneOffset(
        x = x + other.x,
        y = y + other.y,
        z = z + other.z,
    )
}

/**
 * Absolute spatial panel size in virtual pixels (same units as
 * [androidx.xr.compose.subspace.layout.SpatialResizeEvent.size]).
 * Missing entries mean "use the grid default width/height".
 */
data class PaneSize(
    val widthPx: Float,
    val heightPx: Float,
) {
    fun constrained(minWidthPx: Float, minHeightPx: Float): PaneSize = PaneSize(
        widthPx = widthPx.takeIf { it.isFinite() }?.coerceAtLeast(minWidthPx) ?: minWidthPx,
        heightPx = heightPx.takeIf { it.isFinite() }?.coerceAtLeast(minHeightPx) ?: minHeightPx,
    )
}

/**
 * Process-wide layout state keyed by stable terminal pane identity.
 *
 * The spatial grid owns rendering, but offsets and sizes live here so they
 * survive recomposition and can be included in a resumable cockpit snapshot.
 */
object PaneLayoutStore {
    private val _offsets = MutableStateFlow<Map<Long, PaneOffset>>(emptyMap())
    val offsets: StateFlow<Map<Long, PaneOffset>> = _offsets.asStateFlow()

    private val _sizes = MutableStateFlow<Map<Long, PaneSize>>(emptyMap())
    val sizes: StateFlow<Map<Long, PaneSize>> = _sizes.asStateFlow()

    fun offsetFor(paneKey: Long): PaneOffset = _offsets.value[paneKey] ?: PaneOffset()

    fun sizeFor(paneKey: Long): PaneSize? = _sizes.value[paneKey]

    fun move(paneKey: Long, delta: PaneOffset) {
        _offsets.value = _offsets.value + (paneKey to (offsetFor(paneKey) + delta))
    }

    fun resize(paneKey: Long, size: PaneSize) {
        _sizes.value = _sizes.value + (paneKey to size)
    }

    fun restore(
        offsets: Map<Long, PaneOffset>,
        sizes: Map<Long, PaneSize> = emptyMap(),
    ) {
        _offsets.value = offsets.toMap()
        _sizes.value = sizes.toMap()
    }

    fun remove(paneKey: Long) {
        _offsets.value = _offsets.value - paneKey
        _sizes.value = _sizes.value - paneKey
    }

    /** Test helper: clear all layout state between unit tests. */
    internal fun clear() {
        _offsets.value = emptyMap()
        _sizes.value = emptyMap()
    }
}
