package com.ghosttyxr.app.terminal.input

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import kotlin.math.max

fun Modifier.terminalScrollback(
    cellHeightPx: Float,
    enabled: Boolean = true,
    onScrollLines: (Int) -> Unit,
): Modifier = composed {
    if (!enabled) return@composed this

    val currentOnScrollLines by rememberUpdatedState(onScrollLines)
    val accumulator = remember(cellHeightPx) { ScrollbackLineAccumulator(cellHeightPx) }
    val scrollableState = rememberScrollableState { deltaPx ->
        val lines = accumulator.consume(deltaPx)
        if (lines != 0) currentOnScrollLines(lines)
        deltaPx
    }

    this.scrollable(
        state = scrollableState,
        orientation = Orientation.Vertical,
    )
}

class ScrollbackLineAccumulator(cellHeightPx: Float) {
    private val cellHeightPx = max(1f, cellHeightPx.takeIf { it.isFinite() } ?: 1f)
    private var pendingPx = 0f

    fun consume(deltaPx: Float): Int {
        if (!deltaPx.isFinite()) return 0
        pendingPx += deltaPx
        val rawLines = (pendingPx / cellHeightPx).toInt()
        if (rawLines == 0) return 0
        pendingPx -= rawLines * cellHeightPx
        return -rawLines
    }
}
