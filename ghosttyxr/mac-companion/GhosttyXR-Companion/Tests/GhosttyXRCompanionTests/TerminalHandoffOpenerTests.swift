import XCTest
@testable import GhosttyXRCompanion

final class TerminalHandoffOpenerTests: XCTestCase {
    private var scratch: URL!

    override func setUpWithError() throws {
        scratch = FileManager.default.temporaryDirectory
            .appendingPathComponent("gxr-handoff-tests-\(UUID().uuidString)", isDirectory: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: scratch)
    }

    private func makeOpener(
        tmux: String? = "/opt/homebrew/bin/tmux",
        existing: Set<String> = [],
        openSucceeds: @escaping (URL) -> Bool = { _ in true },
        fallbackSucceeds: @escaping (String) -> Bool = { _ in false },
        onCleanup: @escaping ([URL]) -> Void = { _ in }
    ) -> TerminalHandoffOpener {
        var opener = TerminalHandoffOpener()
        opener.tmuxPath = { tmux }
        opener.sessionExists = { _, name in existing.contains(name) }
        opener.openCommandFile = openSucceeds
        opener.appleScriptFallback = fallbackSucceeds
        opener.scheduleCleanup = onCleanup
        opener.scratchDirectory = scratch
        return opener
    }

    func testScriptContentsAttachToTheGxrSocketSession() {
        let contents = TerminalHandoffOpener.scriptContents(
            tmuxPath: "/opt/homebrew/bin/tmux",
            sessionName: "gxr-abc-2"
        )
        XCTAssertTrue(contents.hasPrefix("#!/bin/zsh"))
        XCTAssertTrue(contents.contains(
            #"exec "/opt/homebrew/bin/tmux" -L gxr attach-session -t gxr-abc-2"#
        ))
        XCTAssertFalse(contents.contains("kill-session"))
    }

    func testOpensEveryVerifiedSessionAndWritesScripts() {
        var openedURLs: [URL] = []
        var cleaned: [URL] = []
        let opener = makeOpener(
            existing: ["gxr-abc-0", "gxr-abc-1"],
            openSucceeds: { url in openedURLs.append(url); return true },
            onCleanup: { cleaned.append(contentsOf: $0) }
        )
        let outcome = opener.open(sessions: [
            TerminalHandoffSession(name: "gxr-abc-0", title: "claude"),
            TerminalHandoffSession(name: "gxr-abc-1"),
        ])
        XCTAssertEqual(outcome, TerminalHandoffOpenOutcome(ok: true, opened: 2, failed: []))
        XCTAssertEqual(openedURLs.count, 2)
        XCTAssertTrue(openedURLs[0].lastPathComponent.hasSuffix(".command"))
        XCTAssertEqual(cleaned.count, 2)
        // The scripts really exist on disk with the attach command inside.
        let body = try? String(contentsOf: openedURLs[0], encoding: .utf8)
        XCTAssertTrue(body?.contains("attach-session -t gxr-abc-0") ?? false)
    }

    func testMissingSessionOpensNothing() {
        var openedURLs: [URL] = []
        let opener = makeOpener(
            existing: ["gxr-abc-0"], // gxr-abc-1 does not exist on the host
            openSucceeds: { url in openedURLs.append(url); return true }
        )
        let outcome = opener.open(sessions: [
            TerminalHandoffSession(name: "gxr-abc-0"),
            TerminalHandoffSession(name: "gxr-abc-1"),
        ])
        XCTAssertFalse(outcome.ok)
        XCTAssertEqual(outcome.opened, 0)
        XCTAssertEqual(outcome.failed, [
            TerminalHandoffOpenFailure(name: "gxr-abc-1", error: "no such session"),
        ])
        XCTAssertTrue(openedURLs.isEmpty, "all-or-nothing: no window may open when any session is missing")
    }

    func testTmuxNotFoundFailsEverySession() {
        let opener = makeOpener(tmux: nil)
        let outcome = opener.open(sessions: [TerminalHandoffSession(name: "gxr-abc-0")])
        XCTAssertFalse(outcome.ok)
        XCTAssertEqual(outcome.failed.first?.error, "tmux not found on this Mac")
    }

    func testInvalidNameIsRejectedDefensively() {
        let opener = makeOpener(existing: ["ok"])
        let outcome = opener.open(sessions: [TerminalHandoffSession(name: "bad name")])
        XCTAssertFalse(outcome.ok)
        XCTAssertEqual(outcome.failed.first?.error, "invalid session name")
    }

    func testLaunchServicesDeclineFallsBackToTerminalAppleScript() {
        var fallbackCommands: [String] = []
        let opener = makeOpener(
            existing: ["gxr-abc-0"],
            openSucceeds: { _ in false },
            fallbackSucceeds: { command in fallbackCommands.append(command); return true }
        )
        let outcome = opener.open(sessions: [TerminalHandoffSession(name: "gxr-abc-0")])
        XCTAssertTrue(outcome.ok)
        XCTAssertEqual(outcome.opened, 1)
        XCTAssertEqual(fallbackCommands.count, 1)
        XCTAssertTrue(fallbackCommands[0].contains("-L gxr attach-session -t gxr-abc-0"))
    }

    func testOpenFailureAfterFallbackReportsNotOk() {
        let opener = makeOpener(
            existing: ["gxr-abc-0", "gxr-abc-1"],
            openSucceeds: { url in url.lastPathComponent.contains("gxr-abc-0") },
            fallbackSucceeds: { _ in false }
        )
        let outcome = opener.open(sessions: [
            TerminalHandoffSession(name: "gxr-abc-0"),
            TerminalHandoffSession(name: "gxr-abc-1"),
        ])
        XCTAssertFalse(outcome.ok, "mid-batch open failure must report ok:false so XR stays attached")
        XCTAssertEqual(outcome.opened, 1)
        XCTAssertEqual(outcome.failed.first?.name, "gxr-abc-1")
    }
}
