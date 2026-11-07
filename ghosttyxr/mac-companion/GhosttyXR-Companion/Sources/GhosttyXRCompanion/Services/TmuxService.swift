import AppKit
import Foundation

/// Whether tmux is reachable for the headset's SSH sessions. The Android
/// bootstrap (`HostSessionBootstrap`) runs `command -v tmux` in the pane's
/// login shell; without tmux, panes cannot survive headset disconnects.
enum TmuxState: Equatable {
    case installed(path: String)
    case notInstalled
}

enum HomebrewState: Equatable {
    case installed(path: String)
    case notInstalled
}

enum TmuxService {
    /// Common install locations, checked when the login-shell probe fails
    /// (e.g. SHELL is unset in the GUI session).
    static let tmuxCandidatePaths = [
        "/opt/homebrew/bin/tmux",
        "/usr/local/bin/tmux",
        "/opt/local/bin/tmux",
    ]

    static let brewCandidatePaths = [
        "/opt/homebrew/bin/brew",
        "/usr/local/bin/brew",
    ]

    /// Mirrors what the headset's SSH pane will see: probe `command -v tmux`
    /// through the user's login shell first, then fall back to well-known paths.
    static func currentTmuxState() -> TmuxState {
        if let path = loginShellLookup(command: "tmux") {
            return .installed(path: path)
        }
        if let path = firstExecutable(in: tmuxCandidatePaths) {
            return .installed(path: path)
        }
        return .notInstalled
    }

    static func currentHomebrewState() -> HomebrewState {
        if let path = firstExecutable(in: brewCandidatePaths) {
            return .installed(path: path)
        }
        if let path = loginShellLookup(command: "brew") {
            return .installed(path: path)
        }
        return .notInstalled
    }

    static func firstExecutable(
        in candidates: [String],
        isExecutable: (String) -> Bool = { FileManager.default.isExecutableFile(atPath: $0) }
    ) -> String? {
        candidates.first(where: isExecutable)
    }

    /// Runs `command -v <name>` in the user's login shell so PATH matches what
    /// an SSH session resolves (Homebrew's shellenv lives in ~/.zprofile).
    static func loginShellLookup(command: String) -> String? {
        let shell = ProcessInfo.processInfo.environment["SHELL"] ?? "/bin/zsh"
        let process = Process()
        process.executableURL = URL(fileURLWithPath: shell)
        process.arguments = ["-l", "-c", "command -v \(command)"]
        let stdout = Pipe()
        process.standardOutput = stdout
        process.standardError = Pipe()
        do {
            try process.run()
        } catch {
            return nil
        }
        process.waitUntilExit()
        guard process.terminationStatus == 0 else { return nil }
        let output = String(
            data: stdout.fileHandleForReading.readDataToEndOfFile(),
            encoding: .utf8
        )?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return output.hasPrefix("/") ? output : nil
    }
}

/// Owns tmux detection state plus the user-initiated `brew install tmux` flow.
/// Nothing runs without an explicit click — same convention as Remote Login
/// (we open doors for the user; we never walk through them silently).
@MainActor
final class TmuxSetupController: ObservableObject {
    enum Phase: Equatable {
        case checking
        case installed(path: String)
        case missing(brew: HomebrewState)
        case installing
        case failed(reason: String, brew: HomebrewState)
    }

    @Published private(set) var phase: Phase = .checking
    @Published private(set) var installLogTail: String = ""

    private var installProcess: Process?
    private var activationObserver: NSObjectProtocol?

    init() {
        refresh()
        startActivationObserver()
    }

    deinit {
        if let activationObserver {
            NotificationCenter.default.removeObserver(activationObserver)
        }
    }

    var tmuxInstalled: Bool {
        if case .installed = phase { return true }
        return false
    }

    func refresh() {
        guard installProcess == nil else { return }
        let previous = phase
        Task.detached(priority: .userInitiated) {
            let tmux = TmuxService.currentTmuxState()
            let brew = TmuxService.currentHomebrewState()
            await MainActor.run { [weak self] in
                guard let self, self.installProcess == nil else { return }
                switch tmux {
                case .installed(let path):
                    self.phase = .installed(path: path)
                case .notInstalled:
                    // Keep a visible failure on screen until the user retries.
                    if case .failed = previous, case .failed = self.phase {
                        return
                    }
                    self.phase = .missing(brew: brew)
                }
            }
        }
    }

    /// User-initiated `brew install tmux`. Output is streamed so the user sees
    /// exactly what runs on their Mac.
    func installTmux() {
        guard installProcess == nil,
              case .installed(let brewPath) = TmuxService.currentHomebrewState() else {
            phase = .missing(brew: .notInstalled)
            return
        }

        phase = .installing
        installLogTail = "$ brew install tmux\n"

        let process = Process()
        process.executableURL = URL(fileURLWithPath: brewPath)
        process.arguments = ["install", "tmux"]
        var environment = ProcessInfo.processInfo.environment
        environment["NONINTERACTIVE"] = "1"
        environment["HOMEBREW_NO_AUTO_UPDATE"] = "1"
        process.environment = environment

        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = pipe
        pipe.fileHandleForReading.readabilityHandler = { [weak self] handle in
            let data = handle.availableData
            guard !data.isEmpty, let text = String(data: data, encoding: .utf8) else { return }
            Task { @MainActor [weak self] in
                self?.appendLog(text)
            }
        }
        process.terminationHandler = { [weak self] finished in
            pipe.fileHandleForReading.readabilityHandler = nil
            let status = finished.terminationStatus
            Task { @MainActor [weak self] in
                self?.finishInstall(exitStatus: status)
            }
        }

        do {
            try process.run()
            installProcess = process
        } catch {
            installProcess = nil
            phase = .failed(
                reason: "Could not start Homebrew: \(error.localizedDescription)",
                brew: .installed(path: brewPath)
            )
        }
    }

    /// Opens Terminal running the official Homebrew installer. The installer
    /// needs the user's password and consent, so it must run where the user can
    /// see and drive it — not hidden inside this app.
    func openHomebrewInstaller() {
        let script = """
        #!/bin/bash
        echo "This installs Homebrew (https://brew.sh), needed for 'brew install tmux'."
        /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
        echo
        echo "Done. Return to Orb XR and click Check Again."
        """
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("ghosttyxr-install-homebrew.command")
        do {
            try script.write(to: url, atomically: true, encoding: .utf8)
            try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: url.path)
            NSWorkspace.shared.open(url)
        } catch {
            phase = .failed(
                reason: "Could not launch the Homebrew installer: \(error.localizedDescription)",
                brew: .notInstalled
            )
        }
    }

    private func appendLog(_ text: String) {
        installLogTail = String((installLogTail + text).suffix(2_000))
    }

    private func finishInstall(exitStatus: Int32) {
        installProcess = nil
        if case .installed(let path) = TmuxService.currentTmuxState() {
            phase = .installed(path: path)
            return
        }
        let brew = TmuxService.currentHomebrewState()
        phase = .failed(
            reason: exitStatus == 0
                ? "brew finished but tmux is still not on the login-shell PATH."
                : "brew install tmux exited with status \(exitStatus). See the log above.",
            brew: brew
        )
    }

    private func startActivationObserver() {
        activationObserver = NotificationCenter.default.addObserver(
            forName: NSApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                self?.refresh()
            }
        }
    }
}
