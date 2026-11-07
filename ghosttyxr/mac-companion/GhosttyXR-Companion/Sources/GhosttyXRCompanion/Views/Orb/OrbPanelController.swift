import AppKit
import SwiftUI

/// Owns the floating orb window: a borderless, non-activating `NSPanel` that sits above
/// normal windows on every Space.
///
/// **Non-activating is load-bearing.** The keyboard relay installs a CGEvent tap and
/// forwards keys to the headset's focused terminal. If clicking the orb activated the
/// companion, the user's terminal would lose focus and the whole point of the overlay
/// would break. Hence `.nonactivatingPanel`, `becomesKeyOnlyIfNeeded`, and
/// `canBecomeKey` only while a panel with text input is showing.
@MainActor
final class OrbPanelController {
    private var panel: OrbPanel?
    private let state: OrbShellState
    private let content: AnyView
    private let settings: CompanionSettings
    private var screenObserver: NSObjectProtocol?
    private var outsideClickMonitor: Any?

    init(state: OrbShellState, settings: CompanionSettings, content: AnyView) {
        self.state = state
        self.settings = settings
        self.content = content
    }

    var isVisible: Bool { panel?.isVisible ?? false }

    func show() {
        let panel = panel ?? makePanel()
        self.panel = panel
        // Non-activating: order front without stealing focus from the user's terminal.
        panel.orderFrontRegardless()
        observeScreenChanges()
        observeOutsideClicks()
    }

    func hide() {
        state.dismissAll()
        panel?.orderOut(nil)
    }

    func toggle() {
        if isVisible {
            hide()
        } else {
            show()
        }
    }

    /// Recentres the orb on the main screen — the menu-bar escape hatch for an orb that
    /// ended up on a display that no longer exists.
    func resetPosition() {
        guard let screen = NSScreen.main else { return }
        let origin = Self.defaultOrigin(on: screen)
        settings.orbOrigin = origin
        panel?.setFrameOrigin(origin)
        show()
    }

    private func makePanel() -> OrbPanel {
        let panel = OrbPanel(
            contentRect: CGRect(origin: .zero, size: OrbShellMetrics.contentSize),
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )
        panel.isFloatingPanel = true
        panel.level = .floating
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = false
        panel.hidesOnDeactivate = false
        panel.becomesKeyOnlyIfNeeded = true
        panel.isMovableByWindowBackground = false
        panel.isReleasedWhenClosed = false
        panel.appearance = NSAppearance(named: .darkAqua)
        // Follow the user across Spaces and sit above fullscreen apps.
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]
        panel.animationBehavior = .utilityWindow
        panel.state = state
        state.panel = panel

        let container = OrbContainerView(state: state)
        container.frame = CGRect(origin: .zero, size: OrbShellMetrics.contentSize)
        let hosting = NSHostingView(rootView: content)
        hosting.frame = container.bounds
        hosting.autoresizingMask = [.width, .height]
        // The hosting view must not swallow clicks outside the drawn chrome; the
        // container's hitTest decides what is interactive.
        container.addSubview(hosting)
        panel.contentView = container

        panel.setFrameOrigin(resolvedOrigin())
        return panel
    }

    /// Restores the saved position, clamped onto a screen that actually exists today.
    private func resolvedOrigin() -> CGPoint {
        guard let saved = settings.orbOrigin else {
            return Self.defaultOrigin(on: NSScreen.main)
        }
        let frame = CGRect(origin: saved, size: OrbShellMetrics.contentSize)
        // The orb itself must be on-screen; the panel's transparent margins may hang off.
        let orbRect = CGRect(
            x: frame.minX + OrbShellMetrics.orbFrame.minX,
            y: frame.maxY - OrbShellMetrics.orbFrame.maxY,
            width: OrbShellMetrics.orbSize,
            height: OrbShellMetrics.orbSize
        )
        let onScreen = NSScreen.screens.contains { $0.visibleFrame.intersects(orbRect) }
        return onScreen ? saved : Self.defaultOrigin(on: NSScreen.main)
    }

    /// Lower-right of the screen — out of the way of window content and the menu bar.
    private static func defaultOrigin(on screen: NSScreen?) -> CGPoint {
        guard let screen else { return .zero }
        let visible = screen.visibleFrame
        let orbInset = OrbShellMetrics.contentSize.width - OrbShellMetrics.orbCenter.x
        return CGPoint(
            x: visible.maxX - orbInset - 40,
            y: visible.minY - (OrbShellMetrics.contentSize.height - OrbShellMetrics.orbCenter.y
                - OrbShellMetrics.orbRadius) + 120
        )
    }

    /// Clicking anywhere outside the orb collapses the menu, matching the reference
    /// prototype. A *global* monitor only observes — it never swallows the click, so the
    /// app the user clicked still receives it.
    private func observeOutsideClicks() {
        guard outsideClickMonitor == nil else { return }
        outsideClickMonitor = NSEvent.addGlobalMonitorForEvents(
            matching: [.leftMouseDown, .rightMouseDown]
        ) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, self.isVisible, !self.state.onboardingActive else { return }
                self.state.dismissAll()
            }
        }
    }

    /// A display can be unplugged while the orb sits on it; pull it back into view.
    private func observeScreenChanges() {
        guard screenObserver == nil else { return }
        screenObserver = NotificationCenter.default.addObserver(
            forName: NSApplication.didChangeScreenParametersNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, let panel = self.panel, panel.isVisible else { return }
                panel.setFrameOrigin(self.resolvedOrigin())
            }
        }
    }

    deinit {
        if let screenObserver {
            NotificationCenter.default.removeObserver(screenObserver)
        }
        if let outsideClickMonitor {
            NSEvent.removeMonitor(outsideClickMonitor)
        }
    }
}

/// `NSPanel` subclass that can take key focus *only* when the visible panel needs text
/// input (the onboarding password fields). Everything else stays non-activating so the
/// keyboard relay's event tap and the user's frontmost app are untouched.
private final class OrbPanel: NSPanel {
    weak var state: OrbShellState?

    override var canBecomeKey: Bool {
        state?.needsKeyboardFocus ?? false
    }

    override var canBecomeMain: Bool { false }

    /// Escape closes the panel, then the menu — matching the reference prototype.
    override func cancelOperation(_ sender: Any?) {
        state?.escape()
    }
}

/// Content view that is transparent to clicks everywhere except the orb, the fanned
/// menu items, and the open panel. Without this the invisible 480×780 window would
/// block a large rectangle of the user's desktop.
private final class OrbContainerView: NSView {
    private let state: OrbShellState

    init(state: OrbShellState) {
        self.state = state
        super.init(frame: .zero)
        wantsLayer = true
        layer?.backgroundColor = .clear
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("OrbContainerView is created in code only")
    }

    override var isOpaque: Bool { false }

    override func hitTest(_ point: NSPoint) -> NSView? {
        // `point` arrives in this view's superview coordinates; for a content view that
        // is the window's flipped-y content space. Convert to the SwiftUI top-left space
        // the metrics are expressed in.
        let local = convert(point, from: nil)
        let contentPoint = CGPoint(x: local.x, y: bounds.height - local.y)
        guard state.hitRegions.contains(contentPoint) else { return nil }
        return super.hitTest(point)
    }
}
