import Network
import XCTest

@testable import GhosttyXRCompanion

/// Re-arming the media session happens on every codec / encryption change while a
/// mirror is running. The listener binds a fixed port, so re-arming must not
/// cancel and rebind it: `NWListener.cancel()` releases the port asynchronously,
/// and the rebind loses that race with EADDRINUSE. The listener then dies while
/// the headset is being told to dial it, which is a permanently black pane.
final class WindowMirrorMediaServerTests: XCTestCase {
    private func waitForListener(on server: WindowMirrorMediaServer) {
        // The listener comes up on the server's private queue.
        let bound = expectation(description: "media listener bound")
        DispatchQueue.global().asyncAfter(deadline: .now() + 0.35) { bound.fulfill() }
        wait(for: [bound], timeout: 2)
    }

    private func canConnectToMediaPort() -> Bool {
        let connected = expectation(description: "connected to media port")
        // NWConnection can report .failed/.cancelled after .ready, so settle
        // exactly once rather than fulfilling per state change.
        let settleLock = NSLock()
        var settled = false
        var success = false
        func settle(_ reachable: Bool) {
            settleLock.lock()
            defer { settleLock.unlock() }
            guard !settled else { return }
            settled = true
            success = reachable
            connected.fulfill()
        }

        let connection = NWConnection(
            host: .ipv4(.loopback),
            port: windowMirrorMediaPort,
            using: .tcp
        )
        connection.stateUpdateHandler = { state in
            switch state {
            case .ready: settle(true)
            // `.waiting` is how a refused connection surfaces here — NWConnection
            // retries rather than failing outright, so treat it as unreachable.
            case .failed, .cancelled, .waiting: settle(false)
            default: break
            }
        }
        connection.start(queue: .global())
        wait(for: [connected], timeout: 3)
        connection.cancel()
        settleLock.lock()
        defer { settleLock.unlock() }
        return success
    }

    func testReArmingKeepsMediaPortAcceptingConnections() throws {
        let server = WindowMirrorMediaServer()
        var errors: [String] = []
        let errorLock = NSLock()
        server.onError = { message in
            errorLock.lock()
            errors.append(message)
            errorLock.unlock()
        }
        defer { server.stop() }

        server.start(token: UUID().uuidString, encrypted: true)
        waitForListener(on: server)
        XCTAssertTrue(canConnectToMediaPort(), "listener should accept after the first arm")

        // Flipping encryption and codec re-arms the session repeatedly. Each one
        // must leave the port reachable.
        for _ in 0..<3 {
            server.start(token: UUID().uuidString, encrypted: false)
            waitForListener(on: server)
            XCTAssertTrue(canConnectToMediaPort(), "listener should survive a re-arm")
        }

        errorLock.lock()
        let observed = errors
        errorLock.unlock()
        XCTAssertTrue(
            observed.filter { $0.contains("listener") }.isEmpty,
            "re-arming must not report a listener failure: \(observed)"
        )
    }

    func testStopReleasesMediaPort() {
        let server = WindowMirrorMediaServer()
        server.start(token: UUID().uuidString, encrypted: true)
        waitForListener(on: server)
        XCTAssertTrue(canConnectToMediaPort())

        server.stop()
        waitForListener(on: server)
        XCTAssertFalse(canConnectToMediaPort(), "stop() should tear the listener down")

        // A fresh arm after a full stop must be able to rebind the same port.
        let restarted = WindowMirrorMediaServer()
        defer { restarted.stop() }
        restarted.start(token: UUID().uuidString, encrypted: true)
        waitForListener(on: restarted)
        XCTAssertTrue(canConnectToMediaPort(), "the port should be reusable after stop()")
    }
}
