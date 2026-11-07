package com.ghosttyxr.app.ui.pane

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ghosttyxr.app.terminal.TerminalCanvas
import com.ghosttyxr.app.terminal.TerminalSnapshot
import com.ghosttyxr.app.terminal.buildDemoSnapshot
import com.ghosttyxr.app.terminal.input.PaneInputController
import com.ghosttyxr.app.terminal.input.terminalKeyInput
import com.ghosttyxr.app.terminal.input.terminalScrollback
import com.ghosttyxr.app.terminal.session.CockpitPane
import com.ghosttyxr.app.terminal.session.SessionState
import com.ghosttyxr.app.terminal.theme.GhosttyTokens
import com.ghosttyxr.app.terminal.voice.VoiceMicButton
import com.ghosttyxr.app.terminal.voice.command.LiveDictationController
import com.ghosttyxr.app.terminal.voice.command.LiveDictationState
import com.ghosttyxr.app.ui.chrome.GxrPanel
import com.ghosttyxr.app.ui.chrome.GxrText

/**
 * One terminal pane: status bar + live [TerminalCanvas], wired to its
 * [CockpitPane]. Keeps the last rendered frame on screen during a
 * reconnect blip (rather than flashing back to the demo placeholder) and
 * routes physical-keyboard input + scrollback gestures into the engine.
 */
@Composable
fun PaneSurface(
    pane: CockpitPane,
    paneId: Int,
    inputController: PaneInputController,
    isFocused: Boolean,
    backgroundOpacity: Float,
    fontSizeSp: Float,
    voiceController: LiveDictationController?,
) {
    val state by pane.state.collectAsState()
    val live by pane.snapshot.collectAsState()

    val placeholder = remember { buildDemoSnapshot() }
    // Keep the last rendered frame on screen while a pane reconnects so a blip
    // doesn't flash the terminal back to the demo placeholder.
    val displaySnapshot: TerminalSnapshot = when (state) {
        is SessionState.Connected, is SessionState.Reconnecting -> live
        else -> placeholder
    }
    var scrollCellHeightPx by remember { mutableStateOf(16f) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(GhosttyTokens.PaneCorner))
            .terminalKeyInput(inputController, requestFocusOnEnter = isFocused)
            .terminalScrollback(
                cellHeightPx = scrollCellHeightPx,
                enabled = state is SessionState.Connected,
                onScrollLines = pane::scroll,
            )
            .background(Color(displaySnapshot.defaultBgArgb).copy(alpha = backgroundOpacity.coerceIn(0f, 1f)))
    ) {
        PaneStatusBar(
            paneId = paneId,
            state = state,
            isFocused = isFocused,
            // Voice mic lives on the focused pane only — it's the pane that
            // receives the transcript, so the control sits where the user looks.
            voiceController = voiceController.takeIf { isFocused },
        )

        TerminalCanvas(
            snapshot = displaySnapshot,
            modifier = Modifier.weight(1f),
            fontSizeSp = fontSizeSp,
            backgroundOpacity = backgroundOpacity,
            paneLabel = "Terminal ${paneId + 1}",
            onResize = { cols, rows, cellWidth, cellHeight, _, _ ->
                scrollCellHeightPx = cellHeight.toFloat()
                pane.resize(cols, rows, cellWidth, cellHeight)
            },
        )
    }
}

@Composable
private fun PaneStatusBar(
    paneId: Int,
    state: SessionState,
    isFocused: Boolean,
    modifier: Modifier = Modifier,
    voiceController: LiveDictationController? = null,
) {
    val voiceState: LiveDictationState? = voiceController?.state?.collectAsState()?.value
    // Tint the focus border while listening so peripheral vision confirms which pane
    // will receive the text. Falls back to the normal accent focus border otherwise.
    val borderColor = when {
        voiceState is LiveDictationState.Accumulating -> GhosttyTokens.Online
        isFocused -> GhosttyTokens.Accent
        else -> null
    }
    GxrPanel(
        modifier = modifier.padding(10.dp),
        shape = RoundedCornerShape(GhosttyTokens.ChromeCorner),
        color = GhosttyTokens.ScrimStrong,
        borderColor = borderColor,
        borderWidth = 1.5.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor(state)),
            )
            GxrText(
                text = "Terminal ${paneId + 1}",
                style = GhosttyTokens.PaneTitle,
                color = if (isFocused) GhosttyTokens.Accent else GhosttyTokens.OnSurface,
            )
            GxrText(
                text = describeState(state),
                style = GhosttyTokens.StatusLabel,
                color = GhosttyTokens.OnSurfaceVariant,
            )
            if (voiceController != null && voiceState != null) {
                VoiceMicButton(
                    state = voiceState,
                    onToggle = voiceController::toggle,
                    onCancel = voiceController::cancel,
                    onAcknowledge = voiceController::acknowledge,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

/** Status-dot colour for a pane's connection state. */
fun statusColor(state: SessionState): Color = when (state) {
    is SessionState.Connected -> GhosttyTokens.Online
    SessionState.Connecting -> GhosttyTokens.Warning
    SessionState.Authenticated -> GhosttyTokens.Warning
    is SessionState.Reconnecting -> GhosttyTokens.Warning
    is SessionState.Failed -> GhosttyTokens.Danger
    SessionState.Disconnected -> GhosttyTokens.OnSurfaceFaint
}

/** Short human-readable label for a pane's connection state (shown in the status bar). */
fun describeState(state: SessionState): String = when (state) {
    SessionState.Disconnected -> "disconnected"
    SessionState.Connecting -> "connecting…"
    SessionState.Authenticated -> "authenticated"
    is SessionState.Connected -> state.title
        ?.trim()
        ?.takeIf { it.isNotBlank() && !it.isGeneratedTmuxTitle() }
        ?: "connected"
    is SessionState.Reconnecting -> "reconnecting #${state.attempt}"
    is SessionState.Failed -> "failed: ${state.error}"
}

private fun String.isGeneratedTmuxTitle(): Boolean =
    contains("tmux", ignoreCase = true) || startsWith("gxr-", ignoreCase = true)
