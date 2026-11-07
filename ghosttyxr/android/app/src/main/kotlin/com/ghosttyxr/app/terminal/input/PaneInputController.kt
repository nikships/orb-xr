package com.ghosttyxr.app.terminal.input

import com.ghosttyxr.app.terminal.session.CockpitLifecycle
import com.ghosttyxr.app.terminal.session.CockpitState
import com.ghosttyxr.app.terminal.session.PaneId

/**
 * Routes [MappedKey] events to the cockpit's currently-focused pane.
 *
 * Implements [TerminalInputSink] so it slots into the same `Modifier.terminalKeyInput` /
 * `OnScreenKeyboard` plumbing that the Phase-6 layer built. Focus is tracked here (not on
 * the engine) because focus is a UI-layer concept: which of the 4 SpatialPanels has the user
 * looking / clicking on it right now.
 *
 * Dispatch is non-blocking — the engine's `sendKey` enqueues onto the pane's single-threaded
 * dispatcher. We don't enforce `isReady()` strictly so the user can type ahead during a
 * reconnect; libghostty will buffer the encoded bytes.
 */
class PaneInputController(
    private val cockpit: CockpitLifecycle,
) : TerminalInputSink {

    /**
     * Update the focused pane. The lifecycle ignores stale identities, so a delayed input
     * event cannot crash while the grid is being reconfigured.
     */
    fun setFocusedPane(paneId: PaneId) = cockpit.focusPane(paneId)

    fun focusedPaneId(): PaneId? = active()?.focusedPaneId

    /** Always true: dispatch is fire-and-forget and the engine handles "not connected yet." */
    override fun isReady(): Boolean = true

    override fun onKey(key: MappedKey) {
        val active = active() ?: return
        val target = active.panes.firstOrNull { it.id == active.focusedPaneId }
            ?: active.panes.firstOrNull()
            ?: return
        target.sendKey(key)
    }

    /** Route scroll input from controllers or other Activity-level devices to live focus. */
    fun scroll(lines: Int) {
        if (lines == 0) return
        val active = active() ?: return
        val target = active.panes.firstOrNull { it.id == active.focusedPaneId }
            ?: active.panes.firstOrNull()
            ?: return
        target.scroll(lines)
    }

    private fun active(): CockpitState.Active? = cockpit.state.value as? CockpitState.Active
}
