package com.ghosttyxr.app.terminal.session

/**
 * Stable identity of a cockpit pane. Assigned once when the pane is created
 * and never reused within a [CockpitLifecycle] instance — unlike a pane's
 * position in [CockpitState.Active.panes] (which shifts when an earlier pane
 * is removed), a [PaneId] keeps addressing (focus, input routing, layout)
 * correct across add/remove churn.
 */
@JvmInline
value class PaneId(val value: Long)
