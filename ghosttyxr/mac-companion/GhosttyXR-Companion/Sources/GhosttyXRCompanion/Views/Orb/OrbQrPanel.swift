import AppKit
import SwiftUI

/// Pairing QR with a live token countdown. Fronts `PairingController` — the token
/// rotates on its own every ~30s and the countdown just visualises that clock.
struct OrbQrPanel: View {
    @EnvironmentObject private var pairing: PairingController
    let onClose: () -> Void

    @State private var now = Date()
    @State private var didCopy = false

    /// Drives the countdown only; token rotation itself lives in PairingController.
    private let ticker = Timer.publish(every: 0.5, on: .main, in: .common).autoconnect()

    var body: some View {
        OrbPanelShell(
            title: "Pair the headset",
            subtitle: "Look at this code on the headset's Connect your Mac screen.",
            sectionIndex: "01",
            onClose: onClose
        ) {
            VStack(alignment: .leading, spacing: 12) {
                if pairing.remoteLogin != .on {
                    remoteLoginWarning
                }
                qr
                if let connection = pairing.connection {
                    endpoint(connection)
                    countdown(connection)
                }
                actions
            }
        }
        .onReceive(ticker) { now = $0 }
    }

    @ViewBuilder
    private var qr: some View {
        if let urlString = pairing.connection?.pairingURL().absoluteString {
            ZStack {
                RoundedRectangle(cornerRadius: GhosttyTheme.corner)
                    .fill(.white)
                QRImageView(payload: urlString)
                    .padding(10)
            }
            .frame(width: 268, height: 268)
            .overlay(
                RoundedRectangle(cornerRadius: GhosttyTheme.corner)
                    .strokeBorder(GhosttyTheme.neonCyan.opacity(0.35), lineWidth: 1)
            )
            .contentShape(Rectangle())
            .onTapGesture {
                FullScreenQRController.shared.show(urlString: urlString)
            }
            .help("Click to show the QR full screen")
            .accessibilityLabel("Pairing QR code")
        } else {
            VStack(spacing: 8) {
                Image(systemName: "wifi.exclamationmark")
                    .font(.system(size: 28))
                    .foregroundStyle(GhosttyTheme.neonAmber)
                OrbPanelText(text: pairing.lastError ?? "No active local network was found.")
            }
            .frame(width: 268, height: 140)
            .background(
                RoundedRectangle(cornerRadius: GhosttyTheme.corner)
                    .fill(GhosttyTheme.insetSurface)
            )
        }
    }

    private var remoteLoginWarning: some View {
        VStack(alignment: .leading, spacing: 8) {
            OrbStatusRow(
                label: pairing.remoteLogin == .off ? "Remote Login is off" : "Remote Login unknown",
                symbol: "exclamationmark.shield",
                tint: GhosttyTheme.neonAmber
            )
            OrbPanelText(text: "The headset can't SSH in until Remote Login is on.")
            OrbActionButton(title: "Open Sharing Settings", symbol: "gearshape", tint: GhosttyTheme.neonAmber) {
                pairing.openSharingSettings()
            }
        }
    }

    private func endpoint(_ connection: ConnectionInfo) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text("\(connection.username)@\(connection.host):\(connection.port)")
                .font(.system(size: 11, design: .monospaced))
                .foregroundStyle(GhosttyTheme.textSecondary)
            // Never fabricate a fingerprint: say "unavailable" when sshd has no host key.
            Text(connection.hostFingerprint ?? "host fingerprint unavailable")
                .font(.system(size: 9, design: .monospaced))
                .foregroundStyle(GhosttyTheme.dimText)
                .lineLimit(1)
                .truncationMode(.middle)
        }
    }

    private func countdown(_ connection: ConnectionInfo) -> some View {
        let remaining = max(0, connection.token.expiresAt.timeIntervalSince(now))
        let fraction = connection.token.lifetime > 0
            ? remaining / connection.token.lifetime
            : 0
        // A pasted dev password has a 24h lifetime — a countdown bar would be noise.
        let isRotating = connection.token.lifetime <= 120

        return VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text((isRotating ? "Token rotates" : "Using dev SSH password").uppercased())
                    .font(.system(size: 9, weight: .medium, design: .monospaced))
                    .tracking(0.5)
                    .foregroundStyle(GhosttyTheme.dimText)
                Spacer()
                if isRotating {
                    Text("\(Int(remaining.rounded()))s")
                        .font(.system(size: 10, weight: .medium, design: .monospaced))
                        .foregroundStyle(remaining < 6 ? GhosttyTheme.neonAmber : GhosttyTheme.dimText)
                }
            }
            if isRotating {
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Rectangle().fill(Color.white.opacity(0.08))
                        Rectangle()
                            .fill(remaining < 6 ? GhosttyTheme.neonAmber : GhosttyTheme.neonCyan)
                            .frame(width: geo.size.width * fraction)
                    }
                }
                .frame(height: 3)
            }
        }
    }

    private var actions: some View {
        HStack(spacing: 8) {
            OrbActionButton(
                title: didCopy ? "Copied" : "Copy URL",
                symbol: didCopy ? "checkmark" : "doc.on.doc",
                tint: didCopy ? GhosttyTheme.neonGreen : GhosttyTheme.neonCyan,
                prominent: false
            ) {
                copyURL()
            }
            .disabled(pairing.connection == nil)

            OrbActionButton(title: "Refresh", symbol: "arrow.clockwise", prominent: false) {
                pairing.refreshInterfaces()
                pairing.refreshRemoteLogin()
                pairing.refreshHostFingerprint()
                pairing.rotateToken()
            }
        }
    }

    private func copyURL() {
        guard let urlString = pairing.connection?.pairingURL().absoluteString else { return }
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.setString(urlString, forType: .string)
        didCopy = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { didCopy = false }
    }
}
