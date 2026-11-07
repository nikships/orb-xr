import Foundation

/// Wire protocol for the terminal handoff channel — the headset's
/// "send terminals to Mac" pet command. Line-delimited UTF-8 JSON,
/// lockstep-versioned (`"v":1`), same discipline as the keyboard relay.
///
/// headset → companion: `hello`, `open_sessions`
/// companion → headset: `ready`, `open_result`, `error`
///
/// Keep the port/version/limits in lockstep with the Android side
/// (`terminal/handoff/TerminalHandoffProtocol.kt`).
enum TerminalHandoffProtocol {
    static let port: UInt16 = 45_875
    static let version = 1
    static let maxFrameBytes = 64 * 1024

    /// Same alphabet/length rule as the Android `HostSessionBootstrap`
    /// session names (`^[A-Za-z0-9_-]{1,64}$`). Validated before any name is
    /// ever placed near a shell command.
    static func isValidSessionName(_ name: String) -> Bool {
        let bytes = Array(name.utf8)
        guard !bytes.isEmpty, bytes.count <= 64, bytes.count == name.count else { return false }
        return bytes.allSatisfy { byte in
            (0x30...0x39).contains(byte) || // 0-9
                (0x41...0x5A).contains(byte) || // A-Z
                (0x61...0x7A).contains(byte) || // a-z
                byte == 0x2D || byte == 0x5F // - _
        }
    }

    static func decodeLine(_ data: Data) throws -> TerminalHandoffInbound {
        let message: TerminalHandoffWireMessage
        do {
            message = try JSONDecoder().decode(TerminalHandoffWireMessage.self, from: data)
        } catch {
            throw TerminalHandoffProtocolError.malformed("undecodable JSON line")
        }
        guard message.v == version else {
            throw TerminalHandoffProtocolError.unsupportedVersion(message.v ?? -1)
        }
        switch message.type {
        case "hello":
            return .hello(role: message.role)
        case "open_sessions":
            guard let reqId = message.reqId, !reqId.isEmpty else {
                throw TerminalHandoffProtocolError.malformed("open_sessions missing req_id")
            }
            guard let wireSessions = message.sessions, !wireSessions.isEmpty else {
                throw TerminalHandoffProtocolError.malformed("open_sessions missing sessions")
            }
            let sessions = try wireSessions.map { wire -> TerminalHandoffSession in
                guard isValidSessionName(wire.name) else {
                    throw TerminalHandoffProtocolError.invalidSessionName(wire.name)
                }
                return TerminalHandoffSession(name: wire.name, title: wire.title)
            }
            return .openSessions(reqId: reqId, sessions: sessions)
        default:
            throw TerminalHandoffProtocolError.malformed(
                "unsupported message type \(message.type ?? "<nil>")"
            )
        }
    }

    static func readyLineData() -> Data {
        encodeLine(TerminalHandoffWireMessage(v: version, type: "ready"))
    }

    static func openResultLineData(
        reqId: String?,
        ok: Bool,
        opened: Int,
        failed: [TerminalHandoffOpenFailure]
    ) -> Data {
        var message = TerminalHandoffWireMessage(v: version, type: "open_result")
        message.reqId = reqId
        message.ok = ok
        message.opened = opened
        message.failed = failed.map { TerminalHandoffWireFailure(name: $0.name, error: $0.error) }
        return encodeLine(message)
    }

    static func errorLineData(message text: String) -> Data {
        var message = TerminalHandoffWireMessage(v: version, type: "error")
        message.message = text
        return encodeLine(message)
    }

    private static func encodeLine(_ message: TerminalHandoffWireMessage) -> Data {
        var data = (try? JSONEncoder().encode(message)) ?? Data()
        data.append(0x0A)
        return data
    }
}

enum TerminalHandoffProtocolError: Error, Equatable {
    case malformed(String)
    case unsupportedVersion(Int)
    case invalidSessionName(String)
}

/// One live headset pane's host tmux session (`gxr-<cockpitId>-<paneKey>`).
/// `title` is cosmetic (the pane's last OSC title); the attach command only
/// ever uses the validated `name`.
struct TerminalHandoffSession: Equatable {
    let name: String
    let title: String?

    init(name: String, title: String? = nil) {
        self.name = name
        self.title = title
    }
}

/// A session the opener could not verify or open, with its reason.
struct TerminalHandoffOpenFailure: Equatable {
    let name: String
    let error: String
}

/// Decoded headset → companion messages.
enum TerminalHandoffInbound: Equatable {
    case hello(role: String?)
    case openSessions(reqId: String, sessions: [TerminalHandoffSession])
}

private struct TerminalHandoffWireMessage: Codable {
    var v: Int?
    var type: String?
    var role: String?
    var app: String?
    var reqId: String?
    var sessions: [TerminalHandoffWireSession]?
    var ok: Bool?
    var opened: Int?
    var failed: [TerminalHandoffWireFailure]?
    var message: String?

    enum CodingKeys: String, CodingKey {
        case v, type, role, app, sessions, ok, opened, failed, message
        case reqId = "req_id"
    }
}

private struct TerminalHandoffWireSession: Codable {
    var name: String
    var title: String?
}

private struct TerminalHandoffWireFailure: Codable {
    var name: String
    var error: String
}
