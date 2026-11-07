import SwiftUI

/// Starts/stops the window or full-display mirror. Fronts `MacWindowMirrorController`;
/// window picking still goes through the existing click-to-select overlay.
struct OrbMirrorPanel: View {
    @EnvironmentObject private var windowMirror: MacWindowMirrorController
    let onClose: () -> Void

    var body: some View {
        OrbPanelShell(
            title: "Mirror to headset",
            subtitle: "Stream one Mac window, or the whole display with Symbiosis.",
            sectionIndex: "03",
            onClose: onClose
        ) {
            VStack(alignment: .leading, spacing: 12) {
                OrbStatusRow(
                    label: windowMirror.statusLabel,
                    symbol: windowMirror.statusSymbol,
                    tint: windowMirror.statusTint,
                    pulsing: windowMirror.state.status == .streaming
                )
                OrbPanelText(text: windowMirror.state.detail)

                if isSharing {
                    OrbActionButton(title: "Stop mirror", symbol: "xmark", tint: GhosttyTheme.neonAmber) {
                        windowMirror.stopMirror()
                    }
                } else {
                    OrbActionButton(title: "Pick a window", symbol: "rectangle.on.rectangle") {
                        windowMirror.bringWindowIntoXR()
                    }
                    OrbActionButton(
                        title: "Share full screen",
                        symbol: "display",
                        tint: GhosttyTheme.neonGreen
                    ) {
                        windowMirror.shareFullScreen()
                    }
                }

                if needsScreenRecording {
                    OrbActionButton(
                        title: "Open Screen Recording Settings",
                        symbol: "gearshape",
                        tint: GhosttyTheme.neonAmber,
                        prominent: false
                    ) {
                        windowMirror.openScreenRecordingSettings()
                    }
                }

                if needsAccessibility {
                    OrbActionButton(
                        title: "Allow remote control",
                        symbol: "gearshape",
                        tint: GhosttyTheme.neonAmber,
                        prominent: false
                    ) {
                        windowMirror.openAccessibilitySettings()
                    }
                }

                if isSymbiosisActive {
                    Divider().overlay(GhosttyTheme.panelStrokeSubtle)
                    OrbStatusRow(
                        label: windowMirror.state.pointerOwner == .xr
                            ? "Mouse + keyboard: in XR"
                            : "Mouse + keyboard: on Mac",
                        symbol: windowMirror.state.pointerOwner == .xr ? "visionpro" : "cursorarrow",
                        tint: windowMirror.state.pointerOwner == .xr
                            ? GhosttyTheme.neonGreen
                            : GhosttyTheme.dimText
                    )
                    OrbHintRow(chord: "⌃⌥⌘M", detail: "hand the mouse to XR and back")
                }

                if windowMirror.state.status == .streaming {
                    Divider().overlay(GhosttyTheme.panelStrokeSubtle)
                    stats
                }
            }
        }
    }

    private var stats: some View {
        let streamStats = windowMirror.streamStats
        return OrbInsetPanel {
            VStack(alignment: .leading, spacing: 5) {
                OrbSectionLabel(index: "TX", title: "Live telemetry", tint: GhosttyTheme.neonGreen)
                Text("\(windowMirror.activeBitrateMbps, specifier: "%.1f") / \(Int(windowMirror.bitrateMbps)) MBPS")
                    .font(.system(size: 10, weight: .medium, design: .monospaced))
                    .foregroundStyle(GhosttyTheme.textSecondary)
                Text(
                    "LAT \(Int(streamStats.endToEndMs))MS  /  " +
                        "DEC \(Int(streamStats.decodedFps))FPS" +
                        (streamStats.keyFrameRequests > 0
                            ? "  /  \(streamStats.keyFrameRequests) RESYNCS"
                            : "")
                )
                    .font(.system(size: 9, design: .monospaced))
                    .foregroundStyle(GhosttyTheme.dimText)
            }
        }
    }

    private var isSharing: Bool {
        windowMirror.state.windowTitle != nil
    }

    private var needsScreenRecording: Bool {
        windowMirror.state.status == .screenRecordingNeeded
    }

    private var needsAccessibility: Bool {
        isSharing && !windowMirror.state.interactive
    }

    private var isSymbiosisActive: Bool {
        windowMirror.state.isDisplayShare && windowMirror.state.status == .streaming
    }
}
