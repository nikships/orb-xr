import AppKit
import MetalKit
import simd

/// The orb's fixed companion palette. One creature per device family: this matches the
/// desktop orb in the reference prototype (black-glass marble, cyan/violet galaxy).
enum OrbPalette {
    static let anchor = SIMD3<Float>(0x10 / 255.0, 0x16 / 255.0, 0x32 / 255.0) // #101632
    static let accent0 = SIMD3<Float>(0x4C / 255.0, 0xC9 / 255.0, 0xF0 / 255.0) // #4CC9F0
    static let accent1 = SIMD3<Float>(0x7B / 255.0, 0x5C / 255.0, 0xFF / 255.0) // #7B5CFF
    static let accent2 = SIMD3<Float>(0xB8 / 255.0, 0xF1 / 255.0, 0xFF / 255.0) // #B8F1FF
}

/// Uniform block layout — must match `OrbUniforms` in `OrbShader.metal`.
/// `SIMD3<Float>` is 16-byte aligned on both sides, matching MSL `float3`.
private struct OrbUniforms {
    var resolution: SIMD2<Float>
    var background: SIMD3<Float>
    var anchor: SIMD3<Float>
    var accent0: SIMD3<Float>
    var accent1: SIMD3<Float>
    var accent2: SIMD3<Float>
    var time: Float
    var phase: Float
    var audio: Float
    var spin: Float
    var archetype: Float
    var lens: Float
}

/// Drives `OrbShader.metal` for one orb instance.
///
/// The orb is drawn straight into the `MTKView` drawable — the website's shared-canvas
/// trick exists to amortise many orbs on one page; the companion only ever shows one,
/// so an extra offscreen texture + blit would just cost bandwidth.
///
/// Frame pacing: 60fps while the orb is "hot" (menu open, relay capturing, mirror live),
/// 30fps idle, and fully paused while the panel is occluded. Reduced-motion draws one
/// static frame and stops.
final class OrbRenderer: NSObject, MTKViewDelegate {
    /// 0…1 energy pushed in by the app; drives spin, flare and aurora intensity.
    var audioLevel: Double = 0 {
        didSet { syncPreferredFrameRate() }
    }
    /// False while the hosting window is occluded — stops drawing entirely.
    /// `OrbMotion` clamps its delta, so the wall-clock gap can't land as one huge step.
    var isVisible = true {
        didSet {
            guard isVisible != oldValue else { return }
            view?.isPaused = !isVisible || reduceMotion
        }
    }

    private var motion: OrbMotion
    private let commandQueue: MTLCommandQueue
    private let pipeline: MTLRenderPipelineState
    private weak var view: MTKView?
    private let reduceMotion: Bool
    private var didDrawStaticFrame = false

    /// Fails when Metal is unavailable or the shader library is missing — callers fall
    /// back to a non-Metal orb rather than shipping a broken window.
    init?(seed: String) {
        guard let device = MTLCreateSystemDefaultDevice(),
              let queue = device.makeCommandQueue(),
              let library = try? device.makeDefaultLibrary(bundle: .main),
              let vertexFunction = library.makeFunction(name: "orbVertex"),
              let fragmentFunction = library.makeFunction(name: "orbFragment")
        else {
            return nil
        }

        let descriptor = MTLRenderPipelineDescriptor()
        descriptor.vertexFunction = vertexFunction
        descriptor.fragmentFunction = fragmentFunction
        descriptor.colorAttachments[0].pixelFormat = .bgra8Unorm
        guard let state = try? device.makeRenderPipelineState(descriptor: descriptor) else {
            return nil
        }

        self.commandQueue = queue
        self.pipeline = state
        self.motion = OrbMotion(seed: seed)
        self.reduceMotion = NSWorkspace.shared.accessibilityDisplayShouldReduceMotion
        super.init()
    }

    var device: MTLDevice { commandQueue.device }

    func attach(to view: MTKView) {
        self.view = view
        view.delegate = self
        view.device = commandQueue.device
        view.colorPixelFormat = .bgra8Unorm
        view.clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 0)
        view.framebufferOnly = true
        view.layer?.isOpaque = false
        view.enableSetNeedsDisplay = false
        view.isPaused = reduceMotion
        syncPreferredFrameRate()
        if reduceMotion {
            // One frame so the orb is still a galaxy, just a frozen one.
            view.draw()
        }
    }

    func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {}

    func draw(in view: MTKView) {
        // Reduced motion: exactly one frame, ever.
        if reduceMotion {
            guard !didDrawStaticFrame else { return }
            didDrawStaticFrame = true
        }
        guard let drawable = view.currentDrawable,
              let descriptor = view.currentRenderPassDescriptor,
              let buffer = commandQueue.makeCommandBuffer(),
              let encoder = buffer.makeRenderCommandEncoder(descriptor: descriptor)
        else {
            return
        }

        let time = CACurrentMediaTime() + motion.timeOffset
        motion.update(time: time, audio: audioLevel)

        let pixelSize = Float(max(drawable.texture.width, drawable.texture.height))
        var uniforms = OrbUniforms(
            resolution: SIMD2<Float>(pixelSize, pixelSize),
            background: SIMD3<Float>(0, 0, 0),
            anchor: OrbPalette.anchor,
            accent0: OrbPalette.accent0,
            accent1: OrbPalette.accent1,
            accent2: OrbPalette.accent2,
            time: Float(time),
            phase: Float(motion.phase),
            audio: Float(motion.audioSmooth),
            spin: Float(motion.spin),
            // "spiral" — the desktop orb's fixed archetype (index 0).
            archetype: 0,
            // Lens/chromatic pass is only worth its cost above ~48px, matching the web orb.
            lens: pixelSize >= 48 ? 0.4 : 0
        )

        encoder.setRenderPipelineState(pipeline)
        encoder.setFragmentBytes(&uniforms, length: MemoryLayout<OrbUniforms>.stride, index: 0)
        encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
        encoder.endEncoding()
        buffer.present(drawable)
        buffer.commit()
    }

    /// Full rate only when something is actually happening — an always-60fps
    /// transparent overlay is a needless battery drain on a laptop.
    private func syncPreferredFrameRate() {
        guard let view, !reduceMotion else { return }
        let target = audioLevel > 0 ? 60 : 30
        if view.preferredFramesPerSecond != target {
            view.preferredFramesPerSecond = target
        }
    }
}
