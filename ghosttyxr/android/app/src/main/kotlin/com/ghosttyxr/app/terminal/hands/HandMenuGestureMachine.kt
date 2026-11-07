package com.ghosttyxr.app.terminal.hands

/**
 * Pure reveal/dismiss state machine for the hand menu: **palm-up summons, the menu then follows the
 * hand, and flipping the palm over dismisses it.**
 *
 * Behaviours it guarantees:
 * - **Debounced reveal:** the palm-up pose must be held continuously for [revealHoldMs] before the
 *   menu appears, so a hand sweeping through the pose doesn't flash it.
 * - **Stable while open:** once visible, merely lowering or tilting the hand (palm neither clearly
 *   up nor clearly down — the hysteresis gap in [HandGestures]) keeps the menu open so the user can
 *   read it and pinch its buttons. The caller keeps repositioning it onto the palm each frame.
 * - **Flip to dismiss:** turning the palm over ([HandGestures.isPalmDown]) closes the menu. After a
 *   dismiss the pose is naturally re-armed (a flip is the opposite of the summon pose), so raising
 *   the palm-up again re-summons.
 *
 * Android-/ARCore-free and unit-tested; [HandMenuController] drives it from real joint poses.
 */
class HandMenuGestureMachine(
    private val revealHoldMs: Long = DEFAULT_REVEAL_HOLD_MS,
) {
    private var visible = false
    private var poseStartMs: Long? = null

    val isVisible: Boolean get() = visible

    /**
     * Feed one tracking frame. Returns the transition this frame caused, or null for no change.
     *
     * @param palmUp whether the secondary hand is clearly in the palm-up summon pose this frame.
     * @param palmDown whether the secondary hand is clearly flipped palm-down this frame.
     * @param nowMs a monotonic timestamp (e.g. `SystemClock.elapsedRealtime()`).
     */
    fun onFrame(palmUp: Boolean, palmDown: Boolean, nowMs: Long): HandMenuTransition? {
        if (visible) {
            // Stay open while reading/selecting; only a deliberate palm-down flip closes it.
            if (!palmDown) return null
            visible = false
            poseStartMs = null
            return HandMenuTransition.Hide
        }
        if (!palmUp) {
            poseStartMs = null
            return null
        }
        val start = poseStartMs ?: nowMs.also { poseStartMs = it }
        if (nowMs - start >= revealHoldMs) {
            visible = true
            poseStartMs = null
            return HandMenuTransition.Reveal
        }
        return null
    }

    /** An item was picked or Close pressed: hide the menu. Returns the Hide transition, or null. */
    fun onDismiss(): HandMenuTransition? {
        if (!visible) return null
        visible = false
        poseStartMs = null
        return HandMenuTransition.Hide
    }

    companion object {
        const val DEFAULT_REVEAL_HOLD_MS = 350L
    }
}

/** The only two things the machine can do to the menu's visibility. */
enum class HandMenuTransition { Reveal, Hide }
