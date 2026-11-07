import AppKit
import SwiftUI

/// Orb behaviour + the escape hatches: the full Settings window and Quit.
struct OrbSettingsPanel: View {
    @EnvironmentObject private var settings: CompanionSettings
    let onClose: () -> Void
    let onOpenSettingsWindow: () -> Void

    var body: some View {
        OrbPanelShell(
            title: "Settings",
            subtitle: "How the orb behaves on your desktop.",
            sectionIndex: "04",
            onClose: onClose
        ) {
            VStack(alignment: .leading, spacing: 14) {
                OrbInsetPanel {
                    Toggle("LAUNCH AT LOGIN", isOn: $settings.launchAtLogin)
                        .toggleStyle(.checkbox)
                        .controlSize(.small)
                        .font(.system(size: 9, weight: .semibold, design: .monospaced))
                        .tracking(0.6)
                        .foregroundStyle(GhosttyTheme.textSecondary)
                        .tint(GhosttyTheme.neonCyan)
                }

                if let error = settings.lastLoginItemError {
                    OrbStatusRow(label: error, symbol: "exclamationmark.triangle", tint: GhosttyTheme.neonAmber)
                }

                OrbInsetPanel {
                    VStack(alignment: .leading, spacing: 7) {
                        HStack {
                            Text("ORB OPACITY")
                                .font(.system(size: 9, weight: .semibold, design: .monospaced))
                                .tracking(0.6)
                                .foregroundStyle(GhosttyTheme.textSecondary)
                            Spacer()
                            Text("\(Int(settings.orbOpacity * 100))%")
                                .font(.system(size: 10, design: .monospaced))
                                .foregroundStyle(GhosttyTheme.neonCyan)
                        }
                        Slider(value: $settings.orbOpacity, in: 0.35...1)
                            .controlSize(.mini)
                            .tint(GhosttyTheme.neonCyan)
                    }
                }

                OrbPanelText(text: "Drag the orb itself to move it. It stays above other windows on every Space.")

                Divider().overlay(GhosttyTheme.panelStrokeSubtle)

                OrbActionButton(title: "Open Settings window", symbol: "slider.horizontal.3", prominent: false) {
                    onOpenSettingsWindow()
                }
                OrbActionButton(
                    title: "Quit Orb XR",
                    symbol: "power",
                    tint: GhosttyTheme.neonAmber,
                    prominent: false
                ) {
                    NSApp.terminate(nil)
                }
            }
        }
    }
}
