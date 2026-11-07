package com.ghosttyxr.app.terminal.hands

import androidx.xr.runtime.math.Pose
import com.ghosttyxr.app.terminal.voice.command.LiveSpeechOutput

/**
 * The thing the palm-following voice pet is drawn into. Production backs this with a SceneCore
 * `PanelEntity` hosting the ASCII hologram view (built in `wiring/`), pose-driven onto the palm
 * each frame — the documented way to attach content to a hand joint (a Compose `SpatialPanel`
 * can't be placed in activity-space hand coordinates).
 *
 * Kept as a tiny interface so [HandMenuController] (which owns the gesture + pose math) is
 * unit-test driven with a fake, no emulator — mirroring the project's injection discipline.
 */
interface VoicePetSurface {
    /**
     * Reveal the pet with a short entrance animation. [onRevealed] fires exactly once when the
     * animation completes — it is the mic-open trigger. It MUST NOT fire if [hide] is called
     * before the animation finishes (the palm flipped back down mid-reveal).
     */
    fun show(onRevealed: () -> Unit)

    /**
     * Play a brief "wrapping up" beat, then hide (kept alive for the next summon). Cancels any
     * pending [show] callback. Idempotent and safe to call mid-reveal.
     */
    fun hide()

    /** Reposition the pet in activity space. Called every tracked frame while visible. */
    fun setPose(pose: Pose)

    /** Tear down the pet's entities and hosted views. */
    fun dispose()
}

/**
 * Production pet surface capability: visual hand surface plus its spatialized Live PCM sink.
 * Hand gesture tests continue to depend only on [VoicePetSurface].
 */
interface SpatialVoicePetSurface : VoicePetSurface, LiveSpeechOutput
