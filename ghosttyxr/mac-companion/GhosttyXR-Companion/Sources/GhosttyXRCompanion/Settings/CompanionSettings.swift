import Combine
import CoreGraphics
import Foundation
import ServiceManagement

/// App-level user preferences.
///
/// Owns launch-at-login (backed by `SMAppService`) and the floating orb's appearance /
/// position. The window-mirror bitrate ceiling deliberately stays on
/// `MacWindowMirrorController` (it applies live to the encoder); `SettingsView` binds to
/// that controller directly rather than duplicating it here.
@MainActor
final class CompanionSettings: ObservableObject {
    private enum Keys {
        static let orbOriginX = "dev.ghosttyxr.companion.orb.originX"
        static let orbOriginY = "dev.ghosttyxr.companion.orb.originY"
        static let orbOpacity = "dev.ghosttyxr.companion.orb.opacity"
    }

    /// Registration state for launch-at-login. `didSet` applies the change through
    /// `SMAppService`; errors are surfaced (no silent fallback) via `lastLoginItemError`.
    @Published var launchAtLogin: Bool {
        didSet {
            guard launchAtLogin != oldValue else { return }
            guard !isApplying else { return }
            applyLaunchAtLogin(oldValue: oldValue)
        }
    }

    /// Set when an `SMAppService` register/unregister call fails so the UI can show it.
    @Published private(set) var lastLoginItemError: String?
    private var isApplying = false

    /// Overall orb opacity, so it can be faded back over a busy desktop.
    @Published var orbOpacity: Double {
        didSet {
            guard orbOpacity != oldValue else { return }
            UserDefaults.standard.set(orbOpacity, forKey: Keys.orbOpacity)
        }
    }

    /// Bottom-left origin of the orb panel in screen coordinates. `nil` until the orb has
    /// been positioned once, which makes the controller fall back to its default corner.
    var orbOrigin: CGPoint? {
        didSet {
            guard let orbOrigin else { return }
            UserDefaults.standard.set(Double(orbOrigin.x), forKey: Keys.orbOriginX)
            UserDefaults.standard.set(Double(orbOrigin.y), forKey: Keys.orbOriginY)
        }
    }

    init() {
        let defaults = UserDefaults.standard
        self.launchAtLogin = SMAppService.mainApp.status == .enabled
        let storedOpacity = defaults.double(forKey: Keys.orbOpacity)
        self.orbOpacity = storedOpacity > 0 ? min(max(storedOpacity, 0.35), 1) : 1
        if defaults.object(forKey: Keys.orbOriginX) != nil,
           defaults.object(forKey: Keys.orbOriginY) != nil {
            self.orbOrigin = CGPoint(
                x: defaults.double(forKey: Keys.orbOriginX),
                y: defaults.double(forKey: Keys.orbOriginY)
            )
        }
    }

    private func applyLaunchAtLogin(oldValue: Bool) {
        isApplying = true
        defer { isApplying = false }

        do {
            if launchAtLogin {
                try SMAppService.mainApp.register()
            } else {
                try SMAppService.mainApp.unregister()
            }
            lastLoginItemError = nil
        } catch {
            // Revert the published flag to the true system state and surface the error.
            launchAtLogin = oldValue
            lastLoginItemError = error.localizedDescription
        }
    }
}
