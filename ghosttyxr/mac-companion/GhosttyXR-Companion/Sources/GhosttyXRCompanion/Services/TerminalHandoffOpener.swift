import AppKit
import Foundation
import os

private let handoffLogger = Logger(subsystem: "dev.ghosttyxr.companion", category: "TerminalHandoff")

/// Outcome of an `open_sessions` request — mirrors the `open_result` wire message.
struct TerminalHandoffOpenOutcome: Equatable {
    var ok: Bool
    var opened: Int
    var failed: [TerminalHandoffOpenFailure]

    static func failure(_ failed: [TerminalHandoffOpenFailure]) -> TerminalHandoffOpenOutcome {
        TerminalHandoffOpenOutcome(ok: false, opened: 0, failed: failed)
    }
}

/// Opens one local Mac terminal window per handed-off headset pane, attached
/// to the **same** host `tmux -L gxr` session the pane was running in.
///
/// "Default terminal" on macOS: there is **no** system default-terminal API
/// (unlike browsers). The closest reliable behavior, and what this opener
/// implements:
///
///  1. Write a short-lived `gxr-attach-<session>.command` script whose body
///     is `exec tmux -L gxr attach-session -t <session>`.
///  2. Open it with `NSWorkspace.shared.open` so Launch Services routes it to
///     the user's **Open With** handler for `.command` files — Terminal.app
///     by default, Ghostty/iTerm if the user reassigned it.
///  3. If Launch Services declines, fall back to AppleScript telling
///     Terminal.app to `do script` the same attach command.
///
/// Do not "improve" this into a fake system-default lookup; none exists.
///
/// All-or-nothing contract: every session is verified against
/// `tmux -L gxr has-session` **before** any window opens, so a missing
/// session opens nothing. A mid-batch Launch Services failure is still
/// reported as `ok:false` (the headset then leaves XR untouched); windows
/// already opened in that batch are harmless extra attaches the user can
/// close, and tmux sessions are never killed either way.
///
/// Side effects (tmux probe, script writes, Launch Services, AppleScript) are
/// injectable so the decision logic is unit-testable without opening windows.
struct TerminalHandoffOpener {
    /// Resolves the tmux binary the way the headset's SSH panes do (login
    /// shell PATH first, then well-known Homebrew/MacPorts paths).
    var tmuxPath: () -> String? = {
        if case .installed(let path) = TmuxService.currentTmuxState() { return path }
        return nil
    }

    /// `tmux -L gxr has-session -t <name>` — true when the session exists.
    var sessionExists: (_ tmuxPath: String, _ sessionName: String) -> Bool = { tmuxPath, name in
        let process = Process()
        process.executableURL = URL(fileURLWithPath: tmuxPath)
        process.arguments = ["-L", "gxr", "has-session", "-t", name]
        process.standardOutput = Pipe()
        process.standardError = Pipe()
        do {
            try process.run()
        } catch {
            return false
        }
        process.waitUntilExit()
        return process.terminationStatus == 0
    }

    /// Launch Services open — the user's `.command` handler decides the app.
    var openCommandFile: (URL) -> Bool = { url in
        NSWorkspace.shared.open(url)
    }

    /// Terminal.app fallback when Launch Services declines the `.command`.
    var appleScriptFallback: (_ attachCommand: String) -> Bool = { attachCommand in
        let escaped = attachCommand
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\"", with: "\\\"")
        let source = """
        tell application "Terminal"
            activate
            do script "\(escaped)"
        end tell
        """
        var error: NSDictionary?
        guard let script = NSAppleScript(source: source) else { return false }
        script.executeAndReturnError(&error)
        return error == nil
    }

    /// Best-effort deferred delete of the temp scripts once the terminal app
    /// has had time to read them. Injectable so tests can capture instead.
    var scheduleCleanup: ([URL]) -> Void = { urls in
        DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + 15) {
            urls.forEach { try? FileManager.default.removeItem(at: $0) }
        }
    }

    var scratchDirectory: URL = FileManager.default.urls(
        for: .cachesDirectory,
        in: .userDomainMask
    )[0].appendingPathComponent("GhosttyXRCompanion/handoff", isDirectory: true)

    func open(sessions: [TerminalHandoffSession]) -> TerminalHandoffOpenOutcome {
        guard !sessions.isEmpty else {
            return .failure([TerminalHandoffOpenFailure(name: "-", error: "no sessions requested")])
        }
        // Re-validate defensively even though the protocol layer already did.
        let invalid = sessions.filter { !TerminalHandoffProtocol.isValidSessionName($0.name) }
        guard invalid.isEmpty else {
            return .failure(invalid.map {
                TerminalHandoffOpenFailure(name: $0.name, error: "invalid session name")
            })
        }
        guard let tmux = tmuxPath() else {
            return .failure(sessions.map {
                TerminalHandoffOpenFailure(name: $0.name, error: "tmux not found on this Mac")
            })
        }

        // Phase 1 — verify everything before opening anything (all-or-nothing).
        let missing = sessions.filter { !sessionExists(tmux, $0.name) }
        guard missing.isEmpty else {
            handoffLogger.warning("handoff refused: \(missing.count, privacy: .public) session(s) missing")
            return .failure(missing.map {
                TerminalHandoffOpenFailure(name: $0.name, error: "no such session")
            })
        }

        // Phase 2 — write one .command per session.
        do {
            try FileManager.default.createDirectory(
                at: scratchDirectory,
                withIntermediateDirectories: true
            )
        } catch {
            return .failure(sessions.map {
                TerminalHandoffOpenFailure(name: $0.name, error: "could not create handoff scripts directory")
            })
        }
        var scriptURLs: [URL] = []
        for session in sessions {
            let url = scratchDirectory.appendingPathComponent("gxr-attach-\(session.name).command")
            let contents = Self.scriptContents(tmuxPath: tmux, sessionName: session.name)
            do {
                try contents.write(to: url, atomically: true, encoding: .utf8)
                try FileManager.default.setAttributes(
                    [.posixPermissions: 0o755],
                    ofItemAtPath: url.path
                )
            } catch {
                scriptURLs.forEach { try? FileManager.default.removeItem(at: $0) }
                return .failure([TerminalHandoffOpenFailure(
                    name: session.name,
                    error: "could not write attach script"
                )])
            }
            scriptURLs.append(url)
        }

        // Phase 3 — open each window; Terminal.app AppleScript is the fallback.
        var opened = 0
        for (session, url) in zip(sessions, scriptURLs) {
            let attachCommand = Self.attachCommand(tmuxPath: tmux, sessionName: session.name)
            if openCommandFile(url) || appleScriptFallback(attachCommand) {
                opened += 1
            } else {
                handoffLogger.error("handoff open failed for \(session.name, privacy: .public)")
                scheduleCleanup(scriptURLs)
                // Mid-batch failure: report ok:false so the headset keeps its
                // panes. Already-opened windows are redundant attaches, not
                // data loss — tmux sessions are never killed on this path.
                return TerminalHandoffOpenOutcome(
                    ok: false,
                    opened: opened,
                    failed: [TerminalHandoffOpenFailure(
                        name: session.name,
                        error: "macOS could not open a terminal window"
                    )]
                )
            }
        }
        scheduleCleanup(scriptURLs)
        handoffLogger.info("handoff opened \(opened, privacy: .public) terminal window(s)")
        return TerminalHandoffOpenOutcome(ok: true, opened: opened, failed: [])
    }

    /// The exact command a handed-off window runs. Session names are already
    /// validated to `[A-Za-z0-9_-]{1,64}` so they need no quoting; the tmux
    /// path is user-environment-derived and is quoted.
    static func attachCommand(tmuxPath: String, sessionName: String) -> String {
        "\"\(tmuxPath)\" -L gxr attach-session -t \(sessionName)"
    }

    static func scriptContents(tmuxPath: String, sessionName: String) -> String {
        """
        #!/bin/zsh
        # Orb XR terminal handoff: attach this window to the live host session.
        # The session (and any coding agent inside it) was already running; this
        # window only attaches. Closing the window detaches — it never kills.
        exec \(attachCommand(tmuxPath: tmuxPath, sessionName: sessionName))
        """
    }
}
