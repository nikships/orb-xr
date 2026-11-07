package com.ghosttyxr.app.terminal.session

/**
 * Top-level state of the cockpit as a whole, as owned by [CockpitLifecycle].
 *
 * Transitions (driven by [CockpitLifecycle.start]):
 *   [NeedsPairing] — no paired Mac; the QR scanner should be shown.
 *   [ResumePicker] — a paired Mac is known and at least one durable session
 *     snapshot exists; the user picks resume-vs-fresh before any pane connects.
 *   [Active] — panes exist and are connecting/connected.
 */
sealed interface CockpitState {
    data object NeedsPairing : CockpitState

    data class ResumePicker(val sessions: List<SessionSummary>) : CockpitState

    data class Active(
        val panes: List<CockpitPane>,
        val focusedPaneId: PaneId?,
    ) : CockpitState
}
