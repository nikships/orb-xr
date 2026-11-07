import Foundation
import Network

let windowMirrorRelayPort: NWEndpoint.Port = 45_874

/// Direct LAN TCP port carrying video. Separate from the control channel so a
/// large frame can never delay a keyframe request or pointer event.
let windowMirrorMediaPort: NWEndpoint.Port = 45_876

/// On-wire frame header version. Feature compatibility is negotiated via the
/// `version` field in the hello JSON payload.
let windowMirrorWireVersion: UInt8 = 1

/// Feature version 5 carries control over the SSH tunnel and video over a
/// dedicated direct TCP media socket. There is no jitter buffer, pacer, or
/// congestion controller: latency stays bounded because little is ever in
/// flight, not because a buffer absorbs jitter.
let windowMirrorFeatureVersion = 5

let windowMirrorMaxFrameBytes = 8 * 1024 * 1024

let windowMirrorHeaderBytes = 6

/// Fixed binary sub-header ahead of every video slice's bitstream.
let windowMirrorSliceHeaderBytes = 26

let windowMirrorSliceFlagKeyFrame: UInt8 = 0x01
let windowMirrorSliceFlagFrameEnd: UInt8 = 0x02

/// Upgrade copy shown when a peer hello feature version is below the minimum.
let windowMirrorFeatureUpgradeReason =
    "Update Orb XR and the Mac companion before using screen sharing."

enum WindowMirrorFrameType: UInt8, CaseIterable {
    case hello = 1
    case ready = 2
    case ping = 3
    case pong = 4
    case metadata = 5
    case input = 7
    case streamState = 8
    case close = 9
    case reset = 10
    // Symbiosis (full-screen share) pointer frames. Mac → headset except pointerReturn.
    case mouseDelta = 11
    case mouseButton = 12
    case mouseScroll = 13
    case pointerHandoff = 14
    case pointerReturn = 15
    // Direct-TCP media path (feature version 5).
    case videoSlice = 20
    case mediaHello = 21
    case mediaSession = 22
    case codecSupport = 23
    case keyFrameRequest = 24
    case latencyReport = 25

    var contractName: String {
        switch self {
        case .hello: return "hello"
        case .ready: return "ready"
        case .ping: return "ping"
        case .pong: return "pong"
        case .metadata: return "metadata"
        case .input: return "input"
        case .streamState: return "streamState"
        case .close: return "close"
        case .reset: return "reset"
        case .mouseDelta: return "mouseDelta"
        case .mouseButton: return "mouseButton"
        case .mouseScroll: return "mouseScroll"
        case .pointerHandoff: return "pointerHandoff"
        case .pointerReturn: return "pointerReturn"
        case .videoSlice: return "videoSlice"
        case .mediaHello: return "mediaHello"
        case .mediaSession: return "mediaSession"
        case .codecSupport: return "codecSupport"
        case .keyFrameRequest: return "keyFrameRequest"
        case .latencyReport: return "latencyReport"
        }
    }
}

struct WindowMirrorFrame {
    var type: WindowMirrorFrameType
    var payload: Data
}

struct WindowMirrorHello: Codable {
    var role: String
    var app: String
    var version: Int?

    init(role: String, app: String, version: Int?) {
        self.role = role
        self.app = app
        self.version = version
    }

    /// Feature version after applying the contract default (missing → 1).
    var resolvedVersion: Int { version ?? 1 }
}

/// Mac → headset: where and how to open the direct media socket. `token`
/// authenticates that socket and, when `encrypted`, keys its AES-GCM seal.
struct WindowMirrorMediaSessionMessage: Codable {
    var token: String
    var host: String
    var port: Int
    var codec: String
    var encrypted: Bool
}

/// Headset → Mac: first frame on the media socket, proving session ownership.
struct WindowMirrorMediaHelloMessage: Codable {
    var token: String
}

/// Headset → Mac: codecs the headset actually has a hardware decoder for.
struct WindowMirrorCodecSupportMessage: Codable {
    var codecs: [String]
    var lowLatency: Bool?
}

/// Headset → Mac: receiver-driven IDR request, replacing a periodic GOP.
struct WindowMirrorKeyFrameRequestMessage: Codable {
    var reason: String
}

/// Headset → Mac: measured end-to-end latency, the bitrate control variable.
struct WindowMirrorLatencyReportMessage: Codable {
    var endToEndMs: Double
    var decodedFps: Double
    var decodeQueueDepth: Int
}

struct WindowMirrorMetadata: Codable {
    var id: UInt32
    var title: String
    var app: String
    var width: Int
    var height: Int
    var scale: Double
    var interactive: Bool
    /// "window" (single-window mirror) or "display" (full-screen share / Symbiosis).
    var kind: String
    /// Video codec for the stream: "h264" or "hevc".
    var codec: String
}

struct WindowMirrorStreamStateMessage: Codable {
    var status: String
    var detail: String?
    var interactive: Bool
    var transport: String?
}

struct WindowMirrorInputMessage: Codable {
    var kind: String
    var action: String?
    var x: Double?
    var y: Double?
    var dx: Double?
    var dy: Double?
    var button: String?
    var code: String?
    var text: String?
    var mods: Int?
}

struct WindowMirrorCloseMessage: Codable {
    var reason: String?
}

/// Relative mouse movement while XR owns the pointer (Symbiosis).
/// `seq` is the per-pointer-session sequence number (handoff = 1, then +1 per event)
/// so the headset can detect dropped frames and cancel a stranded press.
struct WindowMirrorMouseDeltaMessage: Codable {
    var seq: Int
    var dx: Double
    var dy: Double
}

/// Mouse button transition while XR owns the pointer.
struct WindowMirrorMouseButtonMessage: Codable {
    var seq: Int
    var button: String
    var action: String // "down" | "up"
}

/// Scroll wheel while XR owns the pointer.
struct WindowMirrorMouseScrollMessage: Codable {
    var seq: Int
    var dx: Double
    var dy: Double
}

/// Mac → headset: XR takes pointer ownership (keyboard toggle or legacy edge).
/// `edge` is "left", "right", or "center"/"toggle"; `y` is fractional vertical spawn hint.
/// `seq` starts the pointer session's sequence numbering (always 1).
struct WindowMirrorPointerHandoffMessage: Codable {
    var seq: Int
    var edge: String
    var y: Double
}

/// Headset → Mac: the XR cursor re-entered the mirror pane; Mac takes ownership.
/// `x`/`y` are fractional 0..1 coordinates on the shared display.
struct WindowMirrorPointerReturnMessage: Codable {
    var x: Double
    var y: Double
}

/// Typed inbound message after frame framing + payload validation.
enum WindowMirrorMessage {
    case hello(WindowMirrorHello)
    case ready
    case ping
    case pong
    case metadata(WindowMirrorMetadata)
    case input(WindowMirrorInputMessage)
    case streamState(WindowMirrorStreamStateMessage)
    case close(String?)
    case reset
    case mouseDelta(WindowMirrorMouseDeltaMessage)
    case mouseButton(WindowMirrorMouseButtonMessage)
    case mouseScroll(WindowMirrorMouseScrollMessage)
    case pointerHandoff(WindowMirrorPointerHandoffMessage)
    case pointerReturn(WindowMirrorPointerReturnMessage)
    case mediaHello(WindowMirrorMediaHelloMessage)
    case mediaSession(WindowMirrorMediaSessionMessage)
    case codecSupport(WindowMirrorCodecSupportMessage)
    case keyFrameRequest(WindowMirrorKeyFrameRequestMessage)
    case latencyReport(WindowMirrorLatencyReportMessage)
}

enum WindowMirrorProtocol {
    static func frame(type: WindowMirrorFrameType, payload: Data = Data()) -> Data {
        var data = Data(capacity: 6 + payload.count)
        data.append(windowMirrorWireVersion)
        data.append(type.rawValue)
        data.appendUInt32(UInt32(payload.count))
        data.append(payload)
        return data
    }

    static func jsonFrame<T: Encodable>(_ type: WindowMirrorFrameType, _ value: T) -> Data? {
        guard let payload = try? JSONEncoder().encode(value) else { return nil }
        return frame(type: type, payload: payload)
    }

    /// Builds a `videoSlice` frame: 6-byte wire header, then the fixed 26-byte
    /// binary sub-header, then the (optionally sealed) Annex-B bitstream.
    static func videoSliceFrame(
        sequence: UInt64,
        ptsUs: UInt64,
        width: Int,
        height: Int,
        sliceIndex: Int,
        sliceCount: Int,
        isKeyFrame: Bool,
        isFrameEnd: Bool,
        bitstream: Data
    ) -> Data {
        var payload = Data(capacity: windowMirrorSliceHeaderBytes + bitstream.count)
        payload.appendUInt64(sequence)
        payload.appendUInt64(ptsUs)
        payload.appendUInt16(UInt16(clamping: width))
        payload.appendUInt16(UInt16(clamping: height))
        payload.appendUInt16(UInt16(clamping: sliceIndex))
        payload.appendUInt16(UInt16(clamping: sliceCount))
        var flags: UInt8 = 0
        if isKeyFrame { flags |= windowMirrorSliceFlagKeyFrame }
        if isFrameEnd { flags |= windowMirrorSliceFlagFrameEnd }
        payload.append(flags)
        payload.append(0) // reserved
        payload.append(bitstream)
        return frame(type: .videoSlice, payload: payload)
    }

    /// True when a peer hello feature version meets the negotiated minimum.
    static func isFeatureCompatible(_ version: Int) -> Bool {
        version >= windowMirrorFeatureVersion
    }

    /// Typed payload decode with contract defaults. Owns payload validity so
    /// `WindowMirrorRelayServer` only routes transport consequences.
    static func parse(_ frame: WindowMirrorFrame) throws -> WindowMirrorMessage {
        switch frame.type {
        case .hello:
            return .hello(try parseHello(frame.payload))
        case .ready:
            return .ready
        case .ping:
            return .ping
        case .pong:
            return .pong
        case .metadata:
            return .metadata(try parseMetadata(frame.payload))
        case .input:
            return .input(try parseInput(frame.payload))
        case .streamState:
            return .streamState(try parseStreamState(frame.payload))
        case .close:
            return .close(parseCloseReason(frame.payload))
        case .reset:
            return .reset
        case .mouseDelta:
            return .mouseDelta(try parseMouseDelta(frame.payload))
        case .mouseButton:
            return .mouseButton(try parseMouseButton(frame.payload))
        case .mouseScroll:
            return .mouseScroll(try parseMouseScroll(frame.payload))
        case .pointerHandoff:
            return .pointerHandoff(try parsePointerHandoff(frame.payload))
        case .pointerReturn:
            return .pointerReturn(try parsePointerReturn(frame.payload))
        case .mediaHello:
            return .mediaHello(try parseMediaHello(frame.payload))
        case .mediaSession:
            return .mediaSession(try parseMediaSession(frame.payload))
        case .codecSupport:
            return .codecSupport(try parseCodecSupport(frame.payload))
        case .keyFrameRequest:
            return .keyFrameRequest(try parseKeyFrameRequest(frame.payload))
        case .latencyReport:
            return .latencyReport(try parseLatencyReport(frame.payload))
        case .videoSlice:
            throw WindowMirrorRelayError.protocolError(
                "Video slices decode via WindowMirrorVideoSlice, not parse()."
            )
        }
    }

    // MARK: - Payload parsers (contract defaults)

    static func parseHello(_ data: Data) throws -> WindowMirrorHello {
        let obj = try jsonObject(data)
        guard let role = obj.string("role"), let app = obj.string("app") else {
            throw WindowMirrorRelayError.protocolError("Window mirror hello omitted role/app.")
        }
        return WindowMirrorHello(role: role, app: app, version: obj.int("version"))
    }

    static func parseMetadata(_ data: Data) throws -> WindowMirrorMetadata {
        let obj = try jsonObject(data)
        return WindowMirrorMetadata(
            id: UInt32(obj.int("id") ?? 0),
            title: obj.string("title") ?? "",
            app: obj.string("app") ?? "",
            width: obj.int("width") ?? 1,
            height: obj.int("height") ?? 1,
            scale: obj.double("scale") ?? 1.0,
            interactive: obj.bool("interactive") ?? false,
            kind: obj.string("kind") ?? "window",
            codec: obj.string("codec") ?? "h264"
        )
    }

    static func parseInput(_ data: Data) throws -> WindowMirrorInputMessage {
        let obj = try jsonObject(data)
        guard let kind = obj.string("kind") else {
            throw WindowMirrorRelayError.protocolError("Window mirror input omitted kind.")
        }
        return WindowMirrorInputMessage(
            kind: kind,
            action: obj.string("action"),
            x: obj.double("x"),
            y: obj.double("y"),
            dx: obj.double("dx"),
            dy: obj.double("dy"),
            button: obj.string("button"),
            code: obj.string("code"),
            text: obj.string("text"),
            mods: obj.int("mods")
        )
    }

    static func parseStreamState(_ data: Data) throws -> WindowMirrorStreamStateMessage {
        let obj = try jsonObject(data)
        return WindowMirrorStreamStateMessage(
            status: obj.string("status") ?? "",
            detail: obj.string("detail"),
            interactive: obj.bool("interactive") ?? false,
            transport: obj.string("transport")
        )
    }

    static func parseCloseReason(_ data: Data) -> String? {
        guard let obj = try? jsonObject(data) else { return nil }
        return obj.string("reason")
    }

    static func parseMouseDelta(_ data: Data) throws -> WindowMirrorMouseDeltaMessage {
        let obj = try jsonObject(data)
        return WindowMirrorMouseDeltaMessage(
            seq: obj.int("seq") ?? 0,
            dx: obj.double("dx") ?? 0,
            dy: obj.double("dy") ?? 0
        )
    }

    static func parseMouseButton(_ data: Data) throws -> WindowMirrorMouseButtonMessage {
        let obj = try jsonObject(data)
        return WindowMirrorMouseButtonMessage(
            seq: obj.int("seq") ?? 0,
            button: obj.string("button") ?? "left",
            action: obj.string("action") ?? ""
        )
    }

    static func parseMouseScroll(_ data: Data) throws -> WindowMirrorMouseScrollMessage {
        let obj = try jsonObject(data)
        return WindowMirrorMouseScrollMessage(
            seq: obj.int("seq") ?? 0,
            dx: obj.double("dx") ?? 0,
            dy: obj.double("dy") ?? 0
        )
    }

    static func parsePointerHandoff(_ data: Data) throws -> WindowMirrorPointerHandoffMessage {
        let obj = try jsonObject(data)
        return WindowMirrorPointerHandoffMessage(
            seq: obj.int("seq") ?? 0,
            edge: obj.string("edge") ?? "right",
            y: obj.double("y") ?? 0.5
        )
    }

    static func parsePointerReturn(_ data: Data) throws -> WindowMirrorPointerReturnMessage {
        let obj = try jsonObject(data)
        guard let x = obj.double("x"), let y = obj.double("y") else {
            throw WindowMirrorRelayError.protocolError("Window mirror pointer return omitted coordinates.")
        }
        return WindowMirrorPointerReturnMessage(x: x, y: y)
    }

    static func parseMediaHello(_ data: Data) throws -> WindowMirrorMediaHelloMessage {
        let obj = try jsonObject(data)
        guard let token = obj.string("token"), !token.isEmpty else {
            throw WindowMirrorRelayError.protocolError("Window mirror media hello omitted token.")
        }
        return WindowMirrorMediaHelloMessage(token: token)
    }

    static func parseMediaSession(_ data: Data) throws -> WindowMirrorMediaSessionMessage {
        let obj = try jsonObject(data)
        // No silent fallbacks: a missing token or host is a real error rather
        // than a guessed endpoint.
        guard let token = obj.string("token"), !token.isEmpty else {
            throw WindowMirrorRelayError.protocolError("Window mirror media session omitted token.")
        }
        guard let host = obj.string("host"), !host.isEmpty else {
            throw WindowMirrorRelayError.protocolError("Window mirror media session omitted host.")
        }
        return WindowMirrorMediaSessionMessage(
            token: token,
            host: host,
            port: obj.int("port") ?? Int(windowMirrorMediaPort.rawValue),
            codec: obj.string("codec") ?? "h264",
            encrypted: obj.bool("encrypted") ?? true
        )
    }

    static func parseCodecSupport(_ data: Data) throws -> WindowMirrorCodecSupportMessage {
        let obj = try jsonObject(data)
        let codecs = (obj["codecs"] as? [Any])?.compactMap { $0 as? String } ?? ["h264"]
        return WindowMirrorCodecSupportMessage(
            codecs: codecs.isEmpty ? ["h264"] : codecs,
            lowLatency: obj.bool("lowLatency")
        )
    }

    static func parseKeyFrameRequest(_ data: Data) throws -> WindowMirrorKeyFrameRequestMessage {
        let obj = try jsonObject(data)
        return WindowMirrorKeyFrameRequestMessage(reason: obj.string("reason") ?? "corruption")
    }

    static func parseLatencyReport(_ data: Data) throws -> WindowMirrorLatencyReportMessage {
        let obj = try jsonObject(data)
        return WindowMirrorLatencyReportMessage(
            endToEndMs: obj.double("endToEndMs") ?? 0,
            decodedFps: obj.double("decodedFps") ?? 0,
            decodeQueueDepth: obj.int("decodeQueueDepth") ?? 0
        )
    }

    private static func jsonObject(_ data: Data) throws -> [String: Any] {
        guard !data.isEmpty else { return [:] }
        let value = try JSONSerialization.jsonObject(with: data, options: [])
        guard let obj = value as? [String: Any] else {
            throw WindowMirrorRelayError.protocolError("Window mirror payload was not a JSON object.")
        }
        return obj
    }
}

final class WindowMirrorFrameDecoder {
    private var buffer = Data()
    private let maxFrameBytes: Int

    init(maxFrameBytes: Int = windowMirrorMaxFrameBytes) {
        self.maxFrameBytes = maxFrameBytes
    }

    func accept(_ data: Data) throws -> [WindowMirrorFrame] {
        buffer.append(data)
        var frames: [WindowMirrorFrame] = []
        while buffer.count >= 6 {
            guard buffer[buffer.startIndex] == windowMirrorWireVersion else {
                buffer.removeAll(keepingCapacity: true)
                throw WindowMirrorRelayError.protocolError("Window mirror protocol version mismatch.")
            }
            let typeByte = buffer[buffer.index(after: buffer.startIndex)]
            guard let type = WindowMirrorFrameType(rawValue: typeByte) else {
                buffer.removeAll(keepingCapacity: true)
                throw WindowMirrorRelayError.protocolError("Unsupported window mirror frame type \(typeByte).")
            }
            let lengthOffset = buffer.index(buffer.startIndex, offsetBy: 2)
            let length = Int(buffer.readUInt32(at: lengthOffset))
            guard length >= 0, length <= maxFrameBytes else {
                buffer.removeAll(keepingCapacity: true)
                throw WindowMirrorRelayError.protocolError("Window mirror frame exceeded \(maxFrameBytes) bytes.")
            }
            let frameLength = 6 + length
            guard buffer.count >= frameLength else { break }
            let payloadStart = buffer.index(buffer.startIndex, offsetBy: 6)
            let payloadEnd = buffer.index(payloadStart, offsetBy: length)
            frames.append(WindowMirrorFrame(type: type, payload: Data(buffer[payloadStart..<payloadEnd])))
            buffer.removeSubrange(buffer.startIndex..<payloadEnd)
        }
        return frames
    }

    func reset() {
        buffer.removeAll(keepingCapacity: true)
    }
}

enum WindowMirrorRelayError: Error, LocalizedError {
    case protocolError(String)

    var errorDescription: String? {
        switch self {
        case .protocolError(let message): return message
        }
    }
}

extension Data {
    mutating func appendUInt32(_ value: UInt32) {
        var bigEndian = value.bigEndian
        Swift.withUnsafeBytes(of: &bigEndian) { append(contentsOf: $0) }
    }

    mutating func appendUInt64(_ value: UInt64) {
        var bigEndian = value.bigEndian
        Swift.withUnsafeBytes(of: &bigEndian) { append(contentsOf: $0) }
    }

    mutating func appendUInt16(_ value: UInt16) {
        var bigEndian = value.bigEndian
        Swift.withUnsafeBytes(of: &bigEndian) { append(contentsOf: $0) }
    }

    func readUInt32(at index: Data.Index) -> UInt32 {
        let end = self.index(index, offsetBy: 4)
        return self[index..<end].reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
    }
}

private extension Dictionary where Key == String, Value == Any {
    func string(_ key: String) -> String? {
        self[key] as? String
    }

    func int(_ key: String) -> Int? {
        switch self[key] {
        case let v as Int: return v
        case let v as Int64: return Int(v)
        case let v as Double: return Int(v)
        case let v as NSNumber: return v.intValue
        default: return nil
        }
    }

    func double(_ key: String) -> Double? {
        switch self[key] {
        case let v as Double: return v
        case let v as Int: return Double(v)
        case let v as NSNumber: return v.doubleValue
        default: return nil
        }
    }

    func bool(_ key: String) -> Bool? {
        switch self[key] {
        case let v as Bool: return v
        case let v as NSNumber: return v.boolValue
        default: return nil
        }
    }
}
