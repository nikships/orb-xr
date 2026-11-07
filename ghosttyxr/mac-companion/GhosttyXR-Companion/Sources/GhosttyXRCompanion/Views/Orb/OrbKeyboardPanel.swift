import SwiftUI

/// Arms/disarms host keyboard forwarding. Fronts `MacKeyboardRelayController`; all
/// status text and tints come from the controller so the orb never invents state.
struct OrbKeyboardPanel: View {
    @EnvironmentObject private var keyboardRelay: MacKeyboardRelayController
    let onClose: () -> Void

    var body: some View {
        OrbPanelShell(
            title: "Forward keyboard",
            subtitle: "Send this Mac's keystrokes to the focused headset terminal.",
            sectionIndex: "02",
            onClose: onClose
        ) {
            VStack(alignment: .leading, spacing: 12) {
                OrbStatusRow(
                    label: keyboardRelay.statusLabel,
                    symbol: keyboardRelay.statusSymbol,
                    tint: keyboardRelay.statusTint,
                    pulsing: isCapturing
                )
                OrbPanelText(text: keyboardRelay.state.detail)

                OrbActionButton(
                    title: keyboardRelay.captureArmed ? "Stop forwarding" : "Start forwarding",
                    symbol: keyboardRelay.captureArmed ? "keyboard.badge.eye.fill" : "keyboard",
                    tint: keyboardRelay.captureArmed ? GhosttyTheme.neonAmber : GhosttyTheme.neonCyan
                ) {
                    keyboardRelay.toggleArmed()
                }

                if needsPermission {
                    OrbActionButton(
                        title: "Open Accessibility Settings",
                        symbol: "gearshape",
                        tint: GhosttyTheme.neonAmber,
                        prominent: false
                    ) {
                        keyboardRelay.openInputMonitoringSettings()
                    }
                }

                Divider().overlay(GhosttyTheme.panelStrokeSubtle)

                OrbHintRow(chord: "⌃⌥⌘K", detail: "toggle forwarding from anywhere")
            }
        }
    }

    private var isCapturing: Bool {
        keyboardRelay.state.status == .capturing
    }

    private var needsPermission: Bool {
        keyboardRelay.state.status == .permissionNeeded
    }
}
