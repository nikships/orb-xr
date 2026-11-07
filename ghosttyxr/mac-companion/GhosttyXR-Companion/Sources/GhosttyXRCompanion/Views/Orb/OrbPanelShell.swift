import SwiftUI

/// The flat technical card every orb panel lives in, anchored under the orb.
struct OrbPanelShell<Content: View>: View {
    let title: String
    let subtitle: String
    var sectionIndex: String = "01"
    var onClose: (() -> Void)?
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
            Rectangle()
                .fill(GhosttyTheme.panelStrokeSubtle)
                .frame(height: 1)
            content()
                .padding(.horizontal, 16)
                .padding(.vertical, 14)
        }
        .frame(width: OrbShellMetrics.panelWidth, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: GhosttyTheme.corner)
                .fill(GhosttyTheme.jet)
        )
        .overlay(
            RoundedRectangle(cornerRadius: GhosttyTheme.corner)
                .strokeBorder(GhosttyTheme.panelStroke, lineWidth: 1)
        )
        .accessibilityElement(children: .contain)
        .accessibilityLabel(title)
    }

    private var header: some View {
        HStack(alignment: .top, spacing: 10) {
            VStack(alignment: .leading, spacing: 5) {
                OrbSectionLabel(index: sectionIndex, title: title)
                Text(title.uppercased())
                    .font(.system(size: 12, weight: .semibold, design: .monospaced))
                    .tracking(0.7)
                    .foregroundStyle(GhosttyTheme.textPrimary)
                Text(subtitle)
                    .font(.system(size: 11))
                    .foregroundStyle(GhosttyTheme.dimText)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 4)
            if let onClose {
                Button(action: onClose) {
                    Image(systemName: "xmark")
                        .font(.system(size: 9, weight: .bold))
                        .foregroundStyle(GhosttyTheme.dimText)
                        .frame(width: 24, height: 24)
                        .background(GhosttyTheme.insetSurface)
                        .overlay(
                            Rectangle()
                                .strokeBorder(GhosttyTheme.panelStroke, lineWidth: 1)
                        )
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Close \(title)")
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }
}

/// Full-width industrial action button. Tinted variants carry meaning:
/// cyan = go, amber = needs attention, green = live.
struct OrbActionButton: View {
    let title: String
    let symbol: String
    var tint: Color = GhosttyTheme.neonCyan
    var prominent: Bool = true
    let action: () -> Void

    @State private var isHovering = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 7) {
                Image(systemName: symbol)
                    .font(.system(size: 10, weight: .semibold))
                Text(title.uppercased())
                    .font(.system(size: 10, weight: .semibold, design: .monospaced))
                    .tracking(0.7)
                Spacer(minLength: 6)
                if prominent {
                    Text("→")
                        .font(.system(size: 11, weight: .bold, design: .monospaced))
                }
            }
            .foregroundStyle(
                prominent
                    ? GhosttyTheme.jet
                    : (isHovering ? tint : GhosttyTheme.textSecondary)
            )
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .background(
                RoundedRectangle(cornerRadius: GhosttyTheme.corner)
                    .fill(
                        prominent
                            ? tint.opacity(isHovering ? 1 : 0.88)
                            : Color.white.opacity(isHovering ? 0.07 : 0.03)
                    )
            )
            .overlay(
                RoundedRectangle(cornerRadius: GhosttyTheme.corner)
                    .strokeBorder(
                        prominent
                            ? tint
                            : (isHovering ? tint.opacity(0.65) : GhosttyTheme.panelStroke),
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

/// One-line status readout with the controller's own symbol/tint, so panel state always
/// matches what the rest of the app reports.
struct OrbStatusRow: View {
    let label: String
    let symbol: String
    let tint: Color
    var pulsing: Bool = false

    @State private var dim = false

    var body: some View {
        HStack(spacing: 7) {
            Text("STATUS")
                .font(.system(size: 9, weight: .semibold, design: .monospaced))
                .tracking(0.9)
                .foregroundStyle(GhosttyTheme.dimText)
            Spacer(minLength: 8)
            Image(systemName: symbol)
                .font(.system(size: 10, weight: .semibold))
                .foregroundStyle(tint)
            Text(label)
                .font(.system(size: 10, weight: .medium, design: .monospaced))
                .foregroundStyle(tint)
                .multilineTextAlignment(.trailing)
                .lineLimit(2)
            if pulsing {
                Rectangle()
                    .fill(GhosttyTheme.neonGreen)
                    .frame(width: 6, height: 6)
                    .opacity(dim ? 0.35 : 1)
                    .onAppear {
                        withAnimation(.easeOut(duration: 0.8).repeatForever(autoreverses: true)) {
                            dim = true
                        }
                    }
            }
        }
        .padding(.horizontal, 9)
        .padding(.vertical, 8)
        .background(GhosttyTheme.insetSurface)
        .overlay(
            RoundedRectangle(cornerRadius: GhosttyTheme.insetCorner)
                .strokeBorder(GhosttyTheme.panelStrokeSubtle, lineWidth: 1)
        )
    }
}

/// Body copy inside a panel — small, dim, wraps.
struct OrbPanelText: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.system(size: 11))
            .foregroundStyle(GhosttyTheme.dimText)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Keyboard-chord hint chip (⌃⌥⌘K and friends).
struct OrbHintRow: View {
    let chord: String
    let detail: String

    var body: some View {
        HStack(spacing: 6) {
            Text(chord)
                .font(.system(size: 10, weight: .semibold, design: .monospaced))
                .foregroundStyle(GhosttyTheme.neonMagenta)
                .padding(.horizontal, 5)
                .padding(.vertical, 2)
                .background(
                    RoundedRectangle(cornerRadius: GhosttyTheme.insetCorner)
                        .fill(GhosttyTheme.insetSurface)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: GhosttyTheme.insetCorner)
                        .strokeBorder(GhosttyTheme.panelStroke, lineWidth: 1)
                )
            Text(detail.uppercased())
                .font(.system(size: 9, weight: .medium, design: .monospaced))
                .tracking(0.45)
                .foregroundStyle(GhosttyTheme.dimText)
            Spacer(minLength: 0)
        }
    }
}
