package com.ghosttyxr.app.ui.pointer

import com.ghosttyxr.app.terminal.windowmirror.SymbiosisPointerEvent
import com.ghosttyxr.app.util.GxrLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "XrPointerRouter"

/** Live XR cursor state, in cockpit units. Null target = cursor over empty space. */
data class XrCursorState(
    val active: Boolean = false,
    val x: Float = 0f,
    val y: Float = 0f,
    val targetId: String? = null,
)

/**
 * The XR side of Symbiosis: a virtual mouse cursor that roams the cockpit while the
 * Mac's physical mouse is handed off. Mouse deltas from the relay move the cursor in
 * [CockpitSpace]; hit-testing resolves the target under it, and interactions are
 * dispatched *semantically* via callbacks — [onTargetClicked] focuses a pane,
 * [onTargetScrolled] scrolls it — rather than by synthesizing MotionEvents. Because
 * the Mac's pointer never fabricates touch events, a pane's own `clickable` only ever
 * sees real hand pinches, which makes hand-input suppression during a display share a
 * simple, correct gate at the call site.
 *
 * Ownership is toggled on the Mac with Control+Option+Command+M (no automatic
 * re-entry return when the cursor crosses the mirror pane). [onPointerReturn] remains
 * for protocol/compatibility paths; production deactivates via pointer-owner state.
 *
 * Pure Kotlin (no Android types), so all routing, hit-testing, spawn, and return math
 * run in plain host-JVM unit tests. Callers invoke it on the main thread (Compose
 * collects the relay flow there).
 */
class XrPointerRouter(
    private val onPointerReturn: (x: Float, y: Float) -> Unit,
    private val onTargetClicked: (targetId: String) -> Unit,
    private val onTargetScrolled: (targetId: String, lines: Int) -> Unit,
) {
    private val _layout = MutableStateFlow<Map<String, CockpitRect>>(emptyMap())
    val layout: StateFlow<Map<String, CockpitRect>> = _layout.asStateFlow()

    private val _cursor = MutableStateFlow(XrCursorState())
    val cursor: StateFlow<XrCursorState> = _cursor.asStateFlow()

    // Drag state: while the button is down, the mirror pane can't steal ownership
    // (no pointer hand-back mid-hold).
    private var pressedTargetId: String? = null

    // Dropped-event recovery: a gap in the Mac's per-session sequence numbers means
    // we may have lost a button transition (network loss or relay buffer overflow),
    // so any held press is released rather than left stranded as a phantom drag.
    private val sequence = PointerSequenceTracker()

    /** Fractional scroll accumulator (Mac points) so sub-line deltas aren't lost. */
    private var scrollAccum = 0f

    /** Mac-points → cockpit-units gain. */
    private val sensitivity = 1.6f

    fun updateLayout(layout: Map<String, CockpitRect>) {
        _layout.value = layout
    }

    fun handle(event: SymbiosisPointerEvent) {
        when (event) {
            is SymbiosisPointerEvent.Handoff -> {
                sequence.reset(event.seq)
                activate(event.edge, event.y)
            }
            is SymbiosisPointerEvent.Delta -> {
                recoverFromSequenceGap(event.seq)
                move(event.dx, event.dy)
            }
            is SymbiosisPointerEvent.Button -> {
                recoverFromSequenceGap(event.seq)
                button(event.button, event.action)
            }
            is SymbiosisPointerEvent.Scroll -> {
                recoverFromSequenceGap(event.seq)
                scroll(event.dx, event.dy)
            }
        }
    }

    private fun recoverFromSequenceGap(seq: Long) {
        if (sequence.accept(seq) && pressedTargetId != null) {
            GxrLog.w(TAG, "pointer sequence gap at $seq; cancelling held press")
            pressedTargetId = null
        }
    }

    /** Hide the cursor and cancel any in-flight press (Mac reclaimed the pointer). */
    fun deactivate() {
        pressedTargetId = null
        scrollAccum = 0f
        sequence.reset()
        _cursor.value = XrCursorState()
    }

    private fun activate(edge: String, yFraction: Float) {
        val spawn = CockpitSpace.spawnPoint(_layout.value, edge, yFraction)
        if (spawn == null) {
            // No mirror pane registered yet: bounce straight back to the Mac.
            GxrLog.w(TAG, "handoff with no mirror target; returning pointer")
            onPointerReturn(if (edge == "left") 1f else 0f, yFraction)
            return
        }
        val (x, y) = spawn
        _cursor.value = XrCursorState(
            active = true,
            x = x,
            y = y,
            targetId = CockpitSpace.hitTest(_layout.value, x, y),
        )
    }

    private fun move(dx: Float, dy: Float) {
        val current = _cursor.value
        if (!current.active) return
        val layout = _layout.value
        val bounds = CockpitSpace.bounds(layout)
        val x = (current.x + dx * sensitivity).coerceIn(bounds.left - CockpitSpace.SPAWN_MARGIN, bounds.right + CockpitSpace.SPAWN_MARGIN)
        val y = (current.y + dy * sensitivity).coerceIn(bounds.top, bounds.bottom)
        val hit = CockpitSpace.hitTest(layout, x, y)
        // Ownership returns only via Mac toggle / emergency / disconnect — not by
        // roaming onto the mirror pane.
        _cursor.value = current.copy(x = x, y = y, targetId = hit)
    }

    private fun button(button: String, action: String) {
        if (button != "left") return
        val current = _cursor.value
        if (!current.active) return
        when (action) {
            "down" -> {
                val target = current.targetId ?: return
                if (target == CockpitSpace.MIRROR_TARGET) return
                pressedTargetId = target
                onTargetClicked(target)
            }
            "up" -> {
                pressedTargetId = null
            }
        }
    }

    private fun scroll(dx: Float, dy: Float) {
        val current = _cursor.value
        if (!current.active) return
        val targetId = current.targetId ?: return
        if (targetId == CockpitSpace.MIRROR_TARGET) return
        // Invert Mac wheel sign so natural scroll matches terminalScrollback /
        // libghostty (negative = older content). Forwarded dy is opposite of
        // Compose's finger-drag convention without this flip.
        scrollAccum += -dy
        val lines = (scrollAccum / SCROLL_POINTS_PER_LINE).toInt()
        if (lines != 0) {
            scrollAccum -= lines * SCROLL_POINTS_PER_LINE
            onTargetScrolled(targetId, lines)
        }
    }

    private companion object {
        /** CGEvent point deltas are already wheel-scale; preserve fractional trackpad input. */
        const val SCROLL_POINTS_PER_LINE = 1f
    }
}
