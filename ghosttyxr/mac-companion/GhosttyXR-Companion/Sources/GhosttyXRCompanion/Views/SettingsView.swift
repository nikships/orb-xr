import AppKit
import SwiftUI

/// The conventional Settings scene, styled as the companion's technical control plane.
struct SettingsView: View {
    @State private var selection = SettingsTab.general

    var body: some View {
        VStack(spacing: 0) {
            header
            Rectangle()
                .fill(GhosttyTheme.panelStroke)
                .frame(height: 1)
            HStack(spacing: 0) {
                navigation
                Rectangle()
                    .fill(GhosttyTheme.panelStrokeSubtle)
                    .frame(width: 1)
                selectedScreen
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            }
        }
        .frame(width: 580, height: 500)
        .background(GhosttyTheme.jet)
        .preferredColorScheme(.dark)
        .background(SettingsAppearanceAnchor())
    }

    private var header: some View {
        ZStack {
            HStack {
                OrbSectionLabel(index: "00", title: "Companion control")
                Spacer()
                Text("MAC / ONLINE")
                    .font(.system(size: 9, weight: .semibold, design: .monospaced))
                    .tracking(0.8)
                    .foregroundStyle(GhosttyTheme.neonGreen)
            }
            Text("ORB / XR")
                .font(.system(size: 12, weight: .semibold, design: .monospaced))
                .tracking(1.8)
                .foregroundStyle(GhosttyTheme.textPrimary)
        }
        .padding(.horizontal, 20)
        .frame(height: 48)
    }

    private var navigation: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("SYSTEM")
                .font(.system(size: 8, weight: .semibold, design: .monospaced))
                .tracking(1.2)
                .foregroundStyle(GhosttyTheme.dimText)
                .padding(.horizontal, 10)
                .padding(.bottom, 6)

            ForEach(SettingsTab.allCases) { tab in
                SettingsNavigationButton(
                    tab: tab,
                    isSelected: selection == tab,
                    action: { selection = tab }
                )
            }
            Spacer()
            Text("DEV.GHOSTTYXR.COMPANION")
                .font(.system(size: 7, weight: .medium, design: .monospaced))
                .tracking(0.5)
                .foregroundStyle(GhosttyTheme.dimText.opacity(0.65))
                .fixedSize()
                .rotationEffect(.degrees(-90))
                .frame(width: 114, height: 118)
        }
        .padding(12)
        .frame(width: 142)
        .frame(maxHeight: .infinity, alignment: .topLeading)
        .background(GhosttyTheme.insetSurface)
    }

    @ViewBuilder
    private var selectedScreen: some View {
        switch selection {
        case .general: GeneralSettingsTab()
        case .pairing: PairingSettingsTab()
        case .mirror: MirrorSettingsTab()
        }
    }
}

private enum SettingsTab: String, CaseIterable, Identifiable {
    case general
    case pairing
    case mirror

    var id: String { rawValue }

    var index: String {
        switch self {
        case .general: return "01"
        case .pairing: return "02"
        case .mirror: return "03"
        }
    }

    var symbol: String {
        switch self {
        case .general: return "gearshape"
        case .pairing: return "qrcode"
        case .mirror: return "rectangle.on.rectangle"
        }
    }
}

private struct SettingsNavigationButton: View {
    let tab: SettingsTab
    let isSelected: Bool
    let action: () -> Void

    @State private var isHovering = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Text(tab.index)
                    .foregroundStyle(isSelected ? GhosttyTheme.neonCyan : GhosttyTheme.dimText)
                Image(systemName: tab.symbol)
                    .font(.system(size: 10, weight: .medium))
                Text(tab.rawValue.uppercased())
                Spacer(minLength: 0)
            }
            .font(.system(size: 9, weight: .semibold, design: .monospaced))
            .tracking(0.6)
            .foregroundStyle(
                isSelected
                    ? GhosttyTheme.textPrimary
                    : (isHovering ? GhosttyTheme.neonCyan : GhosttyTheme.textSecondary)
            )
            .padding(.horizontal, 9)
            .frame(height: 34)
            .background(isSelected ? GhosttyTheme.glassFillStrong : Color.clear)
            .overlay(alignment: .leading) {
                Rectangle()
                    .fill(isSelected ? GhosttyTheme.neonCyan : Color.clear)
                    .frame(width: 2)
            }
            .overlay(
                Rectangle()
                    .strokeBorder(
                        isSelected
                            ? GhosttyTheme.panelStrokeStrong
                            : (isHovering ? GhosttyTheme.panelStroke : Color.clear),
                        lineWidth: 1
                    )
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { isHovering = $0 }
        .animation(.easeOut(duration: 0.14), value: isHovering)
    }
}

/// Pins the Settings window to Orb XR's dark, near-borderless window chrome.
private struct SettingsAppearanceAnchor: NSViewRepresentable {
    func makeNSView(context: Context) -> NSView {
        let view = NSView()
        DispatchQueue.main.async {
            guard let window = view.window else { return }
            applyAppearance(to: window)
        }
        return view
    }

    func updateNSView(_ nsView: NSView, context: Context) {
        guard let window = nsView.window else { return }
        applyAppearance(to: window)
    }

    private func applyAppearance(to window: NSWindow) {
        window.appearance = NSAppearance(named: .darkAqua)
        window.backgroundColor = GhosttyTheme.nsJet
        window.titleVisibility = .hidden
        window.titlebarAppearsTransparent = true
        window.isMovableByWindowBackground = true
    }
}

private struct SettingsScreen<Content: View>: View {
    let index: String
    let title: String
    let detail: String
    @ViewBuilder var content: () -> Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                VStack(alignment: .leading, spacing: 7) {
                    OrbSectionLabel(index: index, title: title)
                    Text(title.uppercased())
                        .font(.system(size: 18, weight: .semibold, design: .monospaced))
                        .tracking(0.8)
                        .foregroundStyle(GhosttyTheme.textPrimary)
                    Text(detail)
                        .font(.system(size: 11))
                        .foregroundStyle(GhosttyTheme.dimText)
                }
                content()
            }
            .padding(24)
        }
    }
}

private struct SettingsSection<Content: View>: View {
    let index: String
    let title: String
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            OrbSectionLabel(index: index, title: title, tint: GhosttyTheme.neonMagenta)
            OrbInsetPanel(padding: 12, content: content)
        }
    }
}

private struct PairingSettingsTab: View {
    @EnvironmentObject private var pairing: PairingController
    @State private var didCopyURL = false

    var body: some View {
        SettingsScreen(
            index: "02",
            title: "Pairing",
            detail: "Connection coordinates and temporary trust material for the headset."
        ) {
            if let conn = pairing.connection {
                let urlString = conn.pairingURL().absoluteString
                SettingsSection(index: "01", title: "Pairing URL") {
                    VStack(alignment: .leading, spacing: 10) {
                        Text(urlString)
                            .font(.system(size: 10, design: .monospaced))
                            .foregroundStyle(GhosttyTheme.textSecondary)
                            .textSelection(.enabled)
                            .lineLimit(3)
                            .truncationMode(.middle)
                        OrbActionButton(
                            title: didCopyURL ? "Copied" : "Copy URL",
                            symbol: didCopyURL ? "checkmark" : "doc.on.doc",
                            tint: didCopyURL ? GhosttyTheme.neonGreen : GhosttyTheme.neonCyan,
                            prominent: false,
                            action: copyURL
                        )
                    }
                }

                SettingsSection(index: "02", title: "Host fingerprint") {
                    if let fingerprint = conn.hostFingerprint {
                        Text(fingerprint)
                            .font(.system(size: 10, design: .monospaced))
                            .foregroundStyle(GhosttyTheme.textSecondary)
                            .textSelection(.enabled)
                    } else {
                        Label(
                            "Unavailable. SSHD may not have generated host keys.",
                            systemImage: "exclamationmark.bubble"
                        )
                        .font(.system(size: 10, design: .monospaced))
                        .foregroundStyle(GhosttyTheme.neonAmber)
                    }
                }
            } else if let error = pairing.lastError {
                OrbStatusRow(
                    label: error,
                    symbol: "exclamationmark.triangle",
                    tint: GhosttyTheme.neonAmber
                )
            }

            SettingsSection(index: "03", title: "Development credential") {
                VStack(alignment: .leading, spacing: 8) {
                    SecureField("Mac login password", text: $pairing.sshPassword)
                        .textFieldStyle(OrbTextFieldStyle())
                    Text("MEMORY ONLY  /  QR TOKEN OVERRIDES THIS VALUE")
                        .font(.system(size: 8, weight: .medium, design: .monospaced))
                        .tracking(0.5)
                        .foregroundStyle(GhosttyTheme.dimText)
                }
            }
        }
    }

    private func copyURL() {
        guard let urlString = pairing.connection?.pairingURL().absoluteString else { return }
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.setString(urlString, forType: .string)
        didCopyURL = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { didCopyURL = false }
    }
}

private struct GeneralSettingsTab: View {
    @EnvironmentObject private var settings: CompanionSettings

    var body: some View {
        SettingsScreen(
            index: "01",
            title: "General",
            detail: "Control startup behavior and the floating orb's desktop presence."
        ) {
            SettingsSection(index: "01", title: "Startup") {
                VStack(alignment: .leading, spacing: 9) {
                    Toggle("LAUNCH ORB XR AT LOGIN", isOn: $settings.launchAtLogin)
                        .toggleStyle(.checkbox)
                        .font(.system(size: 10, weight: .semibold, design: .monospaced))
                        .tracking(0.6)
                        .tint(GhosttyTheme.neonCyan)
                    if let error = settings.lastLoginItemError {
                        Text(error)
                            .font(.system(size: 9, design: .monospaced))
                            .foregroundStyle(GhosttyTheme.neonAmber)
                    }
                }
            }

            SettingsSection(index: "02", title: "Orb presence") {
                VStack(alignment: .leading, spacing: 10) {
                    HStack {
                        Text("OPACITY")
                        Spacer()
                        Text("\(Int(settings.orbOpacity * 100))%")
                            .foregroundStyle(GhosttyTheme.neonCyan)
                    }
                    .font(.system(size: 10, weight: .semibold, design: .monospaced))
                    .tracking(0.5)
                    Slider(value: $settings.orbOpacity, in: 0.35...1)
                        .tint(GhosttyTheme.neonCyan)
                    Text("The orb remains above every Space. Drag it to move it; use the menu bar to hide it or reset its position.")
                        .font(.system(size: 10))
                        .foregroundStyle(GhosttyTheme.dimText)
                }
            }
        }
    }
}

private struct MirrorSettingsTab: View {
    @EnvironmentObject private var windowMirror: MacWindowMirrorController

    var body: some View {
        SettingsScreen(
            index: "03",
            title: "Mirror",
            detail: "Tune the direct local video stream without changing the SSH control plane."
        ) {
            SettingsSection(index: "01", title: "Transport ceiling") {
                VStack(alignment: .leading, spacing: 10) {
                    HStack {
                        Text("MAX BITRATE")
                        Spacer()
                        Text("\(Int(windowMirror.bitrateMbps)) MBPS")
                            .foregroundStyle(GhosttyTheme.neonCyan)
                    }
                    .font(.system(size: 10, weight: .semibold, design: .monospaced))
                    .tracking(0.5)
                    Slider(value: $windowMirror.bitrateMbps, in: 8...100, step: 1)
                        .tint(GhosttyTheme.neonCyan)
                    Text("The encoder applies this limit live and backs off when headset latency rises.")
                        .font(.system(size: 10))
                        .foregroundStyle(GhosttyTheme.dimText)
                }
            }

            SettingsSection(index: "02", title: "Codec") {
                VStack(alignment: .leading, spacing: 8) {
                    Picker("VIDEO CODEC", selection: $windowMirror.codec) {
                        ForEach(WindowMirrorVideoCodec.allCases, id: \.self) { codec in
                            Text(codec.displayName).tag(codec)
                        }
                    }
                    .font(.system(size: 10, weight: .semibold, design: .monospaced))
                    Text("HEVC uses fewer bits. H.264 decodes fastest and remains the compatibility fallback.")
                        .font(.system(size: 10))
                        .foregroundStyle(GhosttyTheme.dimText)
                }
            }

            SettingsSection(index: "03", title: "Local network") {
                VStack(alignment: .leading, spacing: 8) {
                    Toggle("ENCRYPT VIDEO FRAMES", isOn: $windowMirror.encryptMedia)
                        .toggleStyle(.checkbox)
                        .font(.system(size: 10, weight: .semibold, design: .monospaced))
                        .tracking(0.6)
                        .tint(GhosttyTheme.neonCyan)
                    Text("Input and control remain inside the SSH tunnel regardless of this setting.")
                        .font(.system(size: 10))
                        .foregroundStyle(GhosttyTheme.dimText)
                }
            }
        }
    }
}
