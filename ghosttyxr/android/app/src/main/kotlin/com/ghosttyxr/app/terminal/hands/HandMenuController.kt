package com.ghosttyxr.app.terminal.hands

import android.os.SystemClock
import android.util.Log
import androidx.xr.arcore.ArDevice
import androidx.xr.arcore.Hand
import androidx.xr.arcore.HandJointType
import androidx.xr.arcore.HandSide
import androidx.xr.arcore.TrackingState
import androidx.xr.runtime.Config
import androidx.xr.runtime.HandTrackingMode
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionConfigureSuccess
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.scene
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private const val TAG = "HandMenu"

/**
 * The single ARCore-touching layer for hand gestures: the **palm-following voice pet** and the
 * **prayer-pose layout-lock toggle**. Configures hand tracking on the Jetpack XR [Session], watches
 * both hands, and drives pure machines ([HandMenuGestureMachine], [PrayerToggleMachine]).
 *
 * Secondary-hand palm-up summons the pet; flipping palm-down submits and dismisses. Both palms in a
 * prayer hold toggles layout lock once per hold. While prayer is active, palm-up summon is suppressed
 * so the two gestures do not fight.
 *
 * All decision/pose logic is in pure helpers (host-JVM tested); this class only adapts the SDK and
 * is itself unit-tested with a fake [VoicePetSurface]. It degrades gracefully: if hand tracking is
 * unavailable, [start] logs and returns, and keyboard / settings toggles remain the way in.
 */
class HandMenuController(
    /**
     * The user's primary hand (system navigation lives there). Injected as a provider — rather than
     * a whole `ContentResolver` — so the controller stays SDK-thin and unit-testable. Production
     * passes `{ Hand.getPrimaryHandSide(contentResolver) }`.
     */
    private val primaryHandSide: () -> HandSide,
    private val machine: HandMenuGestureMachine = HandMenuGestureMachine(),
    private val prayerMachine: PrayerToggleMachine = PrayerToggleMachine(),
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val onPalmFlipDismiss: () -> Unit = {},
    /** The pet's reveal animation finished: open the mic. Never fires after a mid-reveal hide. */
    private val onRevealComplete: () -> Unit = {},
    /** Prayer hold completed: flip layout lock. Session-only; no UI chrome. */
    private val onLayoutLockToggle: () -> Unit = {},
) {
    private val _isMenuVisible = MutableStateFlow(false)

    /** Whether the menu is currently summoned. Exposed for callers that want to react (e.g. tests). */
    val isMenuVisible: StateFlow<Boolean> = _isMenuVisible.asStateFlow()

    // Internal (not private) so host-JVM tests can exercise the stop() reset without a Session.
    internal val _isAvailable = MutableStateFlow(false)

    /**
     * Whether hand tracking configured successfully and the palm-up menu can actually be summoned.
     * False on devices without hand tracking (emulator, optical glasses) and after [stop]. Callers
     * use this to keep alternative affordances (e.g. the floating settings gear) visible when the
     * palm menu can never appear.
     */
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private var job: Job? = null
    private var surface: VoicePetSurface? = null

    /**
     * Configure hand tracking on [session] and start watching both hands on [scope], driving
     * [surface] for the pet. Safe to call when hand tracking is unavailable — it just logs and
     * leaves gestures disabled. Idempotent: a second call while already running is ignored.
     */
    fun start(session: Session, scope: CoroutineScope, surface: VoicePetSurface) {
        if (job?.isActive == true) return
        val configured = runCatching {
            val newConfig = Config.Builder(session.config)
                .setHandTracking(HandTrackingMode.BOTH)
                .build()
            session.configure(newConfig) is SessionConfigureSuccess
        }.getOrElse {
            Log.w(TAG, "hand tracking configure failed; menu gesture disabled", it)
            false
        }
        if (!configured) {
            Log.i(TAG, "hand tracking unavailable; palm-up menu disabled (keyboard still works)")
            _isAvailable.value = false
            return
        }

        _isAvailable.value = true
        this.surface = surface
        val leftHand = Hand.left(session)
        val rightHand = Hand.right(session)
        val arDevice = runCatching { ArDevice.getInstance(session) }.getOrNull()
        job = scope.launch {
            combine(leftHand.state, rightHand.state) { leftState, rightState ->
                leftState to rightState
            }.collect { (leftState, rightState) ->
                fun palmInActivity(state: Hand.State): Pose? {
                    if (state.trackingState != TrackingState.TRACKING) return null
                    val palmPose = state.handJoints[HandJointType.PALM] ?: return null
                    return session.scene.perceptionSpace.transformPoseTo(
                        palmPose,
                        session.scene.activitySpace,
                    )
                }
                onTrackingFrame(
                    leftPalmInActivity = palmInActivity(leftState),
                    rightPalmInActivity = palmInActivity(rightState),
                    headPosition = headPosition(session, arDevice),
                    surface = surface,
                )
            }
        }
    }

    /**
     * Drive prayer + palm-up machines from one pair of palm poses (activity space) and the user's
     * head. Extracted for host-JVM unit tests with a fake [VoicePetSurface] and synthetic poses.
     */
    internal fun onTrackingFrame(
        leftPalmInActivity: Pose?,
        rightPalmInActivity: Pose?,
        headPosition: Vector3?,
        surface: VoicePetSurface,
    ) {
        val prayerPose = if (leftPalmInActivity != null && rightPalmInActivity != null) {
            PrayerGestures.isPrayerPose(leftPalmInActivity, rightPalmInActivity)
        } else {
            false
        }
        if (prayerMachine.onFrame(prayerPose = prayerPose, nowMs = nowMs()) == PrayerToggleEvent.Toggle) {
            onLayoutLockToggle()
        }

        // Secondary = opposite of primary. UNKNOWN primary falls back to left (same as pre-prayer).
        val secondaryPalm = if (primaryHandSide() == HandSide.LEFT) {
            rightPalmInActivity
        } else {
            leftPalmInActivity
        } ?: return
        onPalmFrame(
            palmInActivity = secondaryPalm,
            headPosition = headPosition,
            surface = surface,
            suppressPalmUp = prayerPose,
        )
    }

    /**
     * Drive the gesture machine + [surface] from one palm pose (already transformed into activity
     * space) and the user's head position (or null). Reduces the palm to the up-dot scalar, feeds
     * [HandMenuGestureMachine], and on reveal/while-visible repositions the panel onto the palm via
     * [HandMenuPlacement]. Extracted from the tracking coroutine so it's unit-testable with a fake
     * [VoicePetSurface] and synthetic poses, no emulator/Session needed.
     *
     * @param suppressPalmUp when true (prayer active), treat the hand as not palm-up so summon
     *   does not race the layout-lock toggle.
     */
    internal fun onPalmFrame(
        palmInActivity: Pose,
        headPosition: Vector3?,
        surface: VoicePetSurface,
        suppressPalmUp: Boolean = false,
    ) {
        val palmNormalDotUp = palmInActivity.down.dot(Vector3.Up)
        fun reposition() = surface.setPose(
            HandMenuPlacement.place(palmInActivity, headPosition),
        )
        when (
            machine.onFrame(
                palmUp = !suppressPalmUp && HandGestures.isPalmUp(palmNormalDotUp),
                palmDown = HandGestures.isPalmDown(palmNormalDotUp),
                nowMs = nowMs(),
            )
        ) {
            HandMenuTransition.Reveal -> {
                _isMenuVisible.value = true
                reposition()
                surface.show(onRevealed = onRevealComplete)
            }
            HandMenuTransition.Hide -> {
                // Order matters: mark hidden + start the surface's wrap-up first, then submit.
                // The command fires immediately; the hide animation runs in parallel.
                _isMenuVisible.value = false
                surface.hide()
                onPalmFlipDismiss()
            }
            null -> if (machine.isVisible) {
                // Follow the hand: keep repositioning the panel onto the palm every frame.
                reposition()
            }
        }
    }

    /** Dismiss the pet (it was pinch-selected to open settings). Mirrors the machine's latch. */
    fun dismiss() {
        if (machine.onDismiss() == HandMenuTransition.Hide) {
            _isMenuVisible.value = false
            surface?.hide()
        }
    }

    /** Lifecycle stop (onPause): cancel the collector and hide the menu. */
    fun stop() {
        job?.cancel()
        job = null
        machine.onDismiss()
        // Re-arm prayer so the next start does not inherit a mid-hold latch.
        prayerMachine.onFrame(prayerPose = false, nowMs = nowMs())
        _isMenuVisible.value = false
        _isAvailable.value = false
        surface?.hide()
        surface = null
    }

    /**
     * The user's head position in activity space, or null if head tracking is unavailable (device
     * pose not ready, permission not granted, or unsupported). The billboard layout falls back to
     * palm-normal facing when this is null.
     */
    private fun headPosition(session: Session, arDevice: ArDevice?): Vector3? {
        val device = arDevice ?: return null
        return runCatching {
            val devicePose = device.state.value.devicePose
            session.scene.perceptionSpace
                .transformPoseTo(devicePose, session.scene.activitySpace)
                .translation
        }.getOrNull()
    }
}
