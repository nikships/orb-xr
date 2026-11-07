package com.ghosttyxr.app.ui.resume

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.MovePolicy
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.movable
import androidx.xr.compose.subspace.layout.width
import com.ghosttyxr.app.terminal.session.SessionSummary
import com.ghosttyxr.app.terminal.theme.GhosttyTokens
import com.ghosttyxr.app.ui.RESUME_LIST_TEST_TAG
import com.ghosttyxr.app.ui.RESUME_SESSION_TEST_TAG_PREFIX
import com.ghosttyxr.app.ui.END_ALL_SESSIONS_TEST_TAG
import com.ghosttyxr.app.ui.END_SESSION_TEST_TAG_PREFIX
import com.ghosttyxr.app.ui.START_FRESH_TEST_TAG
import com.ghosttyxr.app.ui.chrome.GxrButton
import com.ghosttyxr.app.ui.chrome.GxrButtonStyle
import com.ghosttyxr.app.ui.chrome.GxrIconButton
import com.ghosttyxr.app.ui.chrome.GxrPanel
import com.ghosttyxr.app.ui.chrome.GxrText
import java.text.DateFormat
import java.util.Date

@Composable
fun SessionResumePanel(
    sessions: List<SessionSummary>,
    onResume: (String) -> Unit,
    onStartFresh: () -> Unit,
    onEndSession: (String) -> Unit = {},
    onEndAllSessions: () -> Unit = {},
    spatialEnabledOverride: Boolean?,
) {
    val isSpatial = spatialEnabledOverride ?: LocalSpatialCapabilities.current.isSpatialUiEnabled
    if (isSpatial) {
        Subspace {
            SpatialPanel(
                SubspaceModifier
                    .width(640.dp)
                    .height(520.dp)
                    .movable(movePolicy = MovePolicy.Default),
            ) {
                SessionResumeContent(
                    sessions = sessions,
                    onResume = onResume,
                    onStartFresh = onStartFresh,
                    onEndSession = onEndSession,
                    onEndAllSessions = onEndAllSessions,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    } else {
        SessionResumeContent(
            sessions = sessions,
            onResume = onResume,
            onStartFresh = onStartFresh,
            onEndSession = onEndSession,
            onEndAllSessions = onEndAllSessions,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun SessionResumeContent(
    sessions: List<SessionSummary>,
    onResume: (String) -> Unit,
    onStartFresh: () -> Unit,
    onEndSession: (String) -> Unit,
    onEndAllSessions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(GhosttyTokens.Background)
            .padding(24.dp)
            .testTag(RESUME_LIST_TEST_TAG),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GxrText("Resume your cockpit", style = GhosttyTokens.PaneTitle)
        GxrText(
            "Your panes reconnect to their saved host sessions.",
            style = GhosttyTokens.Body,
            color = GhosttyTokens.OnSurfaceVariant,
        )
        // Constrain the list so long histories scroll instead of pushing Start fresh off-screen.
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(sessions, key = { it.id }) { session ->
                GxrPanel(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onResume(session.id) }
                        .testTag("$RESUME_SESSION_TEST_TAG_PREFIX${session.id}"),
                    shape = RoundedCornerShape(GhosttyTokens.ChromeCorner),
                    color = GhosttyTokens.ScrimStrong,
                    borderColor = GhosttyTokens.OnSurfaceFaint,
                    borderWidth = 1.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            GxrText(
                                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                    .format(Date(session.savedAtEpochMs)),
                                style = GhosttyTokens.PaneTitle,
                            )
                            GxrText(
                                "${session.paneCount} ${if (session.paneCount == 1) "pane" else "panes"}",
                                style = GhosttyTokens.StatusLabel,
                                color = GhosttyTokens.OnSurfaceVariant,
                            )
                        }
                        GxrIconButton(
                            onClick = { onEndSession(session.id) },
                            modifier = Modifier.testTag("$END_SESSION_TEST_TAG_PREFIX${session.id}"),
                        ) {
                            GxrText("×", style = GhosttyTokens.PaneTitle, color = GhosttyTokens.Danger)
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
        ) {
            GxrButton(
                onClick = onEndAllSessions,
                style = GxrButtonStyle.Danger,
                modifier = Modifier.testTag(END_ALL_SESSIONS_TEST_TAG),
            ) {
                GxrText("End all sessions")
            }
            GxrButton(
                onClick = onStartFresh,
                style = GxrButtonStyle.Outline,
                modifier = Modifier.testTag(START_FRESH_TEST_TAG),
            ) {
                GxrText("Start fresh")
            }
        }
    }
}
