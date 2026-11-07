package com.ghosttyxr.app.terminal.spatial

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.xr.compose.platform.LocalSession
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.xr.compose.spatial.ExperimentalFollowingSubspaceApi
import androidx.xr.compose.spatial.FollowingSubspace
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.FollowBehavior
import androidx.xr.compose.subspace.FollowTarget
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.MovePolicy
import androidx.xr.compose.subspace.layout.ResizePolicy
import androidx.xr.compose.subspace.layout.SpatialResizeEvent
import androidx.xr.compose.subspace.layout.SpatialResizeEventType
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.movable
import androidx.xr.compose.subspace.layout.offset
import androidx.xr.compose.subspace.layout.resizable
import androidx.xr.compose.subspace.layout.width
import androidx.xr.compose.unit.DpVolumeSize
import androidx.xr.scenecore.SoundEffectPoolComponent
import com.ghosttyxr.app.terminal.session.CockpitPane
import com.ghosttyxr.app.terminal.settings.GhosttySettingsStore
import com.ghosttyxr.app.util.GxrLog

private const val TAG = "AdaptiveTerminalShell"

/** Live first-flight coach card: wide enough for body copy, tall enough for pose art + CTA. */
private const val COACH_PANEL_WIDTH_DP = 540
private const val COACH_PANEL_HEIGHT_DP = 560

/**
 * Top-level adaptive shell. Renders [spatialContent] (the pane grid) plus a single,
 * collapsible control [panelContent] inside a `Subspace { SpatialPanel { … } }` when
 * [LocalSpatialCapabilities] reports spatial UI is enabled (Galaxy XR FullSpace mode),
 * falling back to a plain 2D [Box] otherwise (HomeSpace mode on the headset, or any non-XR
 * device the app happens to launch on).
 *
 * The control panel is one movable [SpatialPanel]: expanded it takes the user-resizable,
 * persisted size from [GhosttySettingsStore] (the settings body reflows its card columns
 * from the resulting width), collapsed it shrinks to a small gear affordance so it never
 * eats grid space when the user wants a clean view. The 2D fallback centres the same panel
 * clamped to ~90% of the window, so FullSpace and HomeSpace show the same exact settings
 * surface. When [hideCollapsedPanel] is true the collapsed gear is not rendered at all —
 * the palm-up hand menu becomes the way to reopen settings, so no stray floating icon
 * drifts around the space. Without the 2D fallback, launching in HomeSpace gives an empty
 * window — `Subspace { … }` is a no-op when spatialization is off, per the Android XR docs.
 */
@Composable
fun AdaptiveTerminalPanel(
    modifier: Modifier = Modifier,
    panelExpanded: Boolean = true,
    panelContent: (@Composable () -> Unit)? = null,
    coachContent: (@Composable () -> Unit)? = null,
    hideCollapsedPanel: Boolean = false,
    spatialEnabledOverride: Boolean? = null,
    spatialContent: @Composable () -> Unit,
) {
    val isSpatial = spatialEnabledOverride ?: LocalSpatialCapabilities.current.isSpatialUiEnabled
    if (isSpatial) {
        Subspace {
            spatialContent()
            if (panelContent != null && (panelExpanded || !hideCollapsedPanel)) {
                if (panelExpanded) {
                    // Expanded settings panel: user-resizable, size persisted so the
                    // card-column layout the user chose survives restarts.
                    val density = LocalDensity.current
                    val panelWidth by GhosttySettingsStore.settingsPanelWidthDp.collectAsState()
                    val panelHeight by GhosttySettingsStore.settingsPanelHeightDp.collectAsState()
                    val minPanelSize = DpVolumeSize(
                        GhosttySettingsStore.MIN_SETTINGS_PANEL_WIDTH_DP.dp,
                        GhosttySettingsStore.MIN_SETTINGS_PANEL_HEIGHT_DP.dp,
                        0.dp,
                    )
                    SpatialPanel(
                        SubspaceModifier
                            .width(panelWidth.dp)
                            .height(panelHeight.dp)
                            .movable(movePolicy = MovePolicy.Default)
                            .resizable(
                                minimumSize = minPanelSize,
                                // alpha16 routes resize handling through a ResizePolicy;
                                // ResizePolicy.system keeps the system-applied sizing and
                                // just reports the committed size back to us.
                                resizePolicy = ResizePolicy.system(
                                    onResize = { event: SpatialResizeEvent ->
                                        if (event.type == SpatialResizeEventType.End) {
                                            with(density) {
                                                GhosttySettingsStore.setSettingsPanelSize(
                                                    widthDp = event.size.width.toFloat().toDp().value,
                                                    heightDp = event.size.height.toFloat().toDp().value,
                                                )
                                            }
                                        }
                                    },
                                ),
                            ),
                    ) {
                        panelContent()
                    }
                } else {
                    SpatialPanel(
                        SubspaceModifier
                            .width(72.dp)
                            .height(72.dp)
                            .movable(movePolicy = MovePolicy.Default),
                    ) {
                        panelContent()
                    }
                }
            }
            if (coachContent != null) {
                // Head-following coach HUD: the first-flight card follows the headset
                // with a Soft behavior so it stays in view while the user looks around
                // the cockpit, without rigidly locking to the gaze. Falls back to a
                // plain panel when no XR session is available on this frame.
                CoachFollowWrap {
                    // Sized for the longest live-coach steps (pose art + multi-line body +
                    // status + primary action). Content also scrolls so nothing clips.
                    SpatialPanel(
                        SubspaceModifier
                            .width(COACH_PANEL_WIDTH_DP.dp)
                            .height(COACH_PANEL_HEIGHT_DP.dp)
                            .offset(y = (-300).dp, z = 120.dp),
                    ) {
                        coachContent()
                    }
                }
            }
        }
    } else {
        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            spatialContent()
            if (panelContent != null) {
                if (panelExpanded) {
                    // Same panel as FullSpace: aim for the persisted size, clamped to ~90%
                    // of the window so small HomeSpace windows still fit (the card grid
                    // reflows its columns from whatever width results).
                    val panelWidth by GhosttySettingsStore.settingsPanelWidthDp.collectAsState()
                    val panelHeight by GhosttySettingsStore.settingsPanelHeightDp.collectAsState()
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .width(min(panelWidth.dp, maxWidth * 0.9f))
                            .height(min(panelHeight.dp, maxHeight * 0.9f)),
                    ) {
                        panelContent()
                    }
                } else {
                    Box(modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                        panelContent()
                    }
                }
            }
            if (coachContent != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .width(min(COACH_PANEL_WIDTH_DP.dp, maxWidth - 32.dp))
                        .height(min(COACH_PANEL_HEIGHT_DP.dp, maxHeight * 0.85f)),
                ) {
                    coachContent()
                }
            }
        }
    }
}

/**
 * Head-following wrapper for the first-flight coach card. When an XR session is live,
 * the card lives inside `FollowingSubspace(target = FollowTarget.ArDevice(session),
 * behavior = FollowBehavior.Soft)` so it trails the headset gently and stays in view
 * while the user inspects the cockpit. Without a session (HomeSpace frame, preview,
 * compose test) the content renders inline with no follow behavior.
 */
@OptIn(ExperimentalFollowingSubspaceApi::class)
@Composable
internal fun CoachFollowWrap(content: @Composable () -> Unit) {
    val session = LocalSession.current
    if (session != null) {
        FollowingSubspace(
            target = FollowTarget.ArDevice(session),
            behavior = FollowBehavior.Soft(),
        ) {
            content()
        }
    } else {
        content()
    }
}

/** Whether the coach card can head-follow on this frame: needs a live XR session. */
internal fun coachFollowAvailable(sessionPresent: Boolean): Boolean = sessionPresent

/**
 * Adaptive 2×2 pane shell. Uses [SpatialPaneGrid] when spatial UI is available, otherwise
 * falls back to a tiled 2D layout (Column of two Rows). The 2D fallback matters because
 * the Android XR Emulator can switch HomeSpace / FullSpace at runtime and a freshly-launched
 * app may briefly render in HomeSpace before transitioning.
 *
 * @param panes Length-4 list of pane content slots ordered top-left, top-right, bottom-left,
 *              bottom-right.
 * @param cockpitPanes Live panes backing [panes] (same order) — used to create per-pane
 *              spatialized bell-chime outputs; null entries disable chimes for that pane.
 *              Null (default) keeps previews/tests chime-free.
 */
@Composable
fun AdaptivePaneGrid(
    panes: List<PaneContent>,
    modifier: Modifier = Modifier,
    panesMovable: Boolean = false,
    panesResizable: Boolean = false,
    panesSelectable: Boolean = true,
    paneKeys: List<Any>? = null,
    snapPanesToSurfaces: Boolean = false,
    cockpitPanes: List<CockpitPane?>? = null,
    onPaneFocused: (paneId: Int) -> Unit = {},
    spatialEnabledOverride: Boolean? = null,
) {
    require(panes.size in 1..PaneGridLayout.MAX_PANES) {
        "AdaptivePaneGrid expects 1..${PaneGridLayout.MAX_PANES} panes, got ${panes.size}"
    }
    require(cockpitPanes == null || cockpitPanes.size == panes.size) {
        "cockpitPanes size ${cockpitPanes?.size} must match panes size ${panes.size}"
    }
    val isSpatial = spatialEnabledOverride ?: LocalSpatialCapabilities.current.isSpatialUiEnabled
    if (isSpatial) {
        PaneBellOutputs(panes = cockpitPanes, enabled = true) { bellOutputs ->
            SpatialPaneGrid(
                panes = panes,
                panesMovable = panesMovable,
                panesResizable = panesResizable,
                panesSelectable = panesSelectable,
                paneKeys = paneKeys,
                snapPanesToSurfaces = snapPanesToSurfaces,
                paneBellOutputs = bellOutputs,
                onPaneFocused = onPaneFocused,
            )
        }
    } else {
        FlatAdaptiveGrid(
            panes = panes,
            paneKeys = paneKeys,
            modifier = modifier,
            panesSelectable = panesSelectable,
            onPaneFocused = onPaneFocused,
        )
    }
}

/**
 * Creates one [PaneBellPlayer] per live pane and hands the grid their
 * `SoundEffectPoolComponent`s. Players are released when the spatial branch leaves
 * composition (HomeSpace switch) or the pane list changes. When [enabled] is false or
 * no XR session is live, the grid gets all-null outputs (chimes silently dropped).
 */
@Composable
private fun PaneBellOutputs(
    panes: List<CockpitPane?>?,
    enabled: Boolean,
    content: @Composable (List<SoundEffectPoolComponent?>?) -> Unit,
) {
    if (panes == null || !enabled) {
        content(null)
        return
    }
    val context = LocalContext.current
    val session = LocalSession.current
    if (session == null) {
        content(panes.map { null })
        return
    }
    val players = panes.map { pane ->
        val bellEvents = pane?.bellEvents
        remember(pane, session) {
            if (bellEvents == null) {
                null
            } else {
                runCatching { PaneBellPlayer(context, session, bellEvents) }
                    .getOrElse { e ->
                        GxrLog.w(TAG, "bell player unavailable: ${e.message}")
                        null
                    }
            }
        }
    }
    DisposableEffect(players) {
        onDispose {
            players.forEach { player ->
                runCatching { player?.release() }
            }
        }
    }
    content(players.map { it?.component })
}

@Composable
private fun FlatAdaptiveGrid(
    panes: List<PaneContent>,
    paneKeys: List<Any>?,
    modifier: Modifier,
    panesSelectable: Boolean,
    onPaneFocused: (Int) -> Unit,
) {
    val rows = PaneGridLayout.rowsFor(panes.size)
    Column(modifier = modifier.fillMaxSize().background(Color.Black)) {
        var offset = 0
        rows.forEach { rowCount ->
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                for (local in 0 until rowCount) {
                    val paneId = offset + local
                    key(paneKeys?.get(paneId) ?: paneId) {
                        FlatPane(
                            content = panes[paneId],
                            paneId = paneId,
                            panesSelectable = panesSelectable,
                            onPaneFocused = onPaneFocused,
                        )
                    }
                }
            }
            offset += rowCount
        }
    }
}

@Composable
private fun RowScope.FlatPane(
    content: PaneContent,
    paneId: Int,
    panesSelectable: Boolean,
    onPaneFocused: (Int) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxSize()
            .padding(4.dp)
            .then(
                if (panesSelectable) {
                    // No indication here: PaneStatusBar already draws its own focus
                    // border, and GlowIndication (the app-wide LocalIndication) would
                    // otherwise wash the whole terminal canvas in an accent tint.
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                    ) { onPaneFocused(paneId) }
                } else {
                    Modifier
                },
            ),
    ) {
        content.render(paneId)
    }
}
