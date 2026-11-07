import AppKit
import Combine
import Foundation

/// Persists completion only after the companion observes a live headset connection.
private enum OnboardingDefaults {
    static let completionKey = "dev.ghosttyxr.companion.onboarding.v2.completed"
}

@MainActor
final class PairingController: ObservableObject {
    @Published private(set) var connection: ConnectionInfo?
    @Published private(set) var interfaceCandidates: [NetworkInterface] = []
    @Published var selectedInterfaceName: String?
    @Published private(set) var remoteLogin: RemoteLoginState = .unknown(reason: "not yet probed")
    @Published private(set) var hostFingerprint: String?
    @Published private(set) var lastError: String?
    @Published private(set) var onboardingComplete: Bool
    @Published var sshPassword: String = "" {
        didSet { rebuildConnection() }
    }

    private let username: String
    private let port: Int = 22
    private let tokenLifetime: TimeInterval = 30
    private var tickTask: Task<Void, Never>?
    private var activationObserver: NSObjectProtocol?

    init(username: String = NSUserName()) {
        self.username = username
        self.onboardingComplete = UserDefaults.standard.bool(forKey: OnboardingDefaults.completionKey)
        refreshInterfaces()
        refreshRemoteLogin()
        refreshHostFingerprint()
        rotateToken()
        startTicker()
        startActivationObserver()
    }

    deinit {
        tickTask?.cancel()
        if let activationObserver {
            NotificationCenter.default.removeObserver(activationObserver)
        }
    }

    func refreshInterfaces() {
        do {
            let ifs = try NetworkInterfaceService.activeIPv4Interfaces()
            interfaceCandidates = ifs
            if !ifs.contains(where: { $0.name == selectedInterfaceName }) {
                selectedInterfaceName = ifs.first?.name
            }
            rebuildConnection()
            lastError = nil
        } catch {
            interfaceCandidates = []
            selectedInterfaceName = nil
            connection = nil
            lastError = error.localizedDescription
        }
    }

    func refreshRemoteLogin() {
        remoteLogin = RemoteLoginService.currentState()
    }

    func refreshHostFingerprint() {
        hostFingerprint = try? SSHKeyService.hostFingerprint()
        rebuildConnection()
    }

    func openSharingSettings() {
        RemoteLoginService.openSharingSettings()
    }

    func completeOnboarding() {
        UserDefaults.standard.set(true, forKey: OnboardingDefaults.completionKey)
        onboardingComplete = true
    }

    func rotateToken() {
        updateConnection(token: pairingToken())
    }

    private func selectedInterface() -> NetworkInterface? {
        interfaceCandidates.first { $0.name == selectedInterfaceName } ?? interfaceCandidates.first
    }

    private func rebuildConnection() {
        let token = sshPassword.isEmpty
            ? (connection?.token ?? PairingToken.generate(lifetime: tokenLifetime))
            : pairingToken()
        updateConnection(token: token)
    }

    private func updateConnection(token: PairingToken) {
        guard let iface = selectedInterface() else {
            connection = nil
            return
        }
        connection = ConnectionInfo(
            username: username,
            host: iface.ipv4,
            port: port,
            token: token,
            hostFingerprint: hostFingerprint
        )
    }

    /// Re-probes sshd state + host fingerprint + interfaces whenever the user brings
    /// the companion back to the front. The typical flow: tap "Open Sharing Settings"
    /// -> toggle Remote Login on -> switch back here, and the cache updates without
    /// needing the user to click Refresh or restart.
    private func startActivationObserver() {
        // Skip the very first activation event we get on launch: the init-time probes
        // already covered that, so the activation callback would just do redundant work.
        var firstActivation = true
        activationObserver = NotificationCenter.default.addObserver(
            forName: NSApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            guard let self else { return }
            if firstActivation {
                firstActivation = false
                return
            }
            Task { @MainActor in
                self.refreshInterfaces()
                self.refreshRemoteLogin()
                self.refreshHostFingerprint()
            }
        }
    }

    private func startTicker() {
        tickTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 500_000_000)
                guard let self else { return }
                if let token = self.connection?.token, token.isExpired() {
                    self.rotateToken()
                }
            }
        }
    }

    private func pairingToken() -> PairingToken {
        if sshPassword.isEmpty {
            PairingToken.generate(lifetime: tokenLifetime)
        } else {
            PairingToken(value: sshPassword, issuedAt: Date(), lifetime: 24 * 60 * 60)
        }
    }
}
