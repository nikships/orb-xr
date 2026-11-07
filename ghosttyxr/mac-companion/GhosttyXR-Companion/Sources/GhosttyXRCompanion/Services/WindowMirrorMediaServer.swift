import CryptoKit
import Foundation
import Network
import OSLog

/// Direct LAN TCP socket carrying encoded video to the headset.
///
/// Deliberately has no jitter buffer, no pacer, and no congestion controller.
/// Latency stays bounded because very little is ever in flight — the encoder's
/// `DataRateLimits` window caps burst size and each slice is written and flushed
/// the moment it is produced — rather than because a buffer absorbs jitter. A
/// growing buffer is exactly what made the previous WebRTC path drift and freeze.
final class WindowMirrorMediaServer {
    var onError: (String) -> Void = { _ in }
    /// Fires when the headset attaches or detaches the media socket.
    var onClientChanged: (Bool) -> Void = { _ in }

    private let logger = Logger(subsystem: "dev.ghosttyxr.companion", category: "WindowMirrorMedia")
    private let queue = DispatchQueue(label: "dev.ghosttyxr.window-mirror.media")
    private let stateLock = NSLock()

    private var listener: NWListener?
    private var connection: NWConnection?
    private var decoder = WindowMirrorFrameDecoder()
    private var authenticated = false

    private var token = ""
    private var encryptionKey: SymmetricKey?
    private var sequence: UInt64 = 0

    /// Bytes queued in the socket beyond which we drop non-keyframe slices.
    /// If this trips we are already behind, and sending more only adds latency.
    private static let maxPendingBytes = 4 * 1024 * 1024
    private var pendingBytes = 0

    var hasClient: Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return authenticated
    }

    /// Begins listening and arms the session token. `encryptionKey` is nil when
    /// the user has disabled LAN media encryption.
    func start(token: String, encrypted: Bool) {
        queue.async {
            // Drop only the client, not the listener. Re-arming happens on every
            // codec/encryption change, and the headset redials immediately.
            if let old = self.connection {
                self.dropConnection(old)
            }
            self.stateLock.lock()
            self.token = token
            // Deriving the key from the token means no second secret to ship;
            // the token itself only ever travels inside the SSH control channel.
            self.encryptionKey = encrypted
                ? SymmetricKey(data: SHA256.hash(data: Data(token.utf8)))
                : nil
            self.sequence = 0
            self.stateLock.unlock()
            self.ensureListener()
        }
    }

    /// Binds the media listener if it is not already up. Must run on `queue`.
    ///
    /// The listener is deliberately **reused** across re-arms. It binds a fixed
    /// port, and `NWListener.cancel()` releases that port asynchronously — so
    /// cancelling and immediately rebinding loses the race and fails with
    /// EADDRINUSE, leaving the headset dialling a port nothing is listening on.
    /// Only `stop()` tears the listener down.
    private func ensureListener() {
        guard listener == nil else { return }
        do {
            let parameters = NWParameters.tcp
            if let tcp = parameters.defaultProtocolStack.transportProtocol as? NWProtocolTCP.Options {
                // Nagle off: a slice must go out the instant it is written.
                tcp.noDelay = true
                tcp.connectionTimeout = 5
            }
            parameters.allowLocalEndpointReuse = true
            let listener = try NWListener(
                using: parameters,
                on: windowMirrorMediaPort
            )
            listener.newConnectionHandler = { [weak self] connection in
                self?.queue.async { self?.accept(connection) }
            }
            listener.stateUpdateHandler = { [weak self] state in
                guard let self, case .failed(let error) = state else { return }
                // Release the dead listener so the next arm rebinds instead of
                // skipping setup because a failed one is still held.
                self.queue.async {
                    self.listener?.cancel()
                    self.listener = nil
                }
                self.onError("Window mirror media listener failed: \(error.localizedDescription)")
            }
            listener.start(queue: queue)
            self.listener = listener
            logger.info("media listener up on \(windowMirrorMediaPort.rawValue, privacy: .public)")
        } catch {
            onError("Window mirror media listener could not start: \(error.localizedDescription)")
        }
    }

    func stop() {
        queue.async { self.stopLocked() }
    }

    /// Writes one encoded frame as a run of slices, flushing each immediately.
    func send(_ frame: EncodedWindowFrame) {
        queue.async {
            self.stateLock.lock()
            let ready = self.authenticated
            let key = self.encryptionKey
            let backlogged = self.pendingBytes > Self.maxPendingBytes
            self.stateLock.unlock()
            guard ready, let connection = self.connection else { return }

            // Keyframes bypass the backlog check: dropping one strands the
            // headset until the next IDR request, which is strictly worse than
            // the queueing it would have caused.
            if backlogged, !frame.isKeyFrame {
                // Burn the sequence numbers this frame would have used. The gap is
                // what tells the headset its reference chain is broken; staying
                // contiguous would leave it decoding against a frame it never got.
                self.stateLock.lock()
                self.sequence += UInt64(frame.slices.count)
                self.stateLock.unlock()
                return
            }

            let sliceCount = frame.slices.count
            for (index, slice) in frame.slices.enumerated() {
                self.stateLock.lock()
                self.sequence += 1
                let sequence = self.sequence
                self.stateLock.unlock()

                let payload: Data
                if let key {
                    guard let sealed = Self.seal(slice, key: key, sequence: sequence) else {
                        self.onError("Window mirror media encryption failed.")
                        return
                    }
                    payload = sealed
                } else {
                    payload = slice
                }

                let data = WindowMirrorProtocol.videoSliceFrame(
                    sequence: sequence,
                    ptsUs: frame.ptsUs,
                    width: frame.width,
                    height: frame.height,
                    sliceIndex: index,
                    sliceCount: sliceCount,
                    isKeyFrame: frame.isKeyFrame,
                    isFrameEnd: index == sliceCount - 1,
                    bitstream: payload
                )
                self.write(data, to: connection)
            }
        }
    }

    private func write(_ data: Data, to connection: NWConnection) {
        stateLock.lock()
        pendingBytes += data.count
        stateLock.unlock()
        connection.send(content: data, completion: .contentProcessed { [weak self] error in
            guard let self else { return }
            self.stateLock.lock()
            self.pendingBytes = max(0, self.pendingBytes - data.count)
            self.stateLock.unlock()
            if let error {
                self.onError("Window mirror media send failed: \(error.localizedDescription)")
                self.queue.async { self.dropConnection(connection) }
            }
        })
    }

    /// AES-GCM with the sequence number as the nonce, so no nonce ever repeats
    /// under a given session key.
    private static func seal(_ plaintext: Data, key: SymmetricKey, sequence: UInt64) -> Data? {
        var nonceBytes = Data(count: 4)
        nonceBytes.append(contentsOf: withUnsafeBytes(of: sequence.bigEndian) { Array($0) })
        guard let nonce = try? AES.GCM.Nonce(data: nonceBytes),
              let sealed = try? AES.GCM.seal(plaintext, using: key, nonce: nonce)
        else {
            return nil
        }
        return sealed.ciphertext + sealed.tag
    }

    private func accept(_ newConnection: NWConnection) {
        // One headset at a time; a second attempt is a stale reconnect.
        if connection != nil {
            newConnection.cancel()
            return
        }
        connection = newConnection
        decoder.reset()
        stateLock.lock()
        authenticated = false
        pendingBytes = 0
        stateLock.unlock()

        newConnection.stateUpdateHandler = { [weak self, weak newConnection] state in
            guard let self, let newConnection else { return }
            switch state {
            case .failed(let error):
                self.onError("Window mirror media connection failed: \(error.localizedDescription)")
                self.queue.async { self.dropConnection(newConnection) }
            case .cancelled:
                self.queue.async { self.dropConnection(newConnection) }
            default:
                break
            }
        }
        newConnection.start(queue: queue)
        receive(on: newConnection)
    }

    private func receive(on connection: NWConnection) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 16 * 1024) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            self.queue.async {
                if let data, !data.isEmpty {
                    do {
                        for frame in try self.decoder.accept(data) {
                            try self.handle(frame, on: connection)
                        }
                    } catch {
                        self.onError(error.localizedDescription)
                        self.dropConnection(connection)
                        return
                    }
                }
                if isComplete || error != nil {
                    self.dropConnection(connection)
                } else {
                    self.receive(on: connection)
                }
            }
        }
    }

    private func handle(_ frame: WindowMirrorFrame, on connection: NWConnection) throws {
        guard frame.type == .mediaHello else { return }
        let hello = try WindowMirrorProtocol.parseMediaHello(frame.payload)
        stateLock.lock()
        let expected = token
        stateLock.unlock()
        // Constant-time compare so a wrong token cannot be probed byte by byte.
        guard !expected.isEmpty, Self.constantTimeEquals(hello.token, expected) else {
            onError("Window mirror media client presented an invalid token.")
            connection.cancel()
            return
        }
        stateLock.lock()
        authenticated = true
        stateLock.unlock()
        logger.info("media client authenticated")
        onClientChanged(true)
    }

    private static func constantTimeEquals(_ lhs: String, _ rhs: String) -> Bool {
        let a = Array(lhs.utf8)
        let b = Array(rhs.utf8)
        guard a.count == b.count else { return false }
        var diff: UInt8 = 0
        for i in 0..<a.count {
            diff |= a[i] ^ b[i]
        }
        return diff == 0
    }

    private func dropConnection(_ target: NWConnection) {
        guard connection === target else { return }
        connection = nil
        decoder.reset()
        stateLock.lock()
        let wasAuthenticated = authenticated
        authenticated = false
        pendingBytes = 0
        stateLock.unlock()
        target.cancel()
        if wasAuthenticated {
            onClientChanged(false)
        }
    }

    /// The IPv4 address the headset should dial for video.
    ///
    /// Returns nil rather than guessing when there is no usable interface — the
    /// caller surfaces a real error instead of sending the headset somewhere.
    static func preferredLocalAddress() -> String? {
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0, let head else { return nil }
        defer { freeifaddrs(head) }

        var fallback: String?
        var pointer: UnsafeMutablePointer<ifaddrs>? = head
        while let current = pointer {
            pointer = current.pointee.ifa_next
            guard let addr = current.pointee.ifa_addr,
                  addr.pointee.sa_family == UInt8(AF_INET)
            else { continue }
            let flags = Int32(current.pointee.ifa_flags)
            guard flags & IFF_UP != 0, flags & IFF_LOOPBACK == 0 else { continue }

            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard getnameinfo(
                addr,
                socklen_t(addr.pointee.sa_len),
                &host,
                socklen_t(host.count),
                nil,
                0,
                NI_NUMERICHOST
            ) == 0 else { continue }
            let address = String(cString: host)
            // 169.254/16 means DHCP never answered; it will not route.
            if address.hasPrefix("169.254.") { continue }

            let name = String(cString: current.pointee.ifa_name)
            // en0/en1 are Wi-Fi and Ethernet; prefer them over utun/bridge/awdl
            // virtual interfaces, which the headset usually cannot reach.
            if name == "en0" || name == "en1" {
                return address
            }
            if fallback == nil, name.hasPrefix("en") {
                fallback = address
            }
        }
        return fallback
    }

    private func stopLocked() {
        listener?.cancel()
        listener = nil
        let old = connection
        connection = nil
        decoder.reset()
        stateLock.lock()
        let wasAuthenticated = authenticated
        authenticated = false
        pendingBytes = 0
        sequence = 0
        stateLock.unlock()
        old?.cancel()
        if wasAuthenticated {
            onClientChanged(false)
        }
    }
}
