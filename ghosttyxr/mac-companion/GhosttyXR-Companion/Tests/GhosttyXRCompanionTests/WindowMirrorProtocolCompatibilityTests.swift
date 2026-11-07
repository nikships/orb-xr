import XCTest
@testable import GhosttyXRCompanion

/// Loads the shared repository contract and frozen vectors and proves the
/// Mac companion adapter matches them. Android runs the same fixtures.
final class WindowMirrorProtocolCompatibilityTests: XCTestCase {
    private var contract: [String: Any]!
    private var validCases: [[String: Any]]!
    private var invalidCases: [[String: Any]]!

    override func setUpWithError() throws {
        try super.setUpWithError()
        contract = try loadJSONObject(resource: "contract-v1")
        let valid = try loadJSONObject(resource: "valid")
        let invalid = try loadJSONObject(resource: "invalid")
        validCases = valid["cases"] as? [[String: Any]]
        invalidCases = invalid["cases"] as? [[String: Any]]
        XCTAssertFalse(validCases.isEmpty)
        XCTAssertFalse(invalidCases.isEmpty)
    }

    func testContractConstantsMatchSwiftAdapter() {
        XCTAssertEqual(contract["relayPort"] as? Int, Int(windowMirrorRelayPort.rawValue))
        XCTAssertEqual(contract["mediaPort"] as? Int, Int(windowMirrorMediaPort.rawValue))
        XCTAssertEqual(contract["wireVersion"] as? Int, Int(windowMirrorWireVersion))
        XCTAssertEqual(contract["featureVersion"] as? Int, windowMirrorFeatureVersion)
        XCTAssertEqual(contract["headerBytes"] as? Int, windowMirrorHeaderBytes)
        XCTAssertEqual(contract["maxFrameBytes"] as? Int, windowMirrorMaxFrameBytes)
    }

    func testContractFrameTableMatchesEnumExactly() throws {
        let frames = try XCTUnwrap(contract["frames"] as? [[String: Any]])
        let contractIds = frames.compactMap { $0["id"] as? Int }.sorted()
        let implIds = WindowMirrorFrameType.allCases.map { Int($0.rawValue) }.sorted()
        XCTAssertEqual(implIds, contractIds)

        for frame in frames {
            let id = try XCTUnwrap(frame["id"] as? Int)
            let name = try XCTUnwrap(frame["name"] as? String)
            let type = try XCTUnwrap(WindowMirrorFrameType(rawValue: UInt8(id)))
            XCTAssertEqual(type.contractName, name)
            XCTAssertNotNil(frame["direction"] as? String)
            XCTAssertNotNil(frame["transport"] as? String)
        }
    }

    func testValidVectorsDecodeThroughSwiftAdapter() throws {
        for caseObj in validCases {
            let name = try XCTUnwrap(caseObj["name"] as? String)
            guard let completeHex = caseObj["completeBytes"] as? String else { continue }
            let bytes = try Data(hexString: completeHex)
            let frames: [WindowMirrorFrame]
            if let chunkSizes = caseObj["chunkSizes"] as? [Int] {
                frames = try acceptChunked(bytes, chunkSizes: chunkSizes)
            } else {
                frames = try WindowMirrorFrameDecoder().accept(bytes)
            }

            if let expectedSeq = caseObj["expectedFrameSequence"] as? [String] {
                XCTAssertEqual(
                    frames.map(\.type.contractName),
                    expectedSeq,
                    "case \(name) frame sequence"
                )
            }

            if let expected = caseObj["expected"] as? [String: Any], frames.count == 1 {
                if frames[0].type == .videoSlice {
                    // Binary payload: never routed through the JSON parse path.
                    try assertExpectedVideoSlice(name: name, frame: frames[0], expected: expected)
                } else {
                    try assertExpectedPayload(name: name, frame: frames[0], expected: expected)
                }
            }
        }
    }

    func testInvalidVectorsMatchExpectedRecovery() throws {
        for caseObj in invalidCases {
            let name = try XCTUnwrap(caseObj["name"] as? String)
            let expected = try XCTUnwrap(caseObj["expected"] as? String)

            switch expected {
            case "wait_for_more_bytes":
                let bytes = try Data(hexString: caseObj["bytes"] as! String)
                let frames = try WindowMirrorFrameDecoder().accept(bytes)
                XCTAssertTrue(frames.isEmpty, "case \(name) should wait")
            case "reject_and_reset_decoder":
                let decoder = WindowMirrorFrameDecoder()
                let bytes = try Data(hexString: caseObj["bytes"] as! String)
                XCTAssertThrowsError(try decoder.accept(bytes), "case \(name)")
                let recovered = try decoder.accept(WindowMirrorProtocol.frame(type: .ping))
                XCTAssertEqual(recovered.single.type, .ping)
            case "reject_message_payload":
                let bytes = try Data(hexString: caseObj["bytes"] as! String)
                let frame = try WindowMirrorFrameDecoder().accept(bytes).single
                XCTAssertThrowsError(try WindowMirrorProtocol.parse(frame), "case \(name)")
                if caseObj["errorKind"] as? String == "truncated_slice_header" {
                    XCTAssertLessThan(frame.payload.count, windowMirrorSliceHeaderBytes, "case \(name)")
                }
            case "reject_hello_insufficient_feature_version":
                let bytes = try Data(hexString: caseObj["bytes"] as! String)
                let frame = try WindowMirrorFrameDecoder().accept(bytes).single
                guard case .hello(let hello) = try WindowMirrorProtocol.parse(frame) else {
                    return XCTFail("case \(name) expected hello")
                }
                XCTAssertFalse(WindowMirrorProtocol.isFeatureCompatible(hello.resolvedVersion))
                XCTAssertEqual(hello.resolvedVersion, caseObj["featureVersion"] as? Int)
            case "reject_then_accept_after_reset":
                let decoder = WindowMirrorFrameDecoder()
                let trigger = try Data(hexString: caseObj["resetTriggerHex"] as! String)
                XCTAssertThrowsError(try decoder.accept(trigger))
                let recovery = try Data(hexString: caseObj["recoveryHex"] as! String)
                let recovered = try decoder.accept(recovery)
                XCTAssertEqual(recovered.single.type, .ping)
            default:
                XCTFail("unknown expected \(expected) for \(name)")
            }
        }
    }

    func testHeaderOnlyOutboundFramesMatchFrozenBytes() throws {
        let byName = Dictionary(uniqueKeysWithValues: validCases.compactMap { c -> (String, [String: Any])? in
            guard let name = c["name"] as? String else { return nil }
            return (name, c)
        })
        XCTAssertEqual(
            WindowMirrorProtocol.frame(type: .ping),
            try Data(hexString: byName["ping"]!["completeBytes"] as! String)
        )
        XCTAssertEqual(
            WindowMirrorProtocol.frame(type: .pong),
            try Data(hexString: byName["pong"]!["completeBytes"] as! String)
        )
    }

    func testVideoSliceFramesMatchFrozenBytes() throws {
        let byName = Dictionary(uniqueKeysWithValues: validCases.compactMap { c -> (String, [String: Any])? in
            guard let name = c["name"] as? String else { return nil }
            return (name, c)
        })

        for name in ["video_slice_key_frame", "video_slice_frame_end"] {
            let caseObj = try XCTUnwrap(byName[name])
            let expected = try XCTUnwrap(caseObj["expected"] as? [String: Any])
            let built = WindowMirrorProtocol.videoSliceFrame(
                sequence: UInt64(try XCTUnwrap(expected["sequence"] as? Int)),
                ptsUs: UInt64(try XCTUnwrap(expected["ptsUs"] as? Int)),
                width: try XCTUnwrap(expected["width"] as? Int),
                height: try XCTUnwrap(expected["height"] as? Int),
                sliceIndex: try XCTUnwrap(expected["sliceIndex"] as? Int),
                sliceCount: try XCTUnwrap(expected["sliceCount"] as? Int),
                isKeyFrame: try XCTUnwrap(expected["isKeyFrame"] as? Bool),
                isFrameEnd: try XCTUnwrap(expected["isFrameEnd"] as? Bool),
                bitstream: try Data(hexString: try XCTUnwrap(expected["bitstreamHex"] as? String))
            )
            XCTAssertEqual(built, try Data(hexString: caseObj["completeBytes"] as! String), name)
        }
    }

    /// JSON key order is explicitly outside the contract, so these frames are
    /// asserted structurally: encode → decode → compare against frozen bytes'
    /// parsed content, plus a byte-exact header check.
    func testMediaControlFramesRoundTripToFrozenPayloads() throws {
        let byName = Dictionary(uniqueKeysWithValues: validCases.compactMap { c -> (String, [String: Any])? in
            guard let name = c["name"] as? String else { return nil }
            return (name, c)
        })

        func frozenPayload(_ name: String) throws -> Data {
            let bytes = try Data(hexString: byName[name]!["completeBytes"] as! String)
            return try WindowMirrorFrameDecoder().accept(bytes).single.payload
        }

        let mediaHello = try XCTUnwrap(
            WindowMirrorProtocol.jsonFrame(.mediaHello, WindowMirrorMediaHelloMessage(token: "tok-abc"))
        )
        XCTAssertEqual(mediaHello, try Data(hexString: byName["media_hello"]!["completeBytes"] as! String))

        let session = try WindowMirrorProtocol.parseMediaSession(frozenPayload("media_session"))
        let reEncodedSession = try XCTUnwrap(WindowMirrorProtocol.jsonFrame(.mediaSession, session))
        XCTAssertEqual(Array(reEncodedSession.prefix(2)), [windowMirrorWireVersion, WindowMirrorFrameType.mediaSession.rawValue])
        let reparsedSession = try WindowMirrorProtocol.parseMediaSession(
            try WindowMirrorFrameDecoder().accept(reEncodedSession).single.payload
        )
        XCTAssertEqual(reparsedSession.token, session.token)
        XCTAssertEqual(reparsedSession.host, session.host)
        XCTAssertEqual(reparsedSession.port, session.port)
        XCTAssertEqual(reparsedSession.codec, session.codec)
        XCTAssertEqual(reparsedSession.encrypted, session.encrypted)

        let codecs = try WindowMirrorProtocol.parseCodecSupport(frozenPayload("codec_support"))
        let reEncodedCodecs = try XCTUnwrap(WindowMirrorProtocol.jsonFrame(.codecSupport, codecs))
        XCTAssertEqual(Array(reEncodedCodecs.prefix(2)), [windowMirrorWireVersion, WindowMirrorFrameType.codecSupport.rawValue])
        let reparsedCodecs = try WindowMirrorProtocol.parseCodecSupport(
            try WindowMirrorFrameDecoder().accept(reEncodedCodecs).single.payload
        )
        XCTAssertEqual(reparsedCodecs.codecs, codecs.codecs)
        XCTAssertEqual(reparsedCodecs.lowLatency, codecs.lowLatency)

        let keyFrame = try WindowMirrorProtocol.parseKeyFrameRequest(frozenPayload("key_frame_request"))
        let reEncodedKeyFrame = try XCTUnwrap(WindowMirrorProtocol.jsonFrame(.keyFrameRequest, keyFrame))
        XCTAssertEqual(reEncodedKeyFrame, try Data(hexString: byName["key_frame_request"]!["completeBytes"] as! String))

        let latency = try WindowMirrorProtocol.parseLatencyReport(frozenPayload("latency_report"))
        let reparsedLatency = try WindowMirrorProtocol.parseLatencyReport(
            try WindowMirrorFrameDecoder()
                .accept(try XCTUnwrap(WindowMirrorProtocol.jsonFrame(.latencyReport, latency)))
                .single.payload
        )
        XCTAssertEqual(reparsedLatency.endToEndMs, latency.endToEndMs)
        XCTAssertEqual(reparsedLatency.decodedFps, latency.decodedFps)
        XCTAssertEqual(reparsedLatency.decodeQueueDepth, latency.decodeQueueDepth)
    }

    func testMacHelloFeatureNegotiation() throws {
        let macHello = WindowMirrorHello(role: "mac", app: "ghosttyxr-companion", version: windowMirrorFeatureVersion)
        let encoded = try XCTUnwrap(WindowMirrorProtocol.jsonFrame(.hello, macHello))
        XCTAssertEqual(encoded[0], windowMirrorWireVersion)
        let frame = try WindowMirrorFrameDecoder().accept(encoded).single
        guard case .hello(let hello) = try WindowMirrorProtocol.parse(frame) else {
            return XCTFail("expected hello")
        }
        XCTAssertEqual(hello.role, "mac")
        XCTAssertEqual(hello.resolvedVersion, windowMirrorFeatureVersion)
        XCTAssertTrue(WindowMirrorProtocol.isFeatureCompatible(hello.resolvedVersion))
        XCTAssertFalse(WindowMirrorProtocol.isFeatureCompatible(3))
    }

    func testInputPayloadDecodesOnMac() throws {
        let payload = #"{"kind":"pointer","action":"down","button":"left","x":0.25,"y":0.75}"#.data(using: .utf8)!
        let message = try WindowMirrorProtocol.parse(WindowMirrorFrame(type: .input, payload: payload))
        guard case .input(let input) = message else {
            return XCTFail("expected input")
        }
        XCTAssertEqual(input.kind, "pointer")
        XCTAssertEqual(input.action, "down")
        XCTAssertEqual(input.x, 0.25)
        XCTAssertEqual(input.y, 0.75)
    }

    func testFragmentationAndMultiFrame() throws {
        let ping = WindowMirrorProtocol.frame(type: .ping)
        let pong = WindowMirrorProtocol.frame(type: .pong)
        let decoder = WindowMirrorFrameDecoder()

        // Byte-by-byte header.
        for i in 0..<(ping.count - 1) {
            XCTAssertTrue(try decoder.accept(ping.subdata(in: i..<(i + 1))).isEmpty)
        }
        XCTAssertEqual(try decoder.accept(ping.subdata(in: (ping.count - 1)..<ping.count)).single.type, .ping)

        let multi = ping + pong + ping
        let frames = try WindowMirrorFrameDecoder().accept(multi)
        XCTAssertEqual(frames.map(\.type), [.ping, .pong, .ping])

        // Trailing partial then complete.
        let partial = try WindowMirrorFrameDecoder().accept(Data(pong.prefix(4)))
        XCTAssertTrue(partial.isEmpty)
    }

    func testDecoderRecoversAfterWrongVersionUnknownTypeAndOversized() throws {
        let decoder = WindowMirrorFrameDecoder()
        XCTAssertThrowsError(try decoder.accept(Data([2, 3, 0, 0, 0, 0])))
        XCTAssertEqual(try decoder.accept(WindowMirrorProtocol.frame(type: .ping)).single.type, .ping)

        XCTAssertThrowsError(try decoder.accept(Data([1, 99, 0, 0, 0, 0])))
        XCTAssertEqual(try decoder.accept(WindowMirrorProtocol.frame(type: .ping)).single.type, .ping)

        let small = WindowMirrorFrameDecoder(maxFrameBytes: 16)
        var oversized = Data([1, 16])
        oversized.appendUInt32(17)
        oversized.append(Data(count: 17))
        XCTAssertThrowsError(try small.accept(oversized))
        XCTAssertEqual(try small.accept(WindowMirrorProtocol.frame(type: .ping)).single.type, .ping)
    }

    // MARK: - Helpers

    private func assertExpectedPayload(name: String, frame: WindowMirrorFrame, expected: [String: Any]) throws {
        // Input is valid on Mac (headset → Mac).
        let message = try WindowMirrorProtocol.parse(frame)
        switch message {
        case .hello(let hello):
            if let role = expected["role"] as? String { XCTAssertEqual(hello.role, role, name) }
            if let version = expected["version"] as? Int { XCTAssertEqual(hello.resolvedVersion, version, name) }
        case .ping:
            XCTAssertEqual(expected["type"] as? String, "ping", name)
        case .pong:
            XCTAssertEqual(expected["type"] as? String, "pong", name)
        case .ready:
            XCTAssertEqual(expected["type"] as? String, "ready", name)
        case .reset:
            XCTAssertEqual(expected["type"] as? String, "reset", name)
        case .metadata(let meta):
            if let id = expected["id"] as? Int { XCTAssertEqual(Int(meta.id), id, name) }
            if let kind = expected["kind"] as? String { XCTAssertEqual(meta.kind, kind, name) }
            if let codec = expected["codec"] as? String { XCTAssertEqual(meta.codec, codec, name) }
            if let title = expected["title"] as? String { XCTAssertEqual(meta.title, title, name) }
            if let display = expected["isDisplayShare"] as? Bool {
                XCTAssertEqual(meta.kind == "display", display, name)
            }
        case .streamState(let state):
            if let status = expected["status"] as? String { XCTAssertEqual(state.status, status, name) }
            if let detail = expected["detail"] as? String { XCTAssertEqual(state.detail, detail, name) }
            if let interactive = expected["interactive"] as? Bool {
                XCTAssertEqual(state.interactive, interactive, name)
            }
            if let transport = expected["transport"] as? String {
                XCTAssertEqual(state.transport, transport, name)
            }
        case .close(let reason):
            if expected["reason"] is NSNull {
                XCTAssertNil(reason, name)
            } else if let r = expected["reason"] as? String {
                XCTAssertEqual(reason, r, name)
            }
        case .input(let input):
            if let kind = expected["kind"] as? String { XCTAssertEqual(input.kind, kind, name) }
            if let action = expected["action"] as? String { XCTAssertEqual(input.action, action, name) }
        case .pointerHandoff(let handoff):
            if let seq = expected["seq"] as? Int { XCTAssertEqual(handoff.seq, seq, name) }
            if let edge = expected["edge"] as? String { XCTAssertEqual(handoff.edge, edge, name) }
            if let y = expected["y"] as? Double { XCTAssertEqual(handoff.y, y, accuracy: 1e-6, name) }
        case .pointerReturn(let ret):
            if let x = expected["x"] as? Double { XCTAssertEqual(ret.x, x, accuracy: 1e-6, name) }
            if let y = expected["y"] as? Double { XCTAssertEqual(ret.y, y, accuracy: 1e-6, name) }
        case .mouseDelta(let delta):
            if let seq = expected["seq"] as? Int { XCTAssertEqual(delta.seq, seq, name) }
            if let dx = expected["dx"] as? Double { XCTAssertEqual(delta.dx, dx, accuracy: 1e-6, name) }
            if let dy = expected["dy"] as? Double { XCTAssertEqual(delta.dy, dy, accuracy: 1e-6, name) }
        case .mouseButton(let button):
            if let seq = expected["seq"] as? Int { XCTAssertEqual(button.seq, seq, name) }
            if let b = expected["button"] as? String { XCTAssertEqual(button.button, b, name) }
            if let action = expected["action"] as? String { XCTAssertEqual(button.action, action, name) }
        case .mouseScroll(let scroll):
            if let seq = expected["seq"] as? Int { XCTAssertEqual(scroll.seq, seq, name) }
            if let dy = expected["dy"] as? Double { XCTAssertEqual(scroll.dy, dy, accuracy: 1e-6, name) }
        case .mediaSession(let session):
            if let token = expected["token"] as? String { XCTAssertEqual(session.token, token, name) }
            if let host = expected["host"] as? String { XCTAssertEqual(session.host, host, name) }
            if let port = expected["port"] as? Int { XCTAssertEqual(session.port, port, name) }
            if let codec = expected["codec"] as? String { XCTAssertEqual(session.codec, codec, name) }
            if let encrypted = expected["encrypted"] as? Bool {
                XCTAssertEqual(session.encrypted, encrypted, name)
            }
        case .mediaHello(let hello):
            if let token = expected["token"] as? String { XCTAssertEqual(hello.token, token, name) }
        case .codecSupport(let support):
            if let codecs = expected["codecs"] as? [String] { XCTAssertEqual(support.codecs, codecs, name) }
            if let lowLatency = expected["lowLatency"] as? Bool {
                XCTAssertEqual(support.lowLatency, lowLatency, name)
            } else {
                XCTAssertNil(support.lowLatency, name)
            }
        case .keyFrameRequest(let request):
            if let reason = expected["reason"] as? String { XCTAssertEqual(request.reason, reason, name) }
        case .latencyReport(let report):
            if let ms = expected["endToEndMs"] as? Double {
                XCTAssertEqual(report.endToEndMs, ms, accuracy: 1e-6, name)
            }
            if let fps = expected["decodedFps"] as? Double {
                XCTAssertEqual(report.decodedFps, fps, accuracy: 1e-6, name)
            }
            if let depth = expected["decodeQueueDepth"] as? Int {
                XCTAssertEqual(report.decodeQueueDepth, depth, name)
            }
        }
    }

    private func assertExpectedVideoSlice(
        name: String,
        frame: WindowMirrorFrame,
        expected: [String: Any]
    ) throws {
        XCTAssertThrowsError(try WindowMirrorProtocol.parse(frame), "\(name) must not parse as JSON")

        let payload = frame.payload
        XCTAssertGreaterThanOrEqual(payload.count, windowMirrorSliceHeaderBytes, name)
        let base = payload.startIndex
        func be(_ offset: Int, _ size: Int) -> UInt64 {
            let start = payload.index(base, offsetBy: offset)
            let end = payload.index(start, offsetBy: size)
            return payload[start..<end].reduce(UInt64(0)) { ($0 << 8) | UInt64($1) }
        }

        XCTAssertEqual(be(0, 8), UInt64(try XCTUnwrap(expected["sequence"] as? Int)), name)
        XCTAssertEqual(be(8, 8), UInt64(try XCTUnwrap(expected["ptsUs"] as? Int)), name)
        XCTAssertEqual(Int(be(16, 2)), try XCTUnwrap(expected["width"] as? Int), name)
        XCTAssertEqual(Int(be(18, 2)), try XCTUnwrap(expected["height"] as? Int), name)
        XCTAssertEqual(Int(be(20, 2)), try XCTUnwrap(expected["sliceIndex"] as? Int), name)
        XCTAssertEqual(Int(be(22, 2)), try XCTUnwrap(expected["sliceCount"] as? Int), name)

        let flags = UInt8(be(24, 1))
        XCTAssertEqual(
            flags & windowMirrorSliceFlagKeyFrame != 0,
            try XCTUnwrap(expected["isKeyFrame"] as? Bool),
            name
        )
        XCTAssertEqual(
            flags & windowMirrorSliceFlagFrameEnd != 0,
            try XCTUnwrap(expected["isFrameEnd"] as? Bool),
            name
        )
        XCTAssertEqual(UInt8(be(25, 1)), 0, "\(name) reserved byte")

        let bitstreamStart = payload.index(base, offsetBy: windowMirrorSliceHeaderBytes)
        XCTAssertEqual(
            Data(payload[bitstreamStart...]),
            try Data(hexString: try XCTUnwrap(expected["bitstreamHex"] as? String)),
            name
        )
    }

    private func acceptChunked(_ bytes: Data, chunkSizes: [Int]) throws -> [WindowMirrorFrame] {
        let decoder = WindowMirrorFrameDecoder()
        var out: [WindowMirrorFrame] = []
        var offset = 0
        for size in chunkSizes {
            if offset >= bytes.count { break }
            let next = min(offset + size, bytes.count)
            out += try decoder.accept(bytes.subdata(in: offset..<next))
            offset = next
        }
        if offset < bytes.count {
            out += try decoder.accept(bytes.subdata(in: offset..<bytes.count))
        }
        return out
    }

    private func loadJSONObject(resource: String) throws -> [String: Any] {
        let bundle = Bundle(for: WindowMirrorProtocolCompatibilityTests.self)
        // Fixtures are copied into the XCTest bundle (project.yml buildPhase: resources).
        // Prefer the bundle root; also accept a Fixtures/ subdirectory if XcodeGen
        // preserves the folder name.
        let url =
            bundle.url(forResource: resource, withExtension: "json")
            ?? bundle.url(forResource: resource, withExtension: "json", subdirectory: "Fixtures")
        guard let url else {
            let listed = bundle.urls(forResourcesWithExtension: "json", subdirectory: nil)?.map(\.lastPathComponent) ?? []
            throw NSError(
                domain: "WindowMirrorProtocolCompatibilityTests",
                code: 1,
                userInfo: [
                    NSLocalizedDescriptionKey:
                        "Missing test resource \(resource).json in \(bundle.bundlePath); found json: \(listed)",
                ]
            )
        }
        let data = try Data(contentsOf: url)
        let obj = try JSONSerialization.jsonObject(with: data)
        return try XCTUnwrap(obj as? [String: Any])
    }
}

private extension Array where Element == WindowMirrorFrame {
    var single: WindowMirrorFrame {
        precondition(count == 1)
        return self[0]
    }
}

private extension Data {
    init(hexString: String) throws {
        let cleaned = hexString.trimmingCharacters(in: .whitespacesAndNewlines)
        guard cleaned.count % 2 == 0 else {
            throw NSError(domain: "hex", code: 1, userInfo: [NSLocalizedDescriptionKey: "odd hex length"])
        }
        var data = Data(capacity: cleaned.count / 2)
        var index = cleaned.startIndex
        while index < cleaned.endIndex {
            let next = cleaned.index(index, offsetBy: 2)
            let byteStr = cleaned[index..<next]
            guard let byte = UInt8(byteStr, radix: 16) else {
                throw NSError(domain: "hex", code: 2, userInfo: [NSLocalizedDescriptionKey: "bad hex \(byteStr)"])
            }
            data.append(byte)
            index = next
        }
        self = data
    }
}
