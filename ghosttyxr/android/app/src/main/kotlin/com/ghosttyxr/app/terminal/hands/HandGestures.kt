package com.ghosttyxr.app.terminal.hands

/**
 * Pure hand-gesture math for the palm-up hand menu, kept Android-/ARCore-free so it unit-tests on
 * the host JVM. [HandMenuController] computes the raw scalar from ARCore joint poses (the palm
 * normal's dot with world-up) and feeds it here; this object only decides "is the palm up".
 *
 * Mirrors the project discipline (see `VoiceCommandController` / `PaneNavShortcuts`): all decision
 * logic is pure and tested with fakes; only the thin SDK adapter touches the platform.
 *
 * Selection *inside* the menu is not a custom gesture — Android XR routes a hand pinch as the
 * system pointer onto the SpatialPanel's Compose buttons — so the only gesture we detect is the
 * palm-up reveal.
 */
object HandGestures {

    /**
     * How "up" the palm normal must point (dot with world-up) to count as the summon pose.
     * 1.0 = the palm faces straight up; 0.6 ≈ within ~53° of vertical, loose enough that a
     * comfortable hand raise counts without firing on a hand held flat or palm-down.
     */
    const val PALM_UP_DOT = 0.6f

    /**
     * How "down" the palm normal must point (dot with world-up) to count as the dismiss pose —
     * the user flipping their hand palm-down. −0.3 ≈ past ~107° from vertical: requires a clear
     * turn-over, not just lowering the hand. The wide [PALM_UP_DOT]..[PALM_DOWN_DOT] gap is
     * deliberate hysteresis so a hand held flat (dot ≈ 0) neither re-summons nor dismisses — the
     * menu stays put while you read it and only closes on a real flip.
     */
    const val PALM_DOWN_DOT = -0.3f

    /**
     * True when the palm is turned up — the "summon the menu" pose.
     *
     * @param palmNormalDotUp dot product of the palm's outward-facing normal with world-up.
     */
    fun isPalmUp(palmNormalDotUp: Float): Boolean = palmNormalDotUp > PALM_UP_DOT

    /**
     * True when the palm is turned down — the "dismiss the menu" pose (flip-to-close).
     *
     * @param palmNormalDotUp dot product of the palm's outward-facing normal with world-up.
     */
    fun isPalmDown(palmNormalDotUp: Float): Boolean = palmNormalDotUp < PALM_DOWN_DOT
}
