import SwiftUI

@main
struct GhosttyXRCompanionApp: App {
    @NSApplicationDelegateAdaptor(CompanionAppDelegate.self) private var appDelegate
    @Environment(\.openSettings) private var openSettings

    var body: some Scene {
        // The orb is the app's surface. It lives in a borderless, non-activating
        // `NSPanel` owned by `CompanionCore` rather than a SwiftUI scene — clicking it
        // must never pull focus off the user's terminal. The menu bar is the safety net
        // that can always summon it back.
        MenuBarExtra("Orb XR", systemImage: "circle.circle.fill") {
            MenuBarContent(
                orb: appDelegate.core.orb,
                openSettings: { openSettings() }
            )
            .companionEnvironment(appDelegate.core)
        }

        // Standard macOS Settings scene (⌘, / app menu ▸ Settings…) — the full-size
        // surface for the power-user detail the orb panels deliberately keep out of view.
        Settings {
            SettingsView()
                .companionEnvironment(appDelegate.core)
        }
    }
}

/// Menu-bar fallback: the orb can end up on a display that was unplugged, or be hidden
/// outright, so every entry point back into the app lives here too.
private struct MenuBarContent: View {
    @EnvironmentObject private var pairing: PairingController
    @EnvironmentObject private var keyboardRelay: MacKeyboardRelayController
    @EnvironmentObject private var windowMirror: MacWindowMirrorController

    let orb: OrbPanelController?
    let openSettings: () -> Void

    var body: some View {
        Text(remoteLoginLabel)
        Text(keyboardRelay.statusLabel)
        Text(windowMirror.statusLabel)

        Divider()

        Button("Show Orb") { orb?.show() }
        Button("Hide Orb") { orb?.hide() }
        Button("Reset Orb Position") { orb?.resetPosition() }

        Divider()

        Button("Settings…", action: openSettings)
            .keyboardShortcut(",", modifiers: .command)
        Button("Quit Orb XR") { NSApp.terminate(nil) }
            .keyboardShortcut("q", modifiers: .command)
    }

    private var remoteLoginLabel: String {
        switch pairing.remoteLogin {
        case .on: return "Remote Login: on"
        case .off: return "Remote Login: off"
        case .unknown: return "Remote Login: unknown"
        }
    }
}

final class CompanionAppDelegate: NSObject, NSApplicationDelegate {
    /// Created eagerly: the relay/handoff listeners must be up before the headset tries
    /// to connect, regardless of which windows exist.
    @MainActor let core = CompanionCore()

    func applicationDidFinishLaunching(_ notification: Notification) {
        MainActor.assumeIsolated {
            // Dark-only product chrome — match the marketing site, ignore system light mode.
            NSApp.appearance = NSAppearance(named: .darkAqua)
            core.startOrb(openSettingsWindow: Self.openSettingsWindow)
        }
    }

    /// Clicking the Dock icon must bring the orb back — it is the app's only surface.
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        MainActor.assumeIsolated {
            core.orb?.show()
        }
        return true
    }

    /// Closing the Settings window must NOT quit: the keyboard relay server,
    /// terminal-handoff listener, and any active window mirror are app-lifetime services
    /// that keep serving the headset even with no window on screen.
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        false
    }

    /// The orb panel is plain AppKit, so it can't reach SwiftUI's `openSettings`
    /// environment value.
    ///
    /// The usual `showSettingsWindow:` trick is not reliable: on macOS 26+ `sendAction`
    /// returns `true` and then nothing opens. What does work is firing the app menu's own
    /// "Settings…" item, which SwiftUI wires to an internal target. Fall back to the
    /// selectors if that item ever stops being findable.
    @MainActor
    private static func openSettingsWindow() {
        NSApp.activate(ignoringOtherApps: true)

        // Identify the item by its ⌘, shortcut rather than its title, which is localized.
        if let appMenu = NSApp.mainMenu?.items.first?.submenu,
           let item = appMenu.items.first(where: {
               $0.keyEquivalent == "," && $0.keyEquivalentModifierMask.contains(.command)
           }),
           let action = item.action,
           NSApp.sendAction(action, to: item.target, from: item) {
            return
        }

        for name in ["showSettingsWindow:", "showPreferencesWindow:"]
        where NSApp.sendAction(Selector((name)), to: nil, from: nil) {
            return
        }
    }
}
