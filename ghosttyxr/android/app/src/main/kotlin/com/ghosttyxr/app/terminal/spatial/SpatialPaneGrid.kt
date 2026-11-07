package com.ghosttyxr.app.terminal.spatial

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialBox
import androidx.xr.compose.subspace.SpatialColumn
import androidx.xr.compose.subspace.SpatialCurvedRow
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.ExperimentalMoveAnchorPolicy
import androidx.xr.compose.subspace.layout.ExperimentalRotateToLookAtUserApi
import androidx.xr.compose.subspace.layout.MovePolicy
import androidx.xr.compose.subspace.layout.PlaneOrientation
import androidx.xr.compose.subspace.layout.PlaneSemantic
import androidx.xr.compose.subspace.layout.ResizePolicy
import androidx.xr.compose.subspace.layout.SpatialAlignment
import androidx.xr.compose.subspace.layout.SpatialMoveEvent
import androidx.xr.compose.subspace.layout.SpatialResizeEvent
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.movable
import androidx.xr.compose.subspace.layout.offset
import androidx.xr.compose.subspace.layout.resizable
import androidx.xr.compose.subspace.layout.requiredHeight
import androidx.xr.compose.subspace.layout.requiredWidth
import androidx.xr.compose.subspace.layout.rotateToLookAtUser
import androidx.xr.compose.subspace.layout.width
import androidx.xr.compose.subspace.media.asSpatializedAudioOutput
import androidx.xr.compose.subspace.media.spatializedAudioOutput
import androidx.xr.scenecore.SoundEffectPoolComponent
import androidx.xr.compose.unit.DpVolumeSize
import com.ghosttyxr.app.terminal.TerminalCanvas
import com.ghosttyxr.app.terminal.TerminalSnapshot
import com.ghosttyxr.app.terminal.theme.GhosttyTokens
import com.ghosttyxr.app.util.GxrLog

private const val TAG = "SpatialPaneGrid"

private val MinimumPaneWidth = 320.dp
private val MinimumPaneHeight = 240.dp

/**
 * Adaptive spatial grid of cockpit panes. Panes are arranged into balanced rows per
 * [PaneGridLayout] (1→[1], 2→[2], 3→[2,1], 4→[2,2], 5→[3,2], 6→[3,3]), each row a
 * [SpatialCurvedRow] stacked in a [SpatialColumn]. The composable takes whatever the caller
 * wants to render per-cell so it can be driven by live cockpit panes.
 *
 * Per-pane content callback receives the global pane index ordered left-to-right, top-to-bottom.
 *
 * [paneKeys] (when supplied, one per pane) assigns each pane a stable physical slot. The
 * complete seven-pane topology is kept mounted, so adding or removing a terminal never reflows
 * surviving windows.
 */
@Composable
fun SpatialPaneGrid(
    panes: List<PaneContent>,
    modifier: SubspaceModifier = SubspaceModifier,
    // Default spawn size: 30% larger than the original 700×500 so a fresh pane reads at a
    // comfortable XR reading distance without the user having to resize first.
    paneWidth: Dp = 910.dp,
    paneHeight: Dp = 650.dp,
    paneSpacing: Dp = 24.dp,
    panesMovable: Boolean = false,
    panesResizable: Boolean = false,
    panesSelectable: Boolean = true,
    paneKeys: List<Any>? = null,
    // Opt-in real-world surface snapping: when true, unlocked panes use
    // MovePolicy.anchor(...) so a drag can detach a pane onto desk/wall planes.
    snapPanesToSurfaces: Boolean = false,
    // Per-pane spatialized bell-chime outputs (SoundEffectPoolComponent per pane, created
    // by PaneBellPlayer which owns the XR session + loaded chime). Entries are keyed by
    // the same paneKeys order; a null entry means "no spatial audio for that pane".
    paneBellOutputs: List<SoundEffectPoolComponent?>? = null,
    onPaneFocused: (paneId: Int) -> Unit = {},
) {
    require(panes.size in 1..PaneGridLayout.MAX_PANES) {
        "SpatialPaneGrid expects 1..${PaneGridLayout.MAX_PANES} panes, got ${panes.size}"
    }
    require(paneKeys == null || paneKeys.size == panes.size) {
        "paneKeys size ${paneKeys?.size} must match panes size ${panes.size}"
    }
    require(paneBellOutputs == null || paneBellOutputs.size == panes.size) {
        "paneBellOutputs size ${paneBellOutputs?.size} must match panes size ${panes.size}"
    }
    val paneOffsets by PaneLayoutStore.offsets.collectAsState()
    val paneSizes by PaneLayoutStore.sizes.collectAsState()
    // Keep geometry fixed for every pane count. Otherwise crossing the three-pane threshold
    // changes every existing panel's width and arc position.
    val effectiveWidth = paneWidth * 0.8f
    val paneSlots = remember { PaneSlotAssignments(PaneGridLayout.MAX_PANES) }
        .assign(paneKeys ?: panes.indices.toList())
    val panesBySlot = paneSlots.entries.associate { (paneIndex, slot) ->
        slot to GridPane(
            paneId = paneIndex,
            key = paneKeys?.get(paneIndex) ?: paneIndex,
            content = panes[paneIndex],
        )
    }
    val rows = PaneGridLayout.rowsFor(PaneGridLayout.MAX_PANES)
    Subspace {
        SpatialColumn(modifier = modifier) {
            var slotOffset = 0
            rows.forEach { rowCount ->
                val end = slotOffset + rowCount
                GridRow(
                    panesBySlot = panesBySlot,
                    slots = slotOffset until end,
                    paneWidth = effectiveWidth,
                    paneHeight = paneHeight,
                    paneSpacing = paneSpacing,
                    panesMovable = panesMovable,
                    panesResizable = panesResizable,
                    panesSelectable = panesSelectable,
                    snapPanesToSurfaces = snapPanesToSurfaces,
                    paneBellOutputs = paneBellOutputs,
                    paneKeys = paneKeys,
                    paneOffsets = paneOffsets,
                    paneSizes = paneSizes,
                    onPaneFocused = onPaneFocused,
                )
                slotOffset = end
            }
        }
    }
}

/**
 * Convenience wrapper that renders each [TerminalSnapshot] inside a bare [TerminalCanvas].
 * Separate name (not an overload) because `List<TerminalSnapshot>` and `List<PaneContent>`
 * erase to the same JVM signature.
 *
 * Use this when the caller doesn't need per-pane chrome (focus highlight, status overlay)
 * yet — handy for previewing the layout with `buildDemoSnapshot()` ×4 before the repository
 * is wired through.
 */
@Composable
fun SpatialPaneGridFromSnapshots(
    snapshots: List<TerminalSnapshot>,
    modifier: SubspaceModifier = SubspaceModifier,
    paneWidth: Dp = 910.dp,
    paneHeight: Dp = 650.dp,
    paneSpacing: Dp = 24.dp,
    panesMovable: Boolean = false,
    panesResizable: Boolean = false,
    snapPanesToSurfaces: Boolean = false,
    onPaneFocused: (paneId: Int) -> Unit = {},
) {
    require(snapshots.size in 1..PaneGridLayout.MAX_PANES) {
        "SpatialPaneGrid expects 1..${PaneGridLayout.MAX_PANES} snapshots, got ${snapshots.size}"
    }
    SpatialPaneGrid(
        panes = snapshots.map { snap ->
            PaneContent { _ ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(snap.defaultBgArgb)),
                ) {
                    TerminalCanvas(snapshot = snap)
                }
            }
        },
        modifier = modifier,
        paneWidth = paneWidth,
        paneHeight = paneHeight,
        paneSpacing = paneSpacing,
        panesMovable = panesMovable,
        panesResizable = panesResizable,
        snapPanesToSurfaces = snapPanesToSurfaces,
        onPaneFocused = onPaneFocused,
    )
}

/**
 * Per-pane content slot. The lambda is invoked inside the [SpatialPanel]'s 2D layout scope
 * with the pane's index (0..3). Callers can decide whether to render bare canvas, focus
 * chrome, status overlays, etc.
 */
fun interface PaneContent {
    @Composable
    fun render(paneId: Int)
}

@OptIn(ExperimentalRotateToLookAtUserApi::class, ExperimentalMoveAnchorPolicy::class)
@Composable
private fun GridRow(
    panesBySlot: Map<Int, GridPane>,
    slots: IntRange,
    paneWidth: Dp,
    paneHeight: Dp,
    paneSpacing: Dp,
    panesMovable: Boolean,
    panesResizable: Boolean,
    panesSelectable: Boolean,
    snapPanesToSurfaces: Boolean,
    paneBellOutputs: List<SoundEffectPoolComponent?>?,
    paneKeys: List<Any>?,
    paneOffsets: Map<Long, PaneOffset>,
    paneSizes: Map<Long, PaneSize>,
    onPaneFocused: (Int) -> Unit,
) {
    val paneShape = RoundedCornerShape(GhosttyTokens.PaneCorner)
    val density = LocalDensity.current
    val minimumWidthPx = with(density) { MinimumPaneWidth.toPx() }
    val minimumHeightPx = with(density) { MinimumPaneHeight.toPx() }
    val minimumPaneSize = DpVolumeSize(MinimumPaneWidth, MinimumPaneHeight, 0.dp)
    val paneInteractionSource = remember { MutableInteractionSource() }
    SpatialCurvedRow(curveRadius = 1025.dp) {
        slots.forEach { slot ->
            val slotPane = panesBySlot[slot]
            key(slotPane?.key ?: "empty-pane-slot-$slot") {
                FixedPaneSlot(paneWidth, paneHeight) {
                    if (paneRequiresInputSurface(slotPane != null)) {
                        val pane = checkNotNull(slotPane)
                        val paneKey = pane.key as? Long
                        // Prefer stored size (user resize or session resume); fall back to grid default.
                        val storedSize = paneKey?.let { paneSizes[it] }
                            ?.constrained(minimumWidthPx, minimumHeightPx)
                        val resolvedWidth = (
                            storedSize?.let { with(density) { it.widthPx.toDp() } } ?: paneWidth
                        ).coerceAtLeast(MinimumPaneWidth)
                        val resolvedHeight = (
                            storedSize?.let { with(density) { it.heightPx.toDp() } } ?: paneHeight
                        ).coerceAtLeast(MinimumPaneHeight)

                        var panelModifier = SubspaceModifier
                            // Ignore the fixed slot's max constraints so the panel surface and its
                            // 2D terminal content receive the actual user-selected dimensions.
                            .requiredWidth(resolvedWidth)
                            .requiredHeight(resolvedHeight)
                        // Offset + billboard are layout state: always apply so Lock panels freezes
                        // the current arrangement instead of snapping back to the default grid.
                        if (paneKey != null && paneOffsetApplied(hasPaneKey = true)) {
                            val offset = paneOffsets[paneKey] ?: PaneOffset()
                            panelModifier = panelModifier
                                .offset(
                                    x = with(density) { offset.x.toDp() },
                                    y = with(density) { offset.y.toDp() },
                                    z = with(density) { offset.z.toDp() },
                                )
                                .rotateToLookAtUser()
                            // Use incremental movement so the curved-row slot position never leaks
                            // into the pane's independently persisted offset.
                            if (paneInteractionEnabled(interactionRequested = panesMovable)) {
                                val onPaneMove: (SpatialMoveEvent) -> Unit = { event ->
                                    val delta = event.pose.translation - event.previousPose.translation
                                    PaneLayoutStore.move(paneKey, PaneOffset(delta.x, delta.y, delta.z))
                                }
                                panelModifier = when (
                                    resolvePaneMoveMode(
                                        movable = true,
                                        snapToSurfaces = snapPanesToSurfaces,
                                    )
                                ) {
                                    // Opt-in real-world surface snapping: panes can detach onto
                                    // detected desk (horizontal/table) and wall (vertical/wall)
                                    // planes. The persisted-offset onMove stays attached so an
                                    // anchored pane still reports its pose back to the layout
                                    // store for session resume.
                                    PaneMoveMode.ANCHORED -> {
                                        GxrLog.d(TAG) { "pane $paneKey using anchor move policy" }
                                        panelModifier.movable(
                                            movePolicy = MovePolicy.anchor(
                                                anchorPlaneOrientations = setOf(
                                                    PlaneOrientation.Any,
                                                ),
                                                anchorPlaneSemantics = setOf(
                                                    PlaneSemantic.Table,
                                                    PlaneSemantic.Wall,
                                                ),
                                                onMove = onPaneMove,
                                            ),
                                        )
                                    }
                                    // MovePolicy.custom is the alpha16 successor to the deprecated
                                    // `movable(onMove = …)` that returned `true` to claim the event:
                                    // the system applies nothing and we own the resulting pose, which
                                    // is what routing the delta into the pane's persisted offset needs.
                                    // (MovePolicy.system() is the other branch —
                                    // system-applied transforms — and would double-count our offset.)
                                    else -> panelModifier.movable(
                                        movePolicy = MovePolicy.custom(onMove = onPaneMove),
                                    )
                                }
                            }
                        }
                        // Manual resize state survives panel recreation and session restore.
                        if (
                            paneInteractionEnabled(interactionRequested = panesResizable) &&
                            paneKey != null
                        ) {
                            panelModifier = panelModifier.resizable(
                                minimumSize = minimumPaneSize,
                                // Keep the terminal's aspect ratio while resizing so text cells
                                // never stretch; onResize reporting is unchanged.
                                maintainAspectRatio = PaneResizePolicy.MAINTAIN_ASPECT_RATIO,
                                resizePolicy = ResizePolicy.system(
                                    onResize = { event: SpatialResizeEvent ->
                                        PaneLayoutStore.resize(
                                            paneKey,
                                            PaneSize(
                                                widthPx = event.size.width.toFloat(),
                                                heightPx = event.size.height.toFloat(),
                                            ).constrained(minimumWidthPx, minimumHeightPx),
                                        )
                                    },
                                ),
                            )
                        }
                        // Spatialize this pane's bell chimes at its own 3D position. The
                        // component comes from PaneBellPlayer (owns XR session + loaded
                        // chime); the grid only adapts and attaches it.
                        val bellOutput = paneKeys
                            ?.indexOf(pane.key)
                            .takeIf { it != null && it >= 0 }
                            ?.let { paneBellOutputs?.getOrNull(it) }
                        if (bellOutput != null) {
                            panelModifier = panelModifier.spatializedAudioOutput(
                                bellOutput.asSpatializedAudioOutput(),
                            )
                        }
                        SpatialPanel(panelModifier) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(paneSpacing / 4)
                                    .clip(paneShape)
                                    .then(
                                        if (panesSelectable) {
                                            // Pane focus is a routing surface, not a giant button.
                                            // Suppress its whole-pane glow; controls inside keep
                                            // their own GlowIndication and panel button sounds.
                                            Modifier.clickable(
                                                interactionSource = paneInteractionSource,
                                                indication = null,
                                            ) {
                                                onPaneFocused(pane.paneId)
                                            }
                                        } else {
                                            Modifier
                                        },
                                    ),
                            ) {
                                pane.content.render(pane.paneId)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FixedPaneSlot(
    width: Dp,
    height: Dp,
    content: @Composable () -> Unit,
) {
    // The curved row measures only this fixed slot, so resizing its child cannot reflow siblings.
    SpatialBox(
        modifier = SubspaceModifier
            .width(width)
            .height(height),
        alignment = SpatialAlignment.Center,
    ) {
        content()
    }
}

private data class GridPane(
    val paneId: Int,
    val key: Any,
    val content: PaneContent,
)

internal fun paneRequiresInputSurface(panePresent: Boolean): Boolean = panePresent

internal fun paneInteractionEnabled(interactionRequested: Boolean): Boolean = interactionRequested

/**
 * Stored offsets / look-at are layout, not interaction. Always applied when a pane has a stable
 * key so locking only disables move/resize, it does not discard the current arrangement.
 */
internal fun paneOffsetApplied(hasPaneKey: Boolean): Boolean = hasPaneKey

internal class PaneSlotAssignments(
    private val slotCount: Int,
) {
    private val slotsByKey = LinkedHashMap<Any, Int>()

    fun assign(keys: List<Any>): Map<Int, Int> {
        require(keys.size <= slotCount) { "Too many panes for $slotCount slots" }
        require(keys.distinct().size == keys.size) { "Pane keys must be unique" }

        slotsByKey.keys.retainAll(keys.toSet())
        val usedSlots = slotsByKey.values.toMutableSet()
        keys.forEach { key ->
            if (key !in slotsByKey) {
                val slot = (0 until slotCount).first { it !in usedSlots }
                slotsByKey[key] = slot
                usedSlots += slot
            }
        }
        return keys.mapIndexed { index, key -> index to checkNotNull(slotsByKey[key]) }.toMap()
    }
}
