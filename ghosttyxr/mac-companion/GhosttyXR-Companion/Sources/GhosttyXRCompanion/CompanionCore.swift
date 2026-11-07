import AppKit
import SwiftUI

/// Owns every app-lifetime service and the orb that controls them.
///
/// These outlive any window: the keyboard relay server, terminal-handoff listener and
/// window mirror keep serving the headset whether or not the orb is on screen. The orb
/// is a borderless non-activating `NSPanel` rather than a SwiftUI scene, so it can't be
/// created from a scene's lifecycle — the app delegate builds this at launch instead.
@MainActor
final class CompanionCore {
    let settings = CompanionSettings()
    let pairing = PairingController()
    let tmuxSetup = TmuxSetupController()
    let keyboardRelay = MacKeyboardRelayController()
    let windowMirror = MacWindowMirrorController()
    /// Always-on loopback listener for the pet's "send terminals to Mac" — starts with
    /// the app so the headset's tunnel always finds it.
    let terminalHandoff = MacTerminalHandoffController()
    let orbState = OrbShellState()

    private(set) var orb: OrbPanelController?

    init() {
        // Symbiosis: keyboard follows the pointer — capture while XR owns the mouse.
        windowMirror.onPointerOwnerChanged = { [weak keyboardRelay] owner in
            keyboardRelay?.setSymbiosisCapture(owner == .xr)
        }
        // Emergency chord releases the mouse too, not just the keyboard.
        keyboardRelay.onEmergencyStopped = { [weak windowMirror] in
            windowMirror?.forcePointerToMac()
        }
        // While keyboard capture is active it is the newer event tap, so the
        // pointer-ownership chord must be handled here as well as in Symbiosis.
        keyboardRelay.onPointerOwnershipToggle = { [weak windowMirror] in
            windowMirror?.togglePointerOwnership()
        }
    }

    /// Builds the orb panel and shows it. Called once, at launch.
    func startOrb(openSettingsWindow: @escaping () -> Void) {
        guard orb == nil else { return }
        let shell = OrbShellView(onOpenSettingsWindow: openSettingsWindow)
            .environmentObject(orbState)
            .environmentObject(settings)
            .environmentObject(pairing)
            .environmentObject(tmuxSetup)
            .environmentObject(keyboardRelay)
            .environmentObject(windowMirror)
        let controller = OrbPanelController(
            state: orbState,
            settings: settings,
            content: AnyView(shell)
        )
        orb = controller
        controller.show()
    }
}

/// Injects the shared services into any SwiftUI subtree (Settings window, menu bar).
extension View {
    func companionEnvironment(_ core: CompanionCore) -> some View {
        environmentObject(core.settings)
            .environmentObject(core.pairing)
            .environmentObject(core.tmuxSetup)
            .environmentObject(core.keyboardRelay)
            .environmentObject(core.windowMirror)
    }
}
