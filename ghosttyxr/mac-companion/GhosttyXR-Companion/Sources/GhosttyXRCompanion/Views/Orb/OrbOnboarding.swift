import AppKit
import SwiftUI

/// First flight, rendered in the orb's own panel instead of a setup window.
///
/// Same four steps and the same gates as before: the Mac must be genuinely ready, the
/// password must be confirmed, and completion only lands once the headset actually opens
/// the relay tunnel (`keyboardRelay.headsetConnected`) — never on "the QR was shown".
struct OrbOnboarding: View {
    @EnvironmentObject private var pairing: PairingController
    @EnvironmentObject private var tmuxSetup: TmuxSetupController
    @EnvironmentObject private var keyboardRelay: MacKeyboardRelayController

    @State private var step = CompanionOnboardingStep.welcome
    @State private var password = ""
    @State private var passwordConfirmation = ""
    @State private var isFinishing = false

    var body: some View {
        OrbPanelShell(
            title: title,
            subtitle: subtitle,
            sectionIndex: String(format: "%02d", step.rawValue + 1)
        ) {
            VStack(alignment: .leading, spacing: 12) {
                progress
                switch step {
                case .welcome: welcomeStep
                case .macReady: macReadyStep
                case .credentials: credentialsStep
                case .pair: pairStep
                }
            }
        }
        .animation(.easeOut(duration: 0.16), value: step)
        .onChange(of: keyboardRelay.headsetConnected) { _, connected in
            if connected { finish() }
        }
    }

    private var title: String {
        switch step {
        case .welcome: return "Connect your Mac"
        case .macReady: return "Get this Mac ready"
        case .credentials: return "Mac login password"
        case .pair: return isFinishing ? "Headset connected" : "Pair the headset"
        }
    }

    private var subtitle: String {
        switch step {
        case .welcome: return "Four quick steps, then the orb takes over."
        case .macReady: return "Orb XR needs Remote Login, a network, and tmux."
        case .credentials: return "Used once so the headset can install its SSH key."
        case .pair: return isFinishing
            ? "The secure tunnel is live. Finishing setup."
            : "Look at this code on the headset."
        }
    }

    private var progress: some View {
        HStack(spacing: 0) {
            ForEach(CompanionOnboardingStep.allCases, id: \.rawValue) { candidate in
                let isComplete = candidate.rawValue < step.rawValue
                let isCurrent = candidate == step
                VStack(alignment: .leading, spacing: 5) {
                    Text(String(format: "%02d", candidate.rawValue + 1))
                        .foregroundStyle(
                            isCurrent
                                ? GhosttyTheme.neonCyan
                                : (isComplete ? GhosttyTheme.neonGreen : GhosttyTheme.dimText)
                        )
                    Text(stepLabel(candidate))
                        .foregroundStyle(isCurrent ? GhosttyTheme.textPrimary : GhosttyTheme.dimText)
                        .lineLimit(1)
                    Rectangle()
                        .fill(
                            isCurrent
                                ? GhosttyTheme.neonCyan
                                : (isComplete ? GhosttyTheme.neonGreen.opacity(0.6) : GhosttyTheme.panelStroke)
                        )
                        .frame(height: 1)
                }
                .font(.system(size: 8, weight: .semibold, design: .monospaced))
                .tracking(0.5)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    private func stepLabel(_ candidate: CompanionOnboardingStep) -> String {
        switch candidate {
        case .welcome: return "START"
        case .macReady: return "READY"
        case .credentials: return "LOGIN"
        case .pair: return "PAIR"
        }
    }

    // MARK: Steps

    private var welcomeStep: some View {
        VStack(alignment: .leading, spacing: 12) {
            OrbPanelText(text: "We'll check this Mac, prepare secure sign-in, and wait until the headset is actually connected before finishing.")
            promise("checkmark.shield", "Every required setting is verified first.")
            promise("lock.shield", "Your password stays in memory only.")
            promise("qrcode.viewfinder", "One scan from the headset completes setup.")
            OrbActionButton(title: "Start setup", symbol: "chevron.right") {
                refreshReadiness()
                step = .macReady
            }
        }
    }

    private func promise(_ symbol: String, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: symbol)
                .font(.system(size: 11))
                .foregroundStyle(GhosttyTheme.neonCyan)
                .frame(width: 14)
            Text(text)
                .font(.system(size: 11))
                .foregroundStyle(GhosttyTheme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var macReadyStep: some View {
        VStack(alignment: .leading, spacing: 10) {
            readinessRow("Remote Login", remoteLoginDetail, ready: pairing.remoteLogin == .on)
            readinessRow("Local network", networkDetail, ready: pairing.connection != nil)
            readinessRow("tmux", tmuxDetail, ready: tmuxSetup.tmuxInstalled)

            tmuxInstallControls

            if pairing.remoteLogin != .on {
                OrbActionButton(title: "Open Sharing Settings", symbol: "gearshape", tint: GhosttyTheme.neonAmber) {
                    pairing.openSharingSettings()
                }
            }

            HStack(spacing: 8) {
                OrbActionButton(title: "Back", symbol: "chevron.left", prominent: false) {
                    step = .welcome
                }
                OrbActionButton(
                    title: "Continue",
                    symbol: "chevron.right",
                    tint: macReady ? GhosttyTheme.neonGreen : GhosttyTheme.dimText
                ) {
                    guard macReady else { return }
                    step = .credentials
                }
                .disabled(!macReady)
            }

            OrbActionButton(title: "Check again", symbol: "arrow.clockwise", prominent: false) {
                refreshReadiness()
            }
        }
    }

    @ViewBuilder
    private var tmuxInstallControls: some View {
        switch tmuxSetup.phase {
        case .checking, .installed:
            EmptyView()
        case .missing(let brew), .failed(_, let brew):
            if case .failed(let reason, _) = tmuxSetup.phase {
                OrbStatusRow(label: reason, symbol: "xmark.circle", tint: GhosttyTheme.neonAmber)
            }
            if case .installed = brew {
                OrbActionButton(title: "Install tmux", symbol: "arrow.down.circle", tint: GhosttyTheme.neonAmber) {
                    tmuxSetup.installTmux()
                }
            } else {
                OrbActionButton(title: "Install Homebrew", symbol: "terminal", tint: GhosttyTheme.neonAmber) {
                    tmuxSetup.openHomebrewInstaller()
                }
                OrbPanelText(text: "Opens Terminal with the official Homebrew installer. Come back and click Install tmux when it finishes.")
            }
        case .installing:
            HStack(spacing: 7) {
                ProgressView().controlSize(.small).tint(GhosttyTheme.neonCyan)
                Text("Installing tmux…")
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(GhosttyTheme.textSecondary)
            }
        }
    }

    private func readinessRow(_ title: String, _ detail: String, ready: Bool) -> some View {
        OrbInsetPanel(padding: 9) {
            HStack(alignment: .top, spacing: 8) {
                Rectangle()
                    .fill(ready ? GhosttyTheme.neonGreen : GhosttyTheme.neonAmber)
                    .frame(width: 6, height: 6)
                    .padding(.top, 3)
                VStack(alignment: .leading, spacing: 3) {
                    HStack {
                        Text(title.uppercased())
                            .font(.system(size: 9, weight: .semibold, design: .monospaced))
                            .tracking(0.6)
                            .foregroundStyle(GhosttyTheme.textPrimary)
                        Spacer()
                        Text(ready ? "READY" : "ACTION")
                            .font(.system(size: 8, weight: .semibold, design: .monospaced))
                            .foregroundStyle(ready ? GhosttyTheme.neonGreen : GhosttyTheme.neonAmber)
                    }
                    Text(detail)
                        .font(.system(size: 10))
                        .foregroundStyle(GhosttyTheme.dimText)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }

    private var credentialsStep: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 7) {
                Image(systemName: "person.crop.circle")
                    .font(.system(size: 11))
                    .foregroundStyle(GhosttyTheme.neonCyan)
                Text(pairing.connection?.username ?? NSUserName())
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(GhosttyTheme.textSecondary)
            }
            SecureField("Mac login password", text: $password)
                .textFieldStyle(OrbTextFieldStyle())
                .onSubmit(continueFromCredentials)
            SecureField("Enter it again", text: $passwordConfirmation)
                .textFieldStyle(OrbTextFieldStyle())
                .onSubmit(continueFromCredentials)

            credentialFeedback

            HStack(spacing: 8) {
                OrbActionButton(title: "Back", symbol: "chevron.left", prominent: false) {
                    step = .macReady
                }
                OrbActionButton(
                    title: "Show QR",
                    symbol: "qrcode",
                    tint: credentialsReady ? GhosttyTheme.neonGreen : GhosttyTheme.dimText
                ) {
                    continueFromCredentials()
                }
                .disabled(!credentialsReady)
            }

            OrbPanelText(text: "Never written to disk — it only travels in the short-lived pairing QR.")
        }
    }

    @ViewBuilder
    private var credentialFeedback: some View {
        if password.isEmpty && passwordConfirmation.isEmpty {
            OrbPanelText(text: "Use the password you sign in to this Mac with.")
        } else if password != passwordConfirmation {
            OrbStatusRow(label: "Passwords don't match", symbol: "xmark.circle", tint: GhosttyTheme.neonAmber)
        } else {
            OrbStatusRow(label: "Passwords match", symbol: "checkmark.circle.fill", tint: GhosttyTheme.neonGreen)
        }
    }

    @ViewBuilder
    private var pairStep: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let urlString = pairing.connection?.pairingURL().absoluteString {
                ZStack {
                    RoundedRectangle(cornerRadius: GhosttyTheme.corner).fill(.white)
                    QRImageView(payload: urlString)
                        .padding(10)
                }
                .frame(width: 268, height: 268)
                .contentShape(Rectangle())
                .onTapGesture {
                    FullScreenQRController.shared.show(urlString: urlString)
                }
                .help("Click to show the QR full screen")
                .accessibilityLabel("Pairing QR code")

                OrbActionButton(title: "Full screen", symbol: "arrow.up.left.and.arrow.down.right", prominent: false) {
                    FullScreenQRController.shared.show(urlString: urlString)
                }
            } else {
                OrbStatusRow(label: "The Mac network disappeared", symbol: "wifi.exclamationmark", tint: GhosttyTheme.neonAmber)
                OrbActionButton(title: "Back to Mac checks", symbol: "chevron.left", tint: GhosttyTheme.neonAmber) {
                    step = .macReady
                }
            }

            if isFinishing {
                OrbStatusRow(label: "Secure connection confirmed", symbol: "checkmark.seal.fill", tint: GhosttyTheme.neonGreen)
            } else {
                HStack(spacing: 7) {
                    ProgressView().controlSize(.small).tint(GhosttyTheme.neonCyan)
                    Text("Waiting for the headset…")
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(GhosttyTheme.neonCyan)
                }
                OrbActionButton(title: "Back", symbol: "chevron.left", prominent: false) {
                    step = .credentials
                }
            }
        }
        .onAppear(perform: finish)
    }

    // MARK: Logic

    private var macReady: Bool {
        CompanionOnboardingReadiness.macReady(
            remoteLogin: pairing.remoteLogin,
            hasConnection: pairing.connection != nil,
            tmuxInstalled: tmuxSetup.tmuxInstalled
        )
    }

    private var credentialsReady: Bool {
        CompanionOnboardingReadiness.credentialsReady(
            password: password,
            confirmation: passwordConfirmation
        )
    }

    private var remoteLoginDetail: String {
        switch pairing.remoteLogin {
        case .on: return "On and accepting SSH connections."
        case .off: return "Off. Switch on Remote Login in System Settings."
        case .unknown(let reason): return "Not verified yet: \(reason)"
        }
    }

    private var networkDetail: String {
        guard let connection = pairing.connection else {
            return pairing.lastError ?? "No active local network was found."
        }
        return "\(connection.host) — keep both devices on this network."
    }

    private var tmuxDetail: String {
        switch tmuxSetup.phase {
        case .checking: return "Checking…"
        case .installed: return "Terminals survive headset disconnects."
        case .missing, .failed: return "Without tmux, terminals die on disconnect."
        case .installing: return "Installing with Homebrew…"
        }
    }

    private func continueFromCredentials() {
        guard credentialsReady else { return }
        pairing.sshPassword = password
        pairing.rotateToken()
        step = .pair
    }

    private func refreshReadiness() {
        pairing.refreshInterfaces()
        pairing.refreshRemoteLogin()
        pairing.refreshHostFingerprint()
        tmuxSetup.refresh()
    }

    private func finish() {
        guard step == .pair, keyboardRelay.headsetConnected, !isFinishing else { return }
        isFinishing = true
        Task {
            // Let the user register that it worked before the panel swaps out.
            try? await Task.sleep(nanoseconds: 900_000_000)
            await MainActor.run {
                pairing.completeOnboarding()
            }
        }
    }
}
