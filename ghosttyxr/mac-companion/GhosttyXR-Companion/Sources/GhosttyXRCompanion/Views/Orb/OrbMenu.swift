import SwiftUI

/// Four precision dials fanned on an arc above the orb with a short mechanical stagger.
struct OrbMenu: View {
    @EnvironmentObject private var state: OrbShellState
    @EnvironmentObject private var keyboardRelay: MacKeyboardRelayController
    @EnvironmentObject private var windowMirror: MacWindowMirrorController

    var body: some View {
        ZStack {
            ForEach(Array(OrbMenuItem.allCases.enumerated()), id: \.element.id) { index, item in
                let offset = OrbShellMetrics.menuOffset(degrees: item.angle)
                OrbMenuButton(
                    item: item,
                    label: label(for: item),
                    isActive: state.activePanel == item,
                    isLive: isLive(item),
                    action: { state.select(item) }
                )
                .offset(
                    x: state.menuOpen ? offset.x : 0,
                    y: state.menuOpen ? offset.y : 0
                )
                .scaleEffect(state.menuOpen ? 1 : 0.8)
                .opacity(state.menuOpen ? 1 : 0)
                .animation(
                    .easeOut(duration: 0.16)
                        .delay(state.menuOpen ? Double(index) * 0.035 : 0),
                    value: state.menuOpen
                )
            }
        }
        .allowsHitTesting(state.menuOpen)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Orb actions")
    }

    /// Live state changes the verb: the mirror button becomes "Stop mirror" while
    /// streaming, so one click always does the obvious thing.
    private func label(for item: OrbMenuItem) -> String {
        switch item {
        case .mirror where windowMirror.state.windowTitle != nil:
            return "Stop mirror"
        case .keyboard where keyboardRelay.captureArmed:
            return "Stop keyboard"
        default:
            return item.label
        }
    }

    private func isLive(_ item: OrbMenuItem) -> Bool {
        switch item {
        case .keyboard: return keyboardRelay.captureArmed
        case .mirror: return windowMirror.state.windowTitle != nil
        case .qr, .settings: return false
        }
    }
}

private struct OrbMenuButton: View {
    let item: OrbMenuItem
    let label: String
    let isActive: Bool
    let isLive: Bool
    let action: () -> Void

    @State private var isHovering = false

    var body: some View {
        Button(action: action) {
            Image(systemName: item.symbol)
                .font(.system(size: 18, weight: .medium))
                .foregroundStyle(
                    isActive
                        ? GhosttyTheme.neonMagenta
                        : (isHovering ? GhosttyTheme.neonCyan : GhosttyTheme.textSecondary)
                )
                .frame(width: OrbShellMetrics.menuItemSize, height: OrbShellMetrics.menuItemSize)
                .background(
                    Circle().fill(
                        isActive
                            ? GhosttyTheme.neonMagenta.opacity(0.16)
                            : GhosttyTheme.jet
                    )
                )
                .overlay(
                    Circle().strokeBorder(
                        isActive
                            ? GhosttyTheme.neonMagenta.opacity(0.85)
                            : (isHovering ? GhosttyTheme.neonCyan.opacity(0.65) : GhosttyTheme.panelStrokeStrong),
                        lineWidth: 1
                    )
                )
                .overlay(
                    Circle()
                        .inset(by: 4)
                        .strokeBorder(GhosttyTheme.panelStrokeSubtle, lineWidth: 1)
                )
                .overlay(alignment: .topTrailing) {
                    if isLive {
                        Rectangle()
                            .fill(GhosttyTheme.neonGreen)
                            .frame(width: 8, height: 8)
                            .overlay(Rectangle().strokeBorder(GhosttyTheme.jet, lineWidth: 2))
                            .offset(x: 1, y: -1)
                    }
                }
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .onHover { isHovering = $0 }
        .overlay(alignment: .top) {
            if isHovering {
                Text(label.uppercased())
                    .font(.system(size: 9, weight: .semibold, design: .monospaced))
                    .tracking(0.7)
                    .foregroundStyle(GhosttyTheme.textSecondary)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(GhosttyTheme.jet, in: RoundedRectangle(cornerRadius: GhosttyTheme.corner))
                    .overlay(
                        RoundedRectangle(cornerRadius: GhosttyTheme.corner)
                            .strokeBorder(GhosttyTheme.panelStroke, lineWidth: 1)
                    )
                    .fixedSize()
                    .offset(y: OrbShellMetrics.menuItemSize + 8)
                    .transition(.opacity)
                    .allowsHitTesting(false)
            }
        }
        .animation(.easeOut(duration: 0.14), value: isHovering)
        .accessibilityLabel(label)
    }
}
