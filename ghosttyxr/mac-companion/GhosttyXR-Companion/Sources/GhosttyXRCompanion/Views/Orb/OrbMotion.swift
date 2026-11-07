import Foundation

/// Idle + reactive motion for the orb, ported 1:1 from `updateMotion` in
/// `website/src/components/voice/orb-renderer.ts` (the source of truth for the orb).
///
/// The galaxy inside the orb turns on its own; pushing energy in (relay capturing,
/// mirror live, menu open) makes it spin harder and flare. Direction flips are queued
/// by a slow per-orb oscillator and only applied while the fast audio envelope is
/// quiet, so the orb never visibly stutters mid-flare.
///
/// Kept free of Metal/AppKit so it can be unit-tested against the TS reference.
struct OrbMotion {
    /// Per-seed constant that decorrelates every orb instance (shader `uPhase`).
    let phase: Double
    /// Seconds added to the render clock so two orbs with different seeds never
    /// animate in lockstep.
    let timeOffset: Double

    private(set) var spin: Double
    private(set) var spinVelocity: Double = 0
    private(set) var spinDirection: Double = 1
    /// Slow envelope driving the shader's `uAudio` (glow, aurora, pulsar beat).
    private(set) var audioSmooth: Double = 0
    /// Fast envelope driving spin velocity and the attack impulse.
    private(set) var audioFast: Double = 0

    private var previousAudio: Double = 0
    private var flipQueued = false
    private var oscillatorSign: Double = 1
    private var lastTime: Double?

    init(seed: String) {
        let hash = OrbMotion.hashSeed(seed)
        phase = Double(hash % 6283) / 1000
        timeOffset = Double(hash % 4000) / 1000
        spin = phase * 3.7
    }

    /// FNV-1a over UTF-16 code units — matches the TS `hashSeed` bit for bit
    /// (`Math.imul` and `^=` both operate on 32-bit patterns).
    static func hashSeed(_ seed: String) -> UInt32 {
        var hash: UInt32 = 0x811c_9dc5
        for unit in seed.utf16 {
            hash ^= UInt32(unit)
            hash = hash &* 0x0100_0193
        }
        return hash
    }

    /// Advances the motion model. `time` is seconds on a monotonic clock (already
    /// offset by `timeOffset`); `audio` is the 0…1 energy pushed in by the app.
    mutating func update(time: Double, audio: Double) {
        let delta = lastTime.map { min(0.1, max(0, time - $0)) } ?? 0
        lastTime = time

        let level = min(1, max(0, audio))
        // Asymmetric envelopes: energy rises fast and decays slowly.
        let smoothResponse = level > audioSmooth ? 0.11 : 0.3
        audioSmooth += (level - audioSmooth) * (delta > 0 ? 1 - exp(-delta / smoothResponse) : 0)
        let fastResponse = level > audioFast ? 0.04 : 0.18
        audioFast += (level - audioFast) * (delta > 0 ? 1 - exp(-delta / fastResponse) : 0)

        let variance = (6.31 * phase).truncatingRemainder(dividingBy: 1)
        let wave = 0.35 * sin(time * (0.11 + 0.08 * (2.17 * phase).truncatingRemainder(dividingBy: 1)) + phase)

        let oscillator = sin(time * (0.45 + 0.2 * variance) + phase)
        let sign: Double = oscillator > 0 ? 1 : (oscillator < 0 ? -1 : 0)
        if sign != oscillatorSign {
            oscillatorSign = sign
            flipQueued = true
        }
        // Only reverse while the orb is calm, so a flare never reverses mid-flight.
        if flipQueued && audioFast < 0.18 {
            spinDirection *= -1
            flipQueued = false
        }

        let targetVelocity = 0.65 * (0.65 + 0.7 * variance) * (1 + wave)
            + spinDirection * audioFast * 2.2
        spinVelocity += (targetVelocity - spinVelocity) * (delta > 0 ? 1 - exp(-delta / 0.35) : 0)

        let attack = max(0, audioFast - previousAudio)
        previousAudio = audioFast
        spinVelocity += spinDirection * min(6 * attack, 1.4) * delta * 14

        spin += spinVelocity * delta
    }
}
