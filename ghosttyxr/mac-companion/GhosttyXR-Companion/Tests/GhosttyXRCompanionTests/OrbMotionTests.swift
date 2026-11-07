import XCTest

@testable import GhosttyXRCompanion

/// `OrbMotion` is a port of `updateMotion` in `website/src/components/voice/orb-renderer.ts`.
/// These vectors were produced by running that exact TS function under node, so drift on
/// either side shows up here rather than as an orb that visibly animates differently from
/// the headset and the marketing site.
final class OrbMotionTests: XCTestCase {
    private let seed = "orb-desktop"

    // MARK: hashSeed parity

    func testHashSeedMatchesReferenceImplementation() {
        XCTAssertEqual(OrbMotion.hashSeed("orb-desktop"), 1_136_665_995)
        XCTAssertEqual(OrbMotion.hashSeed("grok"), 2_692_908_486)
        XCTAssertEqual(OrbMotion.hashSeed("a"), 3_826_002_220)
        // FNV-1a offset basis, unchanged for the empty string.
        XCTAssertEqual(OrbMotion.hashSeed(""), 2_166_136_261)
    }

    func testSeedDerivedConstantsMatchReference() {
        let motion = OrbMotion(seed: seed)
        XCTAssertEqual(motion.phase, 2.182, accuracy: 1e-9)
        XCTAssertEqual(motion.timeOffset, 1.995, accuracy: 1e-9)
        // Initial spin is phase * 3.7 in the reference.
        XCTAssertEqual(motion.spin, 2.182 * 3.7, accuracy: 1e-9)
    }

    // MARK: Motion integration

    func testIdleSpinMatchesReferenceAfterTwoSeconds() {
        var motion = OrbMotion(seed: seed)
        for frame in 0...120 {
            motion.update(time: Double(frame) / 60, audio: 0)
        }

        XCTAssertEqual(motion.spin, 9.678_875_944_527_49, accuracy: 1e-9)
        XCTAssertEqual(motion.spinVelocity, 0.938_379_826_769_206_4, accuracy: 1e-9)
        // The idle oscillator has flipped the galaxy's direction once by 1.6s.
        XCTAssertEqual(motion.spinDirection, -1)
        // No energy in means no glow.
        XCTAssertEqual(motion.audioSmooth, 0, accuracy: 1e-12)
        XCTAssertEqual(motion.audioFast, 0, accuracy: 1e-12)
    }

    func testEnergyRaisesEnvelopesAndSpinMatchesReference() {
        var motion = OrbMotion(seed: seed)
        for frame in 0...60 {
            motion.update(time: Double(frame) / 60, audio: 0)
        }
        // Relay starts capturing: push energy in for the next two seconds.
        for frame in 61...180 {
            motion.update(time: Double(frame) / 60, audio: 0.5)
        }

        XCTAssertEqual(motion.spin, 12.640_670_740_360_64, accuracy: 1e-9)
        XCTAssertEqual(motion.spinVelocity, 2.002_500_469_840_702_3, accuracy: 1e-9)
        XCTAssertEqual(motion.audioSmooth, 0.499_999_993_650_98, accuracy: 1e-9)
        XCTAssertEqual(motion.audioFast, 0.5, accuracy: 1e-9)
        // Energy spins it up well past the idle rate — the visible "busy" tell.
        XCTAssertGreaterThan(motion.spinVelocity, 2)
    }

    func testDirectionFlipsOnReferenceFrames() {
        var motion = OrbMotion(seed: seed)
        var direction = motion.spinDirection
        var flipFrames: [Int] = []
        for frame in 0...1200 {
            motion.update(time: Double(frame) / 60, audio: 0)
            if motion.spinDirection != direction {
                flipFrames.append(frame)
                direction = motion.spinDirection
            }
        }

        XCTAssertEqual(flipFrames, [96, 408, 720, 1033])
    }

    func testSustainedEnergyHoldsDirectionThroughQueuedFlips() {
        var motion = OrbMotion(seed: seed)
        let startDirection = motion.spinDirection
        // Above the 0.18 fast-envelope gate, so queued flips must not be applied.
        for frame in 0...600 {
            motion.update(time: Double(frame) / 60, audio: 1.0)
        }

        XCTAssertEqual(motion.spinDirection, startDirection)
        XCTAssertGreaterThan(motion.audioFast, 0.18)
    }

    /// Energy rises fast and decays slowly (0.11s vs 0.3s response), so the orb flares
    /// the instant forwarding starts but eases back down rather than snapping off.
    func testEnergyRisesFasterThanItDecays() {
        var rising = OrbMotion(seed: seed)
        rising.update(time: 0, audio: 1)
        rising.update(time: 0.1, audio: 1)
        XCTAssertEqual(rising.audioSmooth, 0.597_109_678_470_867, accuracy: 1e-9)

        var falling = OrbMotion(seed: seed)
        falling.update(time: 0, audio: 1)
        for frame in 1...60 {
            falling.update(time: Double(frame) / 60, audio: 1)
        }
        let settled = falling.audioSmooth
        // Same 0.1s window, energy cut.
        falling.update(time: 1 + 0.1, audio: 0)
        let decayed = settled - falling.audioSmooth

        XCTAssertEqual(decayed, 0.283_436_746_592_387_05, accuracy: 1e-9)
        XCTAssertGreaterThan(rising.audioSmooth, decayed)
    }

    /// The renderer pauses while the panel is occluded, so the next frame can arrive
    /// minutes later. The reference clamps delta to 0.1s precisely so that gap doesn't
    /// teleport the galaxy; without it the orb would visibly snap on every unhide.
    func testDeltaIsClampedSoResumingAfterOcclusionDoesNotJump() {
        var motion = OrbMotion(seed: seed)
        motion.update(time: 0, audio: 0)
        let before = motion.spin

        // One frame after a 10-minute occlusion gap.
        motion.update(time: 600, audio: 0)

        XCTAssertEqual(motion.spin - before, 0.020_611_846_418_189_472, accuracy: 1e-9)
    }
}
