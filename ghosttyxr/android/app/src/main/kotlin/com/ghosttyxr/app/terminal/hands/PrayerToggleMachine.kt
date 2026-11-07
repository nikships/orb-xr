package com.ghosttyxr.app.terminal.hands

/**
 * Debounced edge-trigger for the prayer layout-lock toggle.
 *
 * - Pose must be held continuously for [holdMs] before firing.
 * - Fires **once** per continuous hold, then requires leaving the pose before the next toggle.
 * - Pure / host-JVM testable; [HandMenuController] drives it from ARCore frames.
 */
class PrayerToggleMachine(
    private val holdMs: Long = DEFAULT_HOLD_MS,
) {
    private var poseStartMs: Long? = null
    private var armed = true

    /**
     * Feed one tracking frame.
     *
     * @return [PrayerToggleEvent.Toggle] on the edge after a held prayer pose, else null.
     */
    fun onFrame(prayerPose: Boolean, nowMs: Long): PrayerToggleEvent? {
        if (!prayerPose) {
            poseStartMs = null
            armed = true
            return null
        }
        if (!armed) return null
        val start = poseStartMs ?: nowMs.also { poseStartMs = it }
        if (nowMs - start < holdMs) return null
        armed = false
        poseStartMs = null
        return PrayerToggleEvent.Toggle
    }

    companion object {
        const val DEFAULT_HOLD_MS = 350L
    }
}

/** The only event the prayer machine emits. */
enum class PrayerToggleEvent { Toggle }
