import CoreMedia
import Foundation
import Network

/// Live health of the mirror stream, reported to the UI.
///
/// `endToEndMs` is measured by the headset (capture timestamp to composited
/// frame) and sent back over the control channel, rather than inferred from
/// sender-side RTT. It is the number the bitrate controller actually steers on.
struct WindowMirrorStreamStats: Equatable {
    var endToEndMs: Double = 0
    var decodedFps: Double = 0
    var decodeQueueDepth: Int = 0
    var bitrateBps: Int = 0
    var keyFrameRequests: Int = 0
}

/// Signaling and control server for window mirroring.
///
/// Control, input, and metadata ride the SSH-tunnelled bootstrap connection on
/// `windowMirrorRelayPort`. Video does not: it goes out a separate direct LAN
/// TCP socket owned by `WindowMirrorMediaServer`, because the SSH tunnel does one
/// synchronous JNI round-trip per read and cannot carry tens of Mbps.
final class WindowMirrorRelayServer {
    var onClientReadyChanged: (Bool) -> Void = { _ in }
    var onInput: (WindowMirrorInputMessage) -> Void = { _ in }
    var onPointerReturn: (WindowMirrorPointerReturnMessage) -> Void = { _ in }
    var onClose: (String?) -> Void = { _ in }
    var onError: (String) -> Void = { _ in }
    var onStats: (WindowMirrorStreamStats) -> Void = { _ in }

    private let queue = DispatchQueue(label: "dev.ghosttyxr.window-mirror.relay")
    private let bootstrapDecoder = WindowMirrorFrameDecoder()
    private let media = WindowMirrorMediaServer()
    private let encoder = WindowVideoEncoder()
    private let stateLock = NSLock()
    private var listener: NWListener?
    private var connection: NWConnection?
    private var bootstrapReady = false
    private var mediaReady = false
    private var sessionToken = ""
    private var encryptMedia = true
    private var preferredCodec: WindowMirrorVideoCodec = .h264
    private var headsetCodecs: [String] = ["h264"]
    /// Codec and cipher mode the headset was last told to expect. Nil until a
    /// media session has been announced on the current control connection.
    private var announcedCodec: WindowMirrorVideoCodec?
    private var announcedEncryption: Bool?
    private var stats = WindowMirrorStreamStats()

    init() {
        encoder.onFrame = { [weak self] frame in
            self?.media.send(frame)
        }
        encoder.onError = { [weak self] message in
            self?.onError(message)
        }
        media.onError = { [weak self] message in
            self?.onError(message)
        }
        media.onClientChanged = { [weak self] connected in
            guard let self else { return }
            self.stateLock.lock()
            let changed = self.mediaReady != connected
            self.mediaReady = connected
            self.stateLock.unlock()
            guard changed else { return }
            if connected {
                // The headset has no reference frames yet.
                self.encoder.forceKeyFrame()
            }
            self.onClientReadyChanged(connected)
        }
    }

    var hasReadyClient: Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return mediaReady
    }

    var activeTransportName: String { "direct-tcp" }

    func start() {
        queue.async {
            guard self.listener == nil else { return }
            do {
                let parameters = NWParameters.tcp
                if let tcp = parameters.defaultProtocolStack.transportProtocol as? NWProtocolTCP.Options {
                    tcp.noDelay = true
                }
                parameters.requiredLocalEndpoint = .hostPort(host: .ipv4(.loopback), port: windowMirrorRelayPort)
                let listener = try NWListener(using: parameters)
                listener.newConnectionHandler = { [weak self] connection in
                    self?.queue.async { self?.accept(connection) }
                }
                listener.stateUpdateHandler = { [weak self] state in
                    if case .failed(let error) = state {
                        self?.onError("Window mirror signaling listener failed: \(error.localizedDescription)")
                    }
                }
                listener.start(queue: self.queue)
                self.listener = listener
            } catch {
                self.onError("Window mirror signaling listener could not start: \(error.localizedDescription)")
            }
        }
    }

    func stop() {
        queue.async {
            self.listener?.cancel()
            self.listener = nil
            self.cancelConnection()
        }
    }

    func submit(_ sampleBuffer: CMSampleBuffer) {
        stateLock.lock()
        let ready = mediaReady
        stateLock.unlock()
        guard ready else { return }
        encoder.encode(sampleBuffer)
    }

    /// Applied to the live encoder session — no teardown, no renegotiation.
    func setBitrateCeiling(_ bitsPerSecond: Int) {
        stateLock.lock()
        stats.bitrateBps = bitsPerSecond
        let snapshot = stats
        stateLock.unlock()
        encoder.setBitrate(bitsPerSecond)
        onStats(snapshot)
    }

    /// User codec preference. Only takes effect if the headset advertised support
    /// for it; otherwise we stay on H.264 rather than sending a stream the
    /// headset cannot decode.
    func setPreferredCodec(_ codec: WindowMirrorVideoCodec) {
        stateLock.lock()
        preferredCodec = codec
        stateLock.unlock()
        queue.async { self.renegotiateMediaSession() }
    }

    /// AES-GCM on the direct media socket.
    func setEncryptMedia(_ encrypt: Bool) {
        stateLock.lock()
        encryptMedia = encrypt
        stateLock.unlock()
        queue.async { self.renegotiateMediaSession() }
    }

    /// Must be called with `stateLock` held.
    private func resolveCodecLocked() -> WindowMirrorVideoCodec {
        headsetCodecs.contains(preferredCodec.rawValue) ? preferredCodec : .h264
    }

    /// Re-arms the media socket and re-announces it whenever the codec or cipher
    /// mode the headset was told about stops matching what we would now send.
    ///
    /// Codec and cipher are fixed for the life of one announced media session:
    /// the headset configures its `MediaCodec` from `mediaSession.codec` and
    /// derives its AES key from `mediaSession.token`. Mutating either end in
    /// place left the headset decoding a stream it was not configured for, which
    /// is a permanently black pane — the infinite GOP means no unsolicited IDR
    /// ever arrives to break it out. Re-announcing makes both ends adopt the
    /// change at the same frame boundary. Must run on `queue`.
    private func renegotiateMediaSession() {
        stateLock.lock()
        let announced = bootstrapReady
        let target = connection
        let codec = resolveCodecLocked()
        let encrypt = encryptMedia
        let unchanged = announcedCodec == codec && announcedEncryption == encrypt
        stateLock.unlock()
        guard announced, !unchanged, let target else { return }
        announceMediaSession(codec: codec, encrypted: encrypt, to: target)
    }

    /// Arms a fresh media socket and tells the headset where to dial it. Drops
    /// any socket armed by a previous announcement, so the headset reattaches and
    /// gets an IDR before the first frame of the new configuration.
    private func announceMediaSession(
        codec: WindowMirrorVideoCodec,
        encrypted: Bool,
        to connection: NWConnection
    ) {
        guard let host = WindowMirrorMediaServer.preferredLocalAddress() else {
            // No silent fallback: guessing an address here would send the headset
            // to a host that is not us.
            onError("Could not determine a LAN address for the window mirror media socket.")
            sendJSON(
                .close,
                WindowMirrorCloseMessage(reason: "No LAN address available for window mirror video."),
                to: connection
            )
            connection.cancel()
            return
        }

        // Fresh token per announced session: it authenticates the direct media
        // socket and derives the media encryption key, and only ever travels
        // inside the SSH-tunnelled control channel.
        let token = UUID().uuidString
        stateLock.lock()
        sessionToken = token
        announcedCodec = codec
        announcedEncryption = encrypted
        stateLock.unlock()

        // Switch the encoder before the headset reattaches so the first keyframe
        // on the new socket already carries the new parameter sets.
        encoder.setCodec(codec)
        media.start(token: token, encrypted: encrypted)
        sendJSON(
            .mediaSession,
            WindowMirrorMediaSessionMessage(
                token: token,
                host: host,
                port: Int(windowMirrorMediaPort.rawValue),
                codec: codec.rawValue,
                encrypted: encrypted
            ),
            to: connection
        )
    }

    func sendStreamState(status: String, detail: String?, interactive: Bool, transport: String? = nil) {
        sendJSON(
            .streamState,
            WindowMirrorStreamStateMessage(
                status: status,
                detail: detail,
                interactive: interactive,
                transport: transport
            ),
            requireReady: false
        )
    }

    func sendMetadata(_ metadata: WindowMirrorMetadata) {
        sendJSON(.metadata, metadata, requireReady: true)
    }

    func sendClose(reason: String?) {
        sendJSON(.close, WindowMirrorCloseMessage(reason: reason), requireReady: false)
    }

    func sendMouseDelta(seq: Int, dx: Double, dy: Double) {
        sendJSON(.mouseDelta, WindowMirrorMouseDeltaMessage(seq: seq, dx: dx, dy: dy), requireReady: true)
    }

    func sendMouseButton(seq: Int, button: String, action: String) {
        sendJSON(
            .mouseButton,
            WindowMirrorMouseButtonMessage(seq: seq, button: button, action: action),
            requireReady: true
        )
    }

    func sendMouseScroll(seq: Int, dx: Double, dy: Double) {
        sendJSON(.mouseScroll, WindowMirrorMouseScrollMessage(seq: seq, dx: dx, dy: dy), requireReady: true)
    }

    func sendPointerHandoff(seq: Int, edge: String, y: Double) {
        sendJSON(
            .pointerHandoff,
            WindowMirrorPointerHandoffMessage(seq: seq, edge: edge, y: y),
            requireReady: true
        )
    }

    func notifyPointerReturned() {
        sendJSON(.pointerReturn, WindowMirrorPointerReturnMessage(x: 0, y: 0), requireReady: true)
    }

    private func accept(_ newConnection: NWConnection) {
        stateLock.lock()
        let reject = connection != nil
        if !reject {
            connection = newConnection
            bootstrapReady = false
            mediaReady = false
        }
        stateLock.unlock()
        if reject {
            newConnection.cancel()
            return
        }
        bootstrapDecoder.reset()
        onClientReadyChanged(false)
        newConnection.stateUpdateHandler = { [weak self, weak newConnection] state in
            guard let self, let newConnection else { return }
            if case .failed(let error) = state {
                self.onError("Window mirror signaling failed: \(error.localizedDescription)")
                self.queue.async { self.cancel(newConnection) }
            } else if case .cancelled = state {
                self.queue.async { self.cancel(newConnection) }
            }
        }
        newConnection.start(queue: queue)
        receive(on: newConnection)
    }

    private func receive(on connection: NWConnection) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            self.queue.async {
                if let data, !data.isEmpty {
                    do {
                        for frame in try self.bootstrapDecoder.accept(data) {
                            self.handleBootstrap(frame, connection: connection)
                        }
                    } catch {
                        self.onError(error.localizedDescription)
                        self.cancel(connection)
                        return
                    }
                }
                if isComplete || error != nil {
                    self.cancel(connection)
                } else {
                    self.receive(on: connection)
                }
            }
        }
    }

    private func handleBootstrap(_ frame: WindowMirrorFrame, connection: NWConnection) {
        let message: WindowMirrorMessage
        do {
            message = try WindowMirrorProtocol.parse(frame)
        } catch {
            onError(error.localizedDescription)
            return
        }
        switch message {
        case .hello(let hello):
            handleHello(hello, connection: connection)
        case .codecSupport(let support):
            stateLock.lock()
            headsetCodecs = support.codecs
            stateLock.unlock()
            // May resolve the user's preference to a codec we have not announced
            // yet (HEVC becoming available), which is a full re-announce, not an
            // in-place encoder switch.
            renegotiateMediaSession()
        case .keyFrameRequest:
            stateLock.lock()
            stats.keyFrameRequests += 1
            let snapshot = stats
            stateLock.unlock()
            // The GOP is infinite, so this is the only source of IDRs after the
            // first. Recovering a broken reference chain costs one frame, not a
            // renegotiation.
            encoder.forceKeyFrame()
            onStats(snapshot)
        case .latencyReport(let report):
            stateLock.lock()
            stats.endToEndMs = report.endToEndMs
            stats.decodedFps = report.decodedFps
            stats.decodeQueueDepth = report.decodeQueueDepth
            let snapshot = stats
            stateLock.unlock()
            onStats(snapshot)
        case .input(let input):
            onInput(input)
        case .pointerReturn(let pointerReturn):
            onPointerReturn(pointerReturn)
        case .ping:
            send(WindowMirrorProtocol.frame(type: .pong), to: connection)
        case .close(let reason):
            onClose(reason)
        default:
            break
        }
    }

    private func handleHello(_ hello: WindowMirrorHello, connection: NWConnection) {
        let version = hello.resolvedVersion
        guard WindowMirrorProtocol.isFeatureCompatible(version) else {
            stateLock.lock()
            bootstrapReady = false
            stateLock.unlock()
            sendJSON(.close, WindowMirrorCloseMessage(reason: windowMirrorFeatureUpgradeReason), to: connection)
            connection.cancel()
            return
        }

        stateLock.lock()
        bootstrapReady = true
        let encrypt = encryptMedia
        let codec = resolveCodecLocked()
        stateLock.unlock()

        sendJSON(
            .hello,
            WindowMirrorHello(role: "mac", app: "ghosttyxr-companion", version: windowMirrorFeatureVersion),
            to: connection
        )
        announceMediaSession(codec: codec, encrypted: encrypt, to: connection)
    }

    private func sendJSON<T: Encodable>(_ type: WindowMirrorFrameType, _ value: T, requireReady: Bool) {
        guard let data = WindowMirrorProtocol.jsonFrame(type, value) else { return }
        stateLock.lock()
        let target = connection
        let canSend = bootstrapReady && (!requireReady || mediaReady)
        stateLock.unlock()
        guard canSend, let target else { return }
        send(data, to: target)
    }

    private func sendJSON<T: Encodable>(_ type: WindowMirrorFrameType, _ value: T, to connection: NWConnection) {
        guard let data = WindowMirrorProtocol.jsonFrame(type, value) else { return }
        send(data, to: connection)
    }

    private func send(_ data: Data, to connection: NWConnection) {
        connection.send(content: data, completion: .contentProcessed { [weak self] error in
            if let error {
                self?.onError("Window mirror signaling send failed: \(error.localizedDescription)")
            }
        })
    }

    private func cancel(_ connection: NWConnection) {
        stateLock.lock()
        let isCurrentConnection = self.connection === connection
        stateLock.unlock()
        guard isCurrentConnection else { return }
        cancelConnection()
    }

    private func cancelConnection() {
        stateLock.lock()
        let oldConnection = connection
        connection = nil
        bootstrapReady = false
        mediaReady = false
        sessionToken = ""
        announcedCodec = nil
        announcedEncryption = nil
        stateLock.unlock()
        bootstrapDecoder.reset()
        media.stop()
        encoder.stop()
        oldConnection?.cancel()
        onClientReadyChanged(false)
    }
}
