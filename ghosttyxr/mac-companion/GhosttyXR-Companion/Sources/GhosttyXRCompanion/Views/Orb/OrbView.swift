import MetalKit
import SwiftUI

/// The orb itself: the product's Metal galaxy inside crisp technical rings.
struct OrbView: View {
    /// 0…1 energy for the galaxy: idle when nothing is happening, higher while the
    /// menu is open, highest while the keyboard or mirror is actually live.
    let energy: Double
    let isActive: Bool
    /// Driven by the shell's gesture, which owns both tap and drag.
    let isPressed: Bool
    let size: CGFloat
    let action: () -> Void

    @State private var isHovering = false

    var body: some View {
        OrbMetalView(energy: energy)
            .frame(width: size, height: size)
            .clipShape(Circle())
            .overlay(technicalRim)
            .contentShape(Circle())
            .scaleEffect(pressScale)
            .animation(.easeOut(duration: 0.14), value: isHovering)
            .animation(.easeOut(duration: 0.12), value: isPressed)
            .onHover { isHovering = $0 }
            .help(isActive ? "Close orb menu" : "Open orb menu")
            .accessibilityElement()
            .accessibilityAddTraits(.isButton)
            .accessibilityLabel(isActive ? "Close orb menu" : "Open orb menu")
            .accessibilityAction(.default, action)
        .frame(width: size, height: size)
    }

    private var pressScale: CGFloat {
        if isPressed { return 0.975 }
        return isHovering ? 1.015 : 1.0
    }

    private var technicalRim: some View {
        ZStack {
            Circle()
                .strokeBorder(
                    isActive
                        ? GhosttyTheme.neonMagenta
                        : (isHovering ? GhosttyTheme.neonCyan : GhosttyTheme.panelStrokeStrong),
                    lineWidth: 1
                )
            Circle()
                .inset(by: 4)
                .strokeBorder(GhosttyTheme.panelStrokeSubtle, lineWidth: 1)
        }
        .allowsHitTesting(false)
    }
}

/// Hosts the `MTKView` that runs `OrbShader.metal`. When Metal is unavailable the orb
/// degrades to a static glass marble rather than an empty hole in the window.
private struct OrbMetalView: NSViewRepresentable {
    let energy: Double

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    func makeNSView(context: Context) -> NSView {
        guard let renderer = context.coordinator.renderer else {
            return OrbFallbackView()
        }
        let view = MTKView(frame: .zero, device: renderer.device)
        view.wantsLayer = true
        view.layer?.backgroundColor = .clear
        // Transparent premultiplied compositing over the desktop.
        (view.layer as? CAMetalLayer)?.isOpaque = false
        renderer.attach(to: view)
        context.coordinator.view = view
        context.coordinator.observeOcclusion(of: view)
        return view
    }

    func updateNSView(_ nsView: NSView, context: Context) {
        context.coordinator.renderer?.audioLevel = energy
    }

    static func dismantleNSView(_ nsView: NSView, coordinator: Coordinator) {
        coordinator.stopObserving()
    }

    @MainActor
    final class Coordinator {
        let renderer = OrbRenderer(seed: OrbShellMetrics.seed)
        weak var view: MTKView?
        private var occlusionObserver: NSObjectProtocol?

        /// No point burning GPU cycles on an orb nobody can see (other Space,
        /// fullscreen app on top, display asleep).
        func observeOcclusion(of view: MTKView) {
            occlusionObserver = NotificationCenter.default.addObserver(
                forName: NSWindow.didChangeOcclusionStateNotification,
                object: nil,
                queue: .main
            ) { [weak self, weak view] notification in
                guard let self,
                      let view,
                      let window = notification.object as? NSWindow,
                      window === view.window
                else {
                    return
                }
                MainActor.assumeIsolated {
                    self.renderer?.isVisible = window.occlusionState.contains(.visible)
                }
            }
        }

        func stopObserving() {
            if let occlusionObserver {
                NotificationCenter.default.removeObserver(occlusionObserver)
            }
            occlusionObserver = nil
        }
    }
}

/// Static stand-in for machines where Metal or the shader library is unavailable.
private final class OrbFallbackView: NSView {
    override func draw(_ dirtyRect: NSRect) {
        guard let context = NSGraphicsContext.current?.cgContext else { return }
        let radius = min(bounds.width, bounds.height) / 2
        let center = CGPoint(x: bounds.midX, y: bounds.midY)
        let colors = [
            NSColor(srgbRed: 0.29, green: 0.23, blue: 0.62, alpha: 1).cgColor,
            NSColor(srgbRed: 0.06, green: 0.09, blue: 0.20, alpha: 1).cgColor
        ] as CFArray
        guard let gradient = CGGradient(
            colorsSpace: CGColorSpaceCreateDeviceRGB(),
            colors: colors,
            locations: [0, 1]
        ) else {
            return
        }
        context.addEllipse(in: CGRect(
            x: center.x - radius,
            y: center.y - radius,
            width: radius * 2,
            height: radius * 2
        ))
        context.clip()
        context.drawRadialGradient(
            gradient,
            startCenter: CGPoint(x: center.x - radius * 0.3, y: center.y + radius * 0.3),
            startRadius: 0,
            endCenter: center,
            endRadius: radius,
            options: []
        )
    }
}
