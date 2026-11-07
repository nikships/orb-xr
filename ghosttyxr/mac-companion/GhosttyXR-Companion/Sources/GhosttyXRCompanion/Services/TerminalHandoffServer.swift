import Foundation
import Network
import os

private let handoffServerLogger = Logger(
    subsystem: "dev.ghosttyxr.companion",
    category: "TerminalHandoffServer"
)

/// Owns the always-on terminal-handoff listener for the companion's lifetime
/// (started at app launch, like the keyboard relay). When the headset asks to
/// hand its terminals off, the opener runs on the main queue — NSWorkspace
/// and NSAppleScript are main-thread API — and the result is written back on
/// the server queue.
final class MacTerminalHandoffController: ObservableObject {
    /// Cosmetic status for an optional menu line ("handoff ready").
    @Published private(set) var lastResultSummary: String?

    private let server = TerminalHandoffServer()
    private let opener: TerminalHandoffOpener

    init(opener: TerminalHandoffOpener = TerminalHandoffOpener()) {
        self.opener = opener
        // [weak self] on the outer closure: the server retains this handler
        // for its own lifetime, so a strong self here would cycle
        // controller → server → closure → controller and skip deinit.
        server.onOpenSessions = { [opener, weak self] sessions, completion in
            DispatchQueue.main.async {
                let outcome = opener.open(sessions: sessions)
                self?.lastResultSummary = outcome.ok
                    ? "Opened \(outcome.opened) terminal(s) from the headset."
                    : "Handoff failed: \(outcome.failed.first?.error ?? "unknown error")"
                completion(outcome)
            }
        }
        server.start()
    }

    deinit {
        server.stop()
    }
}

/// Loopback-only (`127.0.0.1:45875`) line-JSON listener the headset reaches
/// through its authenticated SSH `direct-tcpip` tunnel — the same transport
/// pattern as the keyboard relay, so no new LAN port and no new TCC prompt.
/// Single client at a time; a handoff turn is one short-lived connection.
final class TerminalHandoffServer {
    /// Invoked with the validated sessions; call the completion exactly once
    /// with the outcome to send `open_result` back to the headset.
    var onOpenSessions: (
        _ sessions: [TerminalHandoffSession],
        _ completion: @escaping (TerminalHandoffOpenOutcome) -> Void
    ) -> Void = { _, completion in
        completion(.failure([TerminalHandoffOpenFailure(name: "-", error: "handoff not wired")]))
    }

    private let queue = DispatchQueue(label: "dev.ghosttyxr.terminal-handoff.server")
    private var listener: NWListener?
    private var connection: NWConnection?
    private var receiveBuffer = Data()

    func start() {
        queue.async {
            guard self.listener == nil else { return }
            do {
                let parameters = NWParameters.tcp
                parameters.requiredLocalEndpoint = .hostPort(
                    host: .ipv4(.loopback),
                    port: NWEndpoint.Port(rawValue: TerminalHandoffProtocol.port)!
                )
                let listener = try NWListener(using: parameters)
                listener.newConnectionHandler = { [weak self] connection in
                    self?.queue.async {
                        self?.accept(connection)
                    }
                }
                listener.stateUpdateHandler = { state in
                    if case .failed(let error) = state {
                        handoffServerLogger.error(
                            "handoff listener failed: \(error.localizedDescription, privacy: .public)"
                        )
                    }
                }
                listener.start(queue: self.queue)
                self.listener = listener
            } catch {
                handoffServerLogger.error(
                    "handoff listener could not bind 127.0.0.1:\(TerminalHandoffProtocol.port): \(error.localizedDescription, privacy: .public)"
                )
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

    private func accept(_ newConnection: NWConnection) {
        // One handoff turn at a time; a second dialer is refused, matching
        // the keyboard relay's single-client policy.
        guard connection == nil else {
            newConnection.cancel()
            return
        }
        connection = newConnection
        receiveBuffer.removeAll(keepingCapacity: true)
        newConnection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .failed, .cancelled:
                self?.queue.async { self?.cancelConnection(newConnection) }
            default:
                break
            }
        }
        newConnection.start(queue: queue)
        receive(on: newConnection)
    }

    private func receive(on connection: NWConnection) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 8_192) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            self.queue.async {
                if let data, !data.isEmpty {
                    self.consume(data, on: connection)
                }
                if isComplete || error != nil {
                    self.cancelConnection(connection)
                } else if self.connection === connection {
                    self.receive(on: connection)
                }
            }
        }
    }

    private func consume(_ data: Data, on connection: NWConnection) {
        receiveBuffer.append(data)
        if receiveBuffer.count > TerminalHandoffProtocol.maxFrameBytes {
            send(
                TerminalHandoffProtocol.errorLineData(
                    message: "frame exceeded \(TerminalHandoffProtocol.maxFrameBytes) bytes"
                ),
                on: connection
            )
            cancelConnection(connection)
            return
        }
        while let newline = receiveBuffer.firstIndex(of: 0x0A) {
            let lineData = Data(receiveBuffer[..<newline])
            receiveBuffer.removeSubrange(receiveBuffer.startIndex...newline)
            guard !lineData.isEmpty else { continue }
            handleLine(lineData, on: connection)
        }
    }

    private func handleLine(_ lineData: Data, on connection: NWConnection) {
        let inbound: TerminalHandoffInbound
        do {
            inbound = try TerminalHandoffProtocol.decodeLine(lineData)
        } catch {
            let reason: String
            switch error {
            case TerminalHandoffProtocolError.unsupportedVersion(let version):
                reason = "unsupported protocol version \(version)"
            case TerminalHandoffProtocolError.invalidSessionName(let name):
                reason = "invalid session name \(name)"
            default:
                reason = "malformed handoff message"
            }
            send(TerminalHandoffProtocol.errorLineData(message: reason), on: connection)
            cancelConnection(connection)
            return
        }
        switch inbound {
        case .hello:
            send(TerminalHandoffProtocol.readyLineData(), on: connection)
        case .openSessions(let reqId, let sessions):
            onOpenSessions(sessions) { [weak self] outcome in
                self?.queue.async {
                    self?.send(
                        TerminalHandoffProtocol.openResultLineData(
                            reqId: reqId,
                            ok: outcome.ok,
                            opened: outcome.opened,
                            failed: outcome.failed
                        ),
                        on: connection
                    )
                }
            }
        }
    }

    private func send(_ data: Data, on connection: NWConnection) {
        guard self.connection === connection else { return }
        connection.send(content: data, completion: .contentProcessed { error in
            if let error {
                handoffServerLogger.error(
                    "handoff send failed: \(error.localizedDescription, privacy: .public)"
                )
            }
        })
    }

    private func cancelConnection(_ expected: NWConnection? = nil) {
        guard let current = connection else { return }
        if let expected, expected !== current { return }
        connection = nil
        receiveBuffer.removeAll(keepingCapacity: true)
        current.cancel()
    }
}
