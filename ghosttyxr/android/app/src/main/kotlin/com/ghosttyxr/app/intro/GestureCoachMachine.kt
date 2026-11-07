package com.ghosttyxr.app.intro

/**
 * Pure progress tracker for the first-flight **hands lesson**: it watches the two signals the
 * cockpit already produces — the session-only layout-lock flag (flipped by the prayer hold or the
 * Settings toggle) and the voice pet's visibility (summoned by palm-up, dismissed by palm-down) —
 * and records whether the user has actually performed each practice.
 *
 * Semantics:
 * - **Lock practice** counts *edges*: the initial lock state never counts, every subsequent flip
 *   does. One flip is enough to complete the practice ([Progress.lockPracticed]); further flips
 *   keep updating [Progress.layoutLocked] so the coach can echo "locked / unlocked" live.
 * - **Pet practice** requires the full round trip in order: a reveal observed by this machine
 *   ([Progress.petSummoned]) followed by a dismissal ([Progress.petDismissed]). A pet that was
 *   already visible when the lesson started must be re-summoned; a dismissal without a tracked
 *   summon is ignored. Both bits latch — once practiced, always practiced.
 * - The machine deliberately does not care *which* affordance flipped the lock (prayer hold or
 *   Settings → Layout → Lock panels): the lesson teaches the flag, and crediting either path keeps
 *   the coach honest on devices without hand tracking.
 *
 * Android-free and Compose-free so it unit-tests on the host JVM, mirroring the gesture machines
 * in `terminal/hands/`. [OnboardingCoach] feeds it from hoisted state.
 */
class GestureCoachMachine(
    initialLayoutLocked: Boolean = false,
    initialPetVisible: Boolean = false,
) {
    /** Immutable snapshot of everything the coach UI needs to render the lesson. */
    data class Progress(
        /** Live lock flag as of the last frame — drives the "Layout locked / unlocked" echo. */
        val layoutLocked: Boolean,
        /** How many times the lock flag has flipped since the lesson started. */
        val lockToggles: Int,
        /** The pet has been summoned (palm-up reveal) at least once during the lesson. */
        val petSummoned: Boolean,
        /** A tracked summon was followed by a dismissal (palm-down flip or pinch-select). */
        val petDismissed: Boolean,
    ) {
        /** The prayer/lock practice is done: the flag flipped at least once. */
        val lockPracticed: Boolean get() = lockToggles > 0

        /** The pet practice is done: summoned, then dismissed. */
        val petPracticed: Boolean get() = petSummoned && petDismissed
    }

    private var lastLocked = initialLayoutLocked
    private var lastPetVisible = initialPetVisible
    private var lockToggles = 0
    private var petSummoned = false
    private var petDismissed = false

    /** Current progress without feeding a frame. */
    val progress: Progress
        get() = Progress(
            layoutLocked = lastLocked,
            lockToggles = lockToggles,
            petSummoned = petSummoned,
            petDismissed = petDismissed,
        )

    /**
     * Feed the current observed state. Call whenever either signal changes (feeding unchanged
     * values is harmless — edges are computed against the last frame).
     *
     * @param layoutLocked the cockpit's session-only lock flag right now.
     * @param petVisible whether the voice pet is currently summoned.
     * @return the progress after applying this frame.
     */
    fun onFrame(layoutLocked: Boolean, petVisible: Boolean): Progress {
        if (layoutLocked != lastLocked) {
            lastLocked = layoutLocked
            lockToggles++
        }
        if (petVisible && !lastPetVisible) {
            petSummoned = true
        } else if (!petVisible && lastPetVisible && petSummoned) {
            petDismissed = true
        }
        lastPetVisible = petVisible
        return progress
    }
}
