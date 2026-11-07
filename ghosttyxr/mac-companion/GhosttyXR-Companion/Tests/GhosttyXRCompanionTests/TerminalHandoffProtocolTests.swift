import XCTest
@testable import GhosttyXRCompanion

final class TerminalHandoffProtocolTests: XCTestCase {
    private func decode(_ line: String) throws -> TerminalHandoffInbound {
        try TerminalHandoffProtocol.decodeLine(Data(line.utf8))
    }

    func testDecodesHello() throws {
        let inbound = try decode(#"{"v":1,"type":"hello","role":"headset","app":"ghosttyxr"}"#)
        XCTAssertEqual(inbound, .hello(role: "headset"))
    }

    func testDecodesOpenSessionsWithTitles() throws {
        let inbound = try decode(
            #"{"v":1,"type":"open_sessions","req_id":"r1","sessions":[{"name":"gxr-abc-0","title":"claude"},{"name":"gxr-abc-1"}]}"#
        )
        XCTAssertEqual(
            inbound,
            .openSessions(
                reqId: "r1",
                sessions: [
                    TerminalHandoffSession(name: "gxr-abc-0", title: "claude"),
                    TerminalHandoffSession(name: "gxr-abc-1", title: nil),
                ]
            )
        )
    }

    func testRejectsMissingVersion() {
        XCTAssertThrowsError(try decode(#"{"type":"hello"}"#)) { error in
            XCTAssertEqual(error as? TerminalHandoffProtocolError, .unsupportedVersion(-1))
        }
    }

    func testRejectsUnsupportedVersion() {
        XCTAssertThrowsError(try decode(#"{"v":2,"type":"hello"}"#)) { error in
            XCTAssertEqual(error as? TerminalHandoffProtocolError, .unsupportedVersion(2))
        }
    }

    func testRejectsUnknownType() {
        XCTAssertThrowsError(try decode(#"{"v":1,"type":"teleport"}"#))
    }

    func testRejectsOpenSessionsWithoutReqId() {
        XCTAssertThrowsError(try decode(
            #"{"v":1,"type":"open_sessions","sessions":[{"name":"gxr-abc-0"}]}"#
        ))
    }

    func testRejectsOpenSessionsWithEmptySessions() {
        XCTAssertThrowsError(try decode(
            #"{"v":1,"type":"open_sessions","req_id":"r1","sessions":[]}"#
        ))
    }

    func testRejectsInvalidSessionName() {
        XCTAssertThrowsError(try decode(
            #"{"v":1,"type":"open_sessions","req_id":"r1","sessions":[{"name":"bad; rm -rf /"}]}"#
        )) { error in
            XCTAssertEqual(
                error as? TerminalHandoffProtocolError,
                .invalidSessionName("bad; rm -rf /")
            )
        }
    }

    func testSessionNameValidationMatchesBootstrapAlphabet() {
        XCTAssertTrue(TerminalHandoffProtocol.isValidSessionName("gxr-abc123-0"))
        XCTAssertTrue(TerminalHandoffProtocol.isValidSessionName("A_b-9"))
        XCTAssertTrue(TerminalHandoffProtocol.isValidSessionName(String(repeating: "x", count: 64)))
        XCTAssertFalse(TerminalHandoffProtocol.isValidSessionName(""))
        XCTAssertFalse(TerminalHandoffProtocol.isValidSessionName("has space"))
        XCTAssertFalse(TerminalHandoffProtocol.isValidSessionName("semi;colon"))
        XCTAssertFalse(TerminalHandoffProtocol.isValidSessionName("dot.dot"))
        XCTAssertFalse(TerminalHandoffProtocol.isValidSessionName(String(repeating: "x", count: 65)))
        XCTAssertFalse(TerminalHandoffProtocol.isValidSessionName("émoji"))
    }

    func testReadyLineIsVersionedAndNewlineTerminated() throws {
        let data = TerminalHandoffProtocol.readyLineData()
        XCTAssertEqual(data.last, 0x0A)
        let json = try XCTUnwrap(
            JSONSerialization.jsonObject(with: data.dropLast()) as? [String: Any]
        )
        XCTAssertEqual(json["v"] as? Int, 1)
        XCTAssertEqual(json["type"] as? String, "ready")
    }

    func testOpenResultLineCarriesOutcome() throws {
        let data = TerminalHandoffProtocol.openResultLineData(
            reqId: "r9",
            ok: false,
            opened: 0,
            failed: [TerminalHandoffOpenFailure(name: "gxr-abc-1", error: "no such session")]
        )
        XCTAssertEqual(data.last, 0x0A)
        let json = try XCTUnwrap(
            JSONSerialization.jsonObject(with: data.dropLast()) as? [String: Any]
        )
        XCTAssertEqual(json["v"] as? Int, 1)
        XCTAssertEqual(json["type"] as? String, "open_result")
        XCTAssertEqual(json["req_id"] as? String, "r9")
        XCTAssertEqual(json["ok"] as? Bool, false)
        XCTAssertEqual(json["opened"] as? Int, 0)
        let failed = try XCTUnwrap(json["failed"] as? [[String: Any]])
        XCTAssertEqual(failed.count, 1)
        XCTAssertEqual(failed[0]["name"] as? String, "gxr-abc-1")
        XCTAssertEqual(failed[0]["error"] as? String, "no such session")
    }

    func testErrorLineCarriesMessage() throws {
        let data = TerminalHandoffProtocol.errorLineData(message: "boom")
        let json = try XCTUnwrap(
            JSONSerialization.jsonObject(with: data.dropLast()) as? [String: Any]
        )
        XCTAssertEqual(json["type"] as? String, "error")
        XCTAssertEqual(json["message"] as? String, "boom")
    }
}
