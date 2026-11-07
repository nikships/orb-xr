package com.ghosttyxr.app.terminal.input

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent

/**
 * Compose modifier that focuses the panel and dispatches every physical key event through
 * [KeyMapper] → [sink].
 *
 * Uses `onPreviewKeyEvent` (not `onKeyEvent`) so we see the event before the focus-traversal
 * system can consume Tab/arrow keys for focus movement — terminals own those keys.
 *
 * @param sink where mapped events are delivered
 * @param controllerMapper optional override to route controller button keycodes through a
 *   custom mapping (e.g. trigger → Enter). When null, only the standard [KeyMapper] runs.
 * @param requestFocusOnEnter when true (default), composes a [LaunchedEffect] that requests
 *   focus on first composition. Set false if the caller manages focus externally.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.terminalKeyInput(
    sink: TerminalInputSink,
    controllerMapper: ControllerButtonMapper? = ControllerButtonMapper.Default,
    requestFocusOnEnter: Boolean = true,
): Modifier = composed {
    val focusRequester = remember { FocusRequester() }
    if (requestFocusOnEnter) {
        LaunchedEffect(focusRequester) {
            // Compose's docs warn that requestFocus can throw if the node hasn't been
            // attached yet — wait one frame's worth via the suspend point.
            runCatching { focusRequester.requestFocus() }
        }
    }
    this
        .focusRequester(focusRequester)
        .focusable()
        .onPreviewKeyEvent { event ->
            val android = event.nativeKeyEvent
            // Try controller mapping first so a Trigger press is translated to Enter even
            // though the regular KeyMapper would also handle some controller buttons as
            // unknown / fall through.
            val mapped = controllerMapper?.map(android) ?: KeyMapper.fromEvent(android)
            if (mapped == null) {
                false
            } else {
                sink.onKey(mapped)
                true
            }
        }
}
