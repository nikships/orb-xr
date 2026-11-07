import AppKit
import SwiftUI

/// The whole floating surface: orb, radial menu, and whichever panel is open, laid out
/// inside one transparent window. Everything is positioned from `OrbShellMetrics` so the
/// AppKit hit-testing in `OrbPanelController` stays in agreement with what's drawn.
struct OrbShellView: View {
    private static let coordinateSpace = "orb-shell"

    @EnvironmentObject private var state: OrbShellState
    @EnvironmentObject private var settings: CompanionSettings
    @EnvironmentObject private var pairing: PairingController
    @EnvironmentObject private var keyboardRelay: MacKeyboardRelayController
    @EnvironmentObject private var windowMirror: MacWindowMirrorController

    let onOpenSettingsWindow: () -> Void

    /// Where the panel and the cursor were when the current drag began; `nil` while idle.
    @State private var dragAnchor: OrbDragAnchor?

    var body: some View {
        ZStack(alignment: .topLeading) {
            // Transparent canvas — the desktop shows through everywhere we don't draw.
            Color.clear

            OrbMenu()
                .position(OrbShellMetrics.orbCenter)

            OrbView(
                energy: energy,
                isActive: state.menuOpen || state.activePanel != nil,
                isPressed: dragAnchor != nil,
                size: OrbShellMetrics.orbSize,
                action: handleOrbTap
            )
            .position(OrbShellMetrics.orbCenter)
            .gesture(orbGesture)

            panelLayer
        }
        .frame(width: OrbShellMetrics.contentSize.width, height: OrbShellMetrics.contentSize.height)
        .coordinateSpace(name: Self.coordinateSpace)
        .opacity(settings.orbOpacity)
        .environment(\.colorScheme, .dark)
        .onAppear { state.onboardingActive = !pairing.onboardingComplete }
        .onChange(of: pairing.onboardingComplete) { _, complete in
            state.onboardingActive = !complete
            // Setup just finished: drop straight into the steady-state orb.
            if complete { state.dismissAll() }
        }
    }

    private var panelLayer: some View {
        activePanelContent
            .frame(width: OrbShellMetrics.panelWidth, alignment: .top)
            .fixedSize(horizontal: false, vertical: true)
            // Panels can outgrow the window on short screens; keep them inside it.
            .frame(maxHeight: OrbShellMetrics.panelMaxHeight, alignment: .top)
            // Report the drawn rect so clicks on the panel aren't treated as click-through.
            .background(
                GeometryReader { geo in
                    Color.clear.preference(
                        key: OrbPanelRectKey.self,
                        value: geo.frame(in: .named(Self.coordinateSpace))
                    )
                }
            )
            .offset(
                x: OrbShellMetrics.orbCenter.x - OrbShellMetrics.panelWidth / 2,
                y: OrbShellMetrics.panelTop
            )
            .onPreferenceChange(OrbPanelRectKey.self) { rect in
                state.hitRegions.panelRect = rect
            }
            .animation(.easeOut(duration: 0.16), value: state.activePanel)
    }

    @ViewBuilder
    private var activePanelContent: some View {
        if state.onboardingActive {
            OrbOnboarding()
        } else if let panel = state.activePanel {
            switch panel {
            case .qr:
                OrbQrPanel { state.activePanel = nil }
            case .keyboard:
                OrbKeyboardPanel { state.activePanel = nil }
            case .mirror:
                OrbMirrorPanel { state.activePanel = nil }
            case .settings:
                OrbSettingsPanel(
                    onClose: { state.activePanel = nil },
                    onOpenSettingsWindow: onOpenSettingsWindow
                )
            }
        }
    }

    /// Energy pushed into the galaxy: a live relay or mirror spins it up hard, an open
    /// menu just wakes it slightly. This is the payoff of using the orb over an icon.
    private var energy: Double {
        if keyboardRelay.state.status == .capturing || windowMirror.state.status == .streaming {
            return 0.5
        }
        if state.menuOpen || state.activePanel != nil || state.onboardingActive {
            return 0.2
        }
        return 0
    }

    private func handleOrbTap() {
        // During first flight the panel is the only path — don't offer the menu yet.
        guard !state.onboardingActive else { return }
        state.toggleMenu()
    }

    /// One gesture owns both tapping and dragging the orb, because a `Button` plus a
    /// drag would fire the button on mouse-up at the end of every move. A press that
    /// never travels past `dragSlop` toggles the menu; anything further moves the window
    /// and suppresses the tap.
    ///
    /// `minimumDistance: 0` is what lets this also serve as the tap recognizer.
    /// The gesture's own `translation` is unusable for the move: every coordinate space
    /// SwiftUI offers is anchored to the window, but dragging *moves* that window, so the
    /// cursor keeps landing back near its window-relative start and the reported
    /// translation collapses to a fraction of the real movement. Diff the screen-absolute
    /// mouse location instead.
    private var orbGesture: some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { _ in
                guard let panel = state.panel else { return }
                let mouse = NSEvent.mouseLocation
                guard var anchor = dragAnchor else {
                    dragAnchor = OrbDragAnchor(panelOrigin: panel.frame.origin, mouse: mouse)
                    return
                }
                let offset = CGPoint(x: mouse.x - anchor.mouse.x, y: mouse.y - anchor.mouse.y)
                if !anchor.didMove, hypot(offset.x, offset.y) > Self.dragSlop {
                    anchor.didMove = true
                    dragAnchor = anchor
                }
                guard anchor.didMove else { return }
                // Both are AppKit screen coordinates, so this needs no y flip.
                panel.setFrameOrigin(CGPoint(
                    x: anchor.panelOrigin.x + offset.x,
                    y: anchor.panelOrigin.y + offset.y
                ))
            }
            .onEnded { _ in
                let moved = dragAnchor?.didMove ?? false
                dragAnchor = nil
                guard moved else {
                    handleOrbTap()
                    return
                }
                guard let panel = state.panel else { return }
                settings.orbOrigin = panel.frame.origin
            }
    }

    /// Travel allowed before a press becomes a move instead of a tap.
    private static let dragSlop: CGFloat = 4
}

/// Screen-space starting point of an orb press, so movement can be tracked against the
/// mouse rather than against the window being dragged.
private struct OrbDragAnchor {
    let panelOrigin: CGPoint
    let mouse: CGPoint
    /// Set once the press travels far enough to count as a move, which cancels the tap.
    var didMove = false
}

/// Carries the measured panel rect out of the view tree so AppKit hit-testing can use it.
private struct OrbPanelRectKey: PreferenceKey {
    static let defaultValue: CGRect? = nil

    static func reduce(value: inout CGRect?, nextValue: () -> CGRect?) {
        value = nextValue() ?? value
    }
}
