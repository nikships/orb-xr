package com.ghosttyxr.app.terminal.spatial

/**
 * Pure selection logic for the spatial pane move policy.
 *
 * - Layout locked → panes are not movable at all (existing `panesMovable = false` path).
 * - "Snap to surfaces" opt-in (see `GhosttySettingsStore.snapPanesToSurfaces`) →
 *   `MovePolicy.anchor(...)` under `@ExperimentalMoveAnchorPolicy`, so a dragged pane can
 *   detach onto real-world desk/wall planes.
 * - Default → `MovePolicy.custom(...)` with the persisted-offset routing the grid already
 *   uses (system applies nothing; we own the pose).
 *
 * Kept free of XR types so JVM unit tests can pin the selection without an emulator.
 */
enum class PaneMoveMode {
    /** Pane cannot be moved (layout locked). */
    FIXED,

    /** Free move with app-owned pose (persisted offsets). */
    FREE,

    /** Free move that can also snap onto real-world anchor planes. */
    ANCHORED,
}

/** Resolve the [PaneMoveMode] from the cockpit's interaction flags. */
fun resolvePaneMoveMode(movable: Boolean, snapToSurfaces: Boolean): PaneMoveMode = when {
    !movable -> PaneMoveMode.FIXED
    snapToSurfaces -> PaneMoveMode.ANCHORED
    else -> PaneMoveMode.FREE
}

/** Aspect-ratio behavior for spatial resize handles. Panes and the window-mirror orb keep
 *  their content proportions while resizing, so text and desktop shares never stretch. */
object PaneResizePolicy {
    /** Spatial `resizable` handles always preserve the panel's aspect ratio. */
    const val MAINTAIN_ASPECT_RATIO = true
}
