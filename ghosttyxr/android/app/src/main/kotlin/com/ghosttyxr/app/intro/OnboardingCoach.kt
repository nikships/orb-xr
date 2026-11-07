package com.ghosttyxr.app.intro

import androidx.annotation.DrawableRes
import androidx.annotation.RawRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.ghosttyxr.app.R
import com.ghosttyxr.app.terminal.session.CockpitLifecycle
import com.ghosttyxr.app.terminal.theme.GhosttyTokens
import com.ghosttyxr.app.ui.ONBOARDING_COACH_ACTION_TEST_TAG
import com.ghosttyxr.app.ui.ONBOARDING_COACH_MUTE_TEST_TAG
import com.ghosttyxr.app.ui.ONBOARDING_COACH_STATUS_TEST_TAG
import com.ghosttyxr.app.ui.ONBOARDING_COACH_TEST_TAG
import com.ghosttyxr.app.ui.chrome.GxrButton
import com.ghosttyxr.app.ui.chrome.GxrButtonStyle
import com.ghosttyxr.app.ui.chrome.GxrPanel
import com.ghosttyxr.app.ui.chrome.GxrText
import com.ghosttyxr.app.ui.chrome.LocalGxrTextStyle
import com.ghosttyxr.app.ui.chrome.rememberOrbJewelPainter

internal enum class OnboardingCoachStep(
    /** Bundled Aoede narration for the step (see `ghosttyxr/tools/generate_narration.sh`). */
    @RawRes val narrationRes: Int,
    /** Optional HUD illustration shown above the copy; also the no-hand-tracking pose fallback. */
    @DrawableRes val illustrationRes: Int?,
) {
    FIRST_TERMINAL(R.raw.coach_first_terminal, null),
    SPAWN_PANE(R.raw.coach_spawn_pane, R.drawable.coach_spawn_pane),
    LOCK_LAYOUT(R.raw.coach_lock_layout, R.drawable.coach_prayer_hold),
    SUMMON_PET(R.raw.coach_summon_pet, R.drawable.coach_palm_pet),
    READY(R.raw.coach_ready, R.drawable.coach_ready_badge),
}

/**
 * Live-cockpit coaching pass shown after first-run setup. Besides the spawn-a-pane loop it now
 * teaches the two custom hand poses (issue #76): the **prayer hold** that toggles the session-only
 * layout lock, and the **palm-up voice pet** with its palm-down submit/dismiss.
 *
 * Each step is narrated by the same bundled Aoede clips used in [OnboardingFlow] — prerecorded,
 * no runtime synthesis — via [onNarrationRequested] (null stops playback). Landing a practiced
 * gesture plays a one-time spoken acknowledgment on top of the visual status line.
 *
 * Gesture practice is detected from real cockpit state — [layoutLocked] flips (prayer or the
 * Settings switch) and [petVisible] (the pet's reveal/dismiss) — via the pure
 * [GestureCoachMachine]. When [gesturesAvailable] is false (Home Space, emulator, hand tracking
 * permission denied) each lesson degrades to the illustrated pose art plus the Settings / keyboard
 * fallback, and never blocks completing the tour. Practicing is encouraged, not required: the
 * continue button stays enabled throughout.
 */
@Composable
fun OnboardingCoach(
    onSpawnPane: () -> Boolean,
    onComplete: () -> Unit,
    layoutLocked: Boolean = false,
    petVisible: Boolean = false,
    gesturesAvailable: Boolean = false,
    onNarrationRequested: (Int?) -> Unit = {},
) {
    var step by rememberSaveable { mutableStateOf(OnboardingCoachStep.FIRST_TERMINAL) }
    var narrationEnabled by rememberSaveable { mutableStateOf(true) }
    // Spoken acks fire exactly once per practice, surviving recomposition and config changes.
    var lockAckPlayed by rememberSaveable { mutableStateOf(false) }
    var petAckPlayed by rememberSaveable { mutableStateOf(false) }
    // Track real gesture practice for the whole coaching pass (a user who prays before being
    // asked still gets credit), fed from hoisted state on every change.
    val machine = remember {
        GestureCoachMachine(initialLayoutLocked = layoutLocked, initialPetVisible = petVisible)
    }
    var progress by remember { mutableStateOf(machine.progress) }
    LaunchedEffect(layoutLocked, petVisible) {
        progress = machine.onFrame(layoutLocked = layoutLocked, petVisible = petVisible)
    }

    LifecycleResumeEffect(step, narrationEnabled) {
        onNarrationRequested(step.narrationRes.takeIf { narrationEnabled })
        onPauseOrDispose { onNarrationRequested(null) }
    }
    // One-time spoken acknowledgments when a practice lands (latched, latest-wins over the
    // step clip). Only the lesson currently on screen speaks its ack.
    LaunchedEffect(progress.lockPracticed, step, narrationEnabled) {
        if (progress.lockPracticed && !lockAckPlayed && step == OnboardingCoachStep.LOCK_LAYOUT) {
            lockAckPlayed = true
            if (narrationEnabled) onNarrationRequested(R.raw.coach_lock_done)
        }
    }
    LaunchedEffect(progress.petPracticed, step, narrationEnabled) {
        if (progress.petPracticed && !petAckPlayed && step == OnboardingCoachStep.SUMMON_PET) {
            petAckPlayed = true
            if (narrationEnabled) onNarrationRequested(R.raw.coach_pet_done)
        }
    }

    val actionLabel: String
    val onAction: () -> Unit
    when (step) {
        OnboardingCoachStep.FIRST_TERMINAL -> {
            actionLabel = "Show me the cockpit"
            onAction = { step = OnboardingCoachStep.SPAWN_PANE }
        }
        OnboardingCoachStep.SPAWN_PANE -> {
            actionLabel = "Spawn a pane"
            onAction = {
                if (onSpawnPane()) step = OnboardingCoachStep.LOCK_LAYOUT
            }
        }
        OnboardingCoachStep.LOCK_LAYOUT -> {
            actionLabel = if (gesturesAvailable && progress.lockPracticed) {
                "Next: your voice orb"
            } else {
                "Continue"
            }
            onAction = { step = OnboardingCoachStep.SUMMON_PET }
        }
        OnboardingCoachStep.SUMMON_PET -> {
            actionLabel = if (gesturesAvailable && progress.petPracticed) {
                "Finish the tour"
            } else {
                "Continue"
            }
            onAction = { step = OnboardingCoachStep.READY }
        }
        OnboardingCoachStep.READY -> {
            actionLabel = "Finish first flight"
            onAction = onComplete
        }
    }

    GxrPanel(
        modifier = Modifier
            .fillMaxSize()
            .testTag(ONBOARDING_COACH_TEST_TAG),
        color = GhosttyTokens.JewelOverlay,
        backgroundPainter = rememberOrbJewelPainter(),
        borderColor = GhosttyTokens.JewelOutline,
    ) {
        // Scroll body above a pinned CTA so the primary action is never clipped.
        Column(
            Modifier
                .fillMaxSize()
                .padding(20.dp),
        ) {
            Column(
                Modifier
                    .weight(1f, fill = true)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GxrText(
                        "FIRST FLIGHT  /  LIVE COCKPIT",
                        color = GhosttyTokens.Accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    GxrButton(
                        onClick = { narrationEnabled = !narrationEnabled },
                        modifier = Modifier.testTag(ONBOARDING_COACH_MUTE_TEST_TAG),
                    ) {
                        GxrText(if (narrationEnabled) "Voice on" else "Voice off", fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                step.illustrationRes?.let { res ->
                    Image(
                        painter = painterResource(res),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (step == OnboardingCoachStep.READY) 140.dp else 120.dp)
                            .heightIn(max = 140.dp),
                        contentScale = ContentScale.Fit,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                when (step) {
                    OnboardingCoachStep.FIRST_TERMINAL -> {
                        CoachTitle("This is your real Mac terminal.")
                        CoachBody(
                            "The shell and every agent keep running on your Mac when the headset disconnects.",
                        )
                    }

                    OnboardingCoachStep.SPAWN_PANE -> {
                        CoachTitle("Give another agent a pane.")
                        CoachBody(
                            "Each pane is a persistent tmux-backed workspace. Spawn one now beside this terminal.",
                        )
                    }

                    OnboardingCoachStep.LOCK_LAYOUT -> {
                        CoachTitle("Hands together to freeze the layout.")
                        CoachBody(
                            "XR pinches are twitchy — typing or a sip of water can fling a pane. " +
                                "Press both palms together, prayer-like, for about half a second to toggle " +
                                "Lock panels. Panes freeze exactly where they are; Alt+1–" +
                                "${CockpitLifecycle.MAX_PANES} still switches focus.",
                        )
                        Spacer(Modifier.height(12.dp))
                        CoachStatus(
                            text = when {
                                !gesturesAvailable ->
                                    "Hand tracking isn't available right now. Settings → Layout → " +
                                        "Lock panels flips the same switch."
                                progress.lockPracticed && progress.layoutLocked ->
                                    "✓ Layout locked. Hold the pose again to unlock."
                                progress.lockPracticed ->
                                    "✓ Layout unlocked. That's the toggle — one flip per hold."
                                else -> "Try it now: palms together, hold steady."
                            },
                            done = gesturesAvailable && progress.lockPracticed,
                        )
                    }

                    OnboardingCoachStep.SUMMON_PET -> {
                        CoachTitle("Palm up to summon your orb.")
                        CoachBody(
                            "Raise your non-pointing hand palm-up and hold — the orb settles onto your palm " +
                                "and listens. Flip your palm down to send what you said and put it away. " +
                                "Voice AI is unlocked for first flight; the gesture itself is always free.",
                        )
                        Spacer(Modifier.height(12.dp))
                        CoachStatus(
                            text = when {
                                !gesturesAvailable ->
                                    "Hand tracking isn't available right now. Alt+Space on the Mac " +
                                        "keyboard talks to the cockpit instead."
                                progress.petPracticed -> "✓ Summon, flip, done — you've got it."
                                progress.petSummoned -> "There it is. Flip your palm down to dismiss."
                                else -> "Try it now: palm up, hold for a beat."
                            },
                            done = gesturesAvailable && progress.petPracticed,
                        )
                    }

                    OnboardingCoachStep.READY -> {
                        CoachTitle("Your cockpit is ready.")
                        CoachBody(
                            "Voice AI is unlocked during first flight. Finish setup when you are ready; Pro remains optional in Settings.",
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            CoachAction(actionLabel, onAction)
        }
    }
}

@Composable
private fun CoachTitle(text: String) {
    GxrText(text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun CoachBody(text: String) {
    GxrText(
        text,
        color = GhosttyTokens.OnSurfaceVariant,
        fontSize = 13.sp,
        style = LocalGxrTextStyle.current.copy(lineHeight = 20.sp),
    )
}

/** Live acknowledgment for gesture practice; green once the pose landed. */
@Composable
private fun CoachStatus(text: String, done: Boolean) {
    GxrText(
        text,
        color = if (done) GhosttyTokens.Online else GhosttyTokens.Accent,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        style = LocalGxrTextStyle.current.copy(lineHeight = 18.sp),
        modifier = Modifier.testTag(ONBOARDING_COACH_STATUS_TEST_TAG),
    )
}

@Composable
private fun CoachAction(label: String, onClick: () -> Unit) {
    GxrButton(
        onClick = onClick,
        style = GxrButtonStyle.Primary,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ONBOARDING_COACH_ACTION_TEST_TAG),
    ) {
        GxrText(label)
    }
}
