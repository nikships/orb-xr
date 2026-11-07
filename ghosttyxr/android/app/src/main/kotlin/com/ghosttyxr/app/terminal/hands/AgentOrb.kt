package com.ghosttyxr.app.terminal.hands

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

enum class OrbArchetype(val id: Int) {
    Spiral(0),
    Nebula(1),
    Core(2),
    Deep(3),
}

data class OrbPalette(
    val anchor: Int,
    val c0: Int,
    val c1: Int,
    val c2: Int,
)

data class AgentOrbConfig(
    val seed: String,
    val palette: OrbPalette,
    val archetype: OrbArchetype,
)

data class AgentOrbState(
    val seed: String,
    val phase: Double,
    val archetype: Int,
    val palette: OrbPalette,
    var audioSmooth: Double,
    var audioFast: Double,
    var spinDirection: Double,
    var spinVelocity: Double,
    var previousAudio: Double,
    var flipQueued: Boolean,
    var oscillatorSign: Double,
    var spin: Double,
    var lastTimeSec: Double?,
)

val GhosttyBrandPalette = OrbPalette(
    anchor = 0x0A1B2E,
    c0 = 0x3551F3,
    c1 = 0x7B5CFF,
    c2 = 0xCFF2FF,
)

object AgentOrb {

    fun hashSeed(seed: String): Int {
        var hash = 0x811c9dc5.toInt()
        for (character in seed) {
            hash = hash xor character.code
            hash *= 0x01000193
        }
        return hash
    }

    fun createOrbState(config: AgentOrbConfig): AgentOrbState {
        val phase = (hashSeed(config.seed).toUInt().toLong() % 6283L) / 1000.0
        return AgentOrbState(
            seed = config.seed,
            phase = phase,
            archetype = config.archetype.id,
            palette = config.palette,
            audioSmooth = 0.0,
            audioFast = 0.0,
            spinDirection = 1.0,
            spinVelocity = 0.0,
            previousAudio = 0.0,
            flipQueued = false,
            oscillatorSign = 1.0,
            spin = phase * 3.7,
            lastTimeSec = null,
        )
    }

    /**
     * @param excitement 0..1 drive for the spin integrator. The orb is never on screen while the
     *   pet talks (you turn your palm over to submit), so this carries hand motion rather than
     *   audio: flick your wrist and the ball visibly winds up.
     */
    fun updateMotion(state: AgentOrbState, timeSec: Double, excitement: Double = 0.0) {
        val delta = state.lastTimeSec?.let { min(0.1, max(0.0, timeSec - it)) } ?: 0.0
        state.lastTimeSec = timeSec
        val audio = excitement.coerceIn(0.0, 1.0)
        val smoothResponse = if (audio > state.audioSmooth) 0.11 else 0.3
        state.audioSmooth += (audio - state.audioSmooth) * if (delta > 0.0) {
            1.0 - exp(-delta / smoothResponse)
        } else {
            0.0
        }
        val fastResponse = if (audio > state.audioFast) 0.04 else 0.18
        state.audioFast += (audio - state.audioFast) * if (delta > 0.0) {
            1.0 - exp(-delta / fastResponse)
        } else {
            0.0
        }

        val variance = (6.31 * state.phase) % 1.0
        val wave = 0.35 * sin(timeSec * (0.11 + 0.08 * ((2.17 * state.phase) % 1.0)) + state.phase)
        val oscillator = sin(timeSec * (0.45 + 0.2 * variance) + state.phase)
        if (sign(oscillator) != state.oscillatorSign) {
            state.oscillatorSign = sign(oscillator)
            state.flipQueued = true
        }
        if (state.flipQueued && state.audioFast < 0.18) {
            state.spinDirection *= -1.0
            state.flipQueued = false
        }

        val targetVelocity = 0.65 * (0.65 + 0.7 * variance) * (1.0 + wave) +
            state.spinDirection * state.audioFast * 2.2
        state.spinVelocity += (targetVelocity - state.spinVelocity) * if (delta > 0.0) {
            1.0 - exp(-delta / 0.35)
        } else {
            0.0
        }
        val attack = max(0.0, state.audioFast - state.previousAudio)
        state.previousAudio = state.audioFast
        state.spinVelocity += state.spinDirection * min(6.0 * attack, 1.4) * delta * 14.0
        state.spin += state.spinVelocity * delta
    }
}
