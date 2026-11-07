import XCTest
@testable import GhosttyXRCompanion

final class CompanionOnboardingReadinessTests: XCTestCase {
    func testMacIsReadyOnlyWhenRemoteLoginNetworkAndTmuxAreAvailable() {
        XCTAssertTrue(
            CompanionOnboardingReadiness.macReady(
                remoteLogin: .on,
                hasConnection: true,
                tmuxInstalled: true
            )
        )
        XCTAssertFalse(
            CompanionOnboardingReadiness.macReady(
                remoteLogin: .off,
                hasConnection: true,
                tmuxInstalled: true
            )
        )
        XCTAssertFalse(
            CompanionOnboardingReadiness.macReady(
                remoteLogin: .unknown(reason: "probe failed"),
                hasConnection: true,
                tmuxInstalled: true
            )
        )
        XCTAssertFalse(
            CompanionOnboardingReadiness.macReady(
                remoteLogin: .on,
                hasConnection: false,
                tmuxInstalled: true
            )
        )
        XCTAssertFalse(
            CompanionOnboardingReadiness.macReady(
                remoteLogin: .on,
                hasConnection: true,
                tmuxInstalled: false
            )
        )
    }

    func testTmuxLookupPrefersFirstExecutableCandidate() {
        XCTAssertEqual(
            TmuxService.firstExecutable(
                in: ["/opt/homebrew/bin/tmux", "/usr/local/bin/tmux"],
                isExecutable: { $0 == "/usr/local/bin/tmux" }
            ),
            "/usr/local/bin/tmux"
        )
        XCTAssertNil(
            TmuxService.firstExecutable(
                in: ["/opt/homebrew/bin/tmux"],
                isExecutable: { _ in false }
            )
        )
    }

    func testCredentialsRequireMatchingNonEmptyPasswords() {
        XCTAssertTrue(
            CompanionOnboardingReadiness.credentialsReady(
                password: "correct horse battery staple",
                confirmation: "correct horse battery staple"
            )
        )
        XCTAssertFalse(
            CompanionOnboardingReadiness.credentialsReady(
                password: "",
                confirmation: ""
            )
        )
        XCTAssertFalse(
            CompanionOnboardingReadiness.credentialsReady(
                password: "one",
                confirmation: "two"
            )
        )
    }
}
