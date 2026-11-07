import AppKit
import Combine
import SwiftUI

/// The four things the Mac side actually does, fanned around the orb.
enum OrbMenuItem: String, CaseIterable, Identifiable {
    case qr
    case keyboard
    case mirror
    case settings

    var id: String { rawValue }

    /// Arc positions from the reference prototype (degrees, y down → negative fans up).
    var angle: Double {
        switch self {
        case .qr: return -168
        case .keyboard: return -124
        case .mirror: return -56
        case .settings: return -12
        }
    }

    var label: String {
        switch self {
        case .qr: return "Show QR code"
        case .keyboard: return "Forward keyboard"
        case .mirror: return "Start mirror"
        case .settings: return "Settings"
        }
    }

    var symbol: String {
        switch self {
        case .qr: return "qrcode"
        case .keyboard: return "keyboard"
        case .mirror: return "rectangle.on.rectangle"
        case .settings: return "gearshape"
        }
    }
}

/// UI state for the orb shell: which menu/panel is open, plus the hit regions the
/// AppKit container needs so the rest of the transparent window stays click-through.
@MainActor
final class OrbShellState: ObservableObject {
    @Published var menuOpen = false
    @Published var activePanel: OrbMenuItem?
    /// While onboarding is incomplete the shell shows the setup flow instead of the
    /// radial menu — first flight has exactly one path.
    @Published var onboardingActive = false

    /// Read by `OrbContainerView.hitTest` on every mouse event.
    let hitRegions = OrbHitRegions()

    /// The floating window, so the orb's drag gesture can move it. Weak: the controller
    /// owns the panel's lifetime.
    weak var panel: NSWindow?

    private var cancellables: Set<AnyCancellable> = []

    init() {
        // Keep the AppKit hit regions in lockstep with the SwiftUI state.
        $menuOpen
            .sink { [weak self] open in self?.hitRegions.menuOpen = open }
            .store(in: &cancellables)
    }

    /// The panel needs real key focus only for the onboarding password fields; keeping
    /// this false the rest of the time is what stops the orb stealing terminal focus.
    var needsKeyboardFocus: Bool { onboardingActive }

    func toggleMenu() {
        if activePanel != nil {
            activePanel = nil
            return
        }
        menuOpen.toggle()
    }

    func select(_ item: OrbMenuItem) {
        activePanel = activePanel == item ? nil : item
    }

    /// Escape: close the panel first, then the menu.
    func escape() {
        if activePanel != nil {
            activePanel = nil
            return
        }
        menuOpen = false
    }

    func dismissAll() {
        activePanel = nil
        menuOpen = false
    }
}
