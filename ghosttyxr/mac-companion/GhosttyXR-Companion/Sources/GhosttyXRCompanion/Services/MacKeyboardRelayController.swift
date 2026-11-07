import AppKit
import ApplicationServices
import CoreGraphics
import Foundation
import Network
import os
import SwiftUI

private let keyboardRelayPort: NWEndpoint.Port = 45_873
private let keyboardRelayProtocolVersion = 1
private let keyboardRelayMaxFrameBytes = 16 * 1024
private let keyboardRelayMaxQueuedEvents = 256
private let keyboardRelayLogger = Logger(subsystem: "dev.ghosttyxr.companion", category: "KeyboardRelay")

enum MacKeyboardRelayStatus: Equatable {
    case waitingForHeadset
    case armedWaitingForHeadset
    case ready
    case capturing
    case permissionNeeded
    case error(String)
}

struct MacKeyboardRelayState: Equatable {
    var status: MacKeyboardRelayStatus = .waitingForHeadset
    var detail: String = "Waiting for the headset to open the SSH keyboard tunnel."
}

final class MacKeyboardRelayController: ObservableObject {
    @Published private(set) var state = MacKeyboardRelayState()
    @Published private(set) var captureArmed = false
    @Published private(set) var headsetConnected = false

    /// Symbiosis: while the mouse pointer is owned by XR, the keyboard follows it —
    /// capture runs regardless of the manual toggle and stops when the pointer returns.
    private var symbiosisActive = false

    private let server = KeyboardRelayServer()
    private let capture = KeyboardCaptureService()
    private let shortcutCapture = KeyboardShortcutCaptureService()
    private var sequence = 0
    // The relay uses an ACTIVE event tap (.defaultTap) so captured keys are suppressed
    // locally. Active keyboard taps are gated by Accessibility (AXIsProcessTrusted),
    // not Input Monitoring — preflighting ListenEvent access here caused taps to be
    // "blocked by macOS" even after the user granted Input Monitoring.
    private var permissionGranted = AXIsProcessTrusted()
    private var activationObserver: NSObjectProtocol?

    init() {
        server.onClientReadyChanged = { [weak self] ready in
            DispatchQueue.main.async {
                self?.clientReadyChanged(ready)
            }
        }
        server.onError = { [weak self] message in
            DispatchQueue.main.async {
                self?.fail(message)
            }
        }
        server.onOverflow = { [weak self] in
            DispatchQueue.main.async {
                self?.disarm(reason: "Keyboard relay queue overflowed; local Mac keyboard delivery restored.")
            }
        }
        server.start()
        publishState(reason: nil)
        reconcileShortcutCapture()

        // Re-check Input Monitoring when the app regains focus (e.g. the user just
        // granted it in System Settings) so armed forwarding resumes without the
        // user needing to click "Start forwarding" again.
        activationObserver = NotificationCenter.default.addObserver(
            forName: NSApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.publishState(reason: nil)
            self?.reconcileCapture()
        }

    }

    deinit {
        server.stop()
        capture.stop()
        shortcutCapture.stop()
        if let activationObserver {
            NotificationCenter.default.removeObserver(activationObserver)
        }
    }

    var statusLabel: String {
        switch state.status {
        case .waitingForHeadset: return "keyboard waiting"
        case .armedWaitingForHeadset: return "keyboard armed"
        case .ready: return "keyboard ready"
        case .capturing: return "keyboard forwarding"
        case .permissionNeeded: return "permission needed"
        case .error: return "keyboard error"
        }
    }

    var statusSymbol: String {
        switch state.status {
        case .capturing: return "keyboard.badge.eye"
        case .ready, .armedWaitingForHeadset: return "keyboard"
        case .permissionNeeded: return "hand.raised"
        case .error: return "exclamationmark.triangle"
        case .waitingForHeadset: return "keyboard.onehanded.left"
        }
    }

    var statusTint: Color {
        switch state.status {
        case .capturing: return GhosttyTheme.neonGreen
        case .ready, .armedWaitingForHeadset: return GhosttyTheme.neonCyan
        case .permissionNeeded, .error: return GhosttyTheme.neonAmber
        case .waitingForHeadset: return GhosttyTheme.dimText
        }
    }

    func toggleArmed() {
        if captureArmed {
            disarm(reason: "Keyboard forwarding stopped.")
            return
        }
        captureArmed = true
        permissionGranted = AXIsProcessTrusted()
        if !permissionGranted {
            // Prompt for Accessibility — this both shows the system dialog and adds
            // the app to the Accessibility list so the user can flip the toggle.
            NSApp.activate(ignoringOtherApps: true)
            let options = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
            let trusted = AXIsProcessTrustedWithOptions(options)
            permissionGranted = trusted
            keyboardRelayLogger.info(
                "Accessibility request completed, trusted: \(trusted, privacy: .public)"
            )
        }
        if !permissionGranted {
            capture.stop()
            publishState(reason: "Grant Accessibility to Orb XR, then return here to resume forwarding.")
            sendCaptureState(reason: "Accessibility permission is missing.")
            reconcileShortcutCapture()
            return
        }
        publishState(reason: nil)
        reconcileCapture()
    }

    func openInputMonitoringSettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility") {
            NSWorkspace.shared.open(url)
        }
    }

    /// Symbiosis keyboard-follows-pointer: called by the app when full-screen-share
    /// pointer ownership changes. `true` while XR owns the pointer.
    func setSymbiosisCapture(_ active: Bool) {
        guard symbiosisActive != active else { return }
        symbiosisActive = active
        publishState(reason: active
            ? "Symbiosis: Mac keyboard follows the pointer into XR."
            : nil)
        reconcileCapture()
    }

    private func clientReadyChanged(_ ready: Bool) {
        headsetConnected = ready
        if ready {
            sequence = 0
        } else {
            capture.stop()
        }
        publishState(reason: nil)
        reconcileCapture()
    }

    private func reconcileCapture() {
        guard captureArmed || symbiosisActive else {
            capture.stop()
            sendCaptureState(reason: "Keyboard forwarding is stopped.")
            reconcileShortcutCapture()
            return
        }
        permissionGranted = AXIsProcessTrusted()
        guard permissionGranted else {
            capture.stop()
            publishState(reason: "Grant Accessibility to Orb XR.")
            sendCaptureState(reason: "Accessibility permission is missing.")
            reconcileShortcutCapture()
            return
        }
        guard server.hasReadyClient else {
            capture.stop()
            sendCaptureState(reason: "Waiting for headset keyboard tunnel.")
            reconcileShortcutCapture()
            return
        }
        if capture.isCapturing {
            sendCaptureState(reason: "Forwarding Mac keyboard to headset.")
            return
        }
        shortcutCapture.stop()
        let started = capture.start(
            onEvent: { [weak self] event in
                guard let self else { return false }
                return self.send(event: event)
            },
            onEmergencyStop: { [weak self] in
                DispatchQueue.main.async {
                    self?.disarm(reason: "Emergency stop chord pressed.")
                    self?.onEmergencyStopped()
                }
            },
            onPointerOwnershipToggle: { [weak self] in
                DispatchQueue.main.async {
                    self?.onPointerOwnershipToggle()
                }
            },
            onDisabled: { [weak self] reason in
                DispatchQueue.main.async {
                    self?.disarm(reason: reason)
                }
            }
        )
        if started {
            publishState(reason: "Forwarding Mac keyboard to focused XR terminal.")
            sendCaptureState(reason: "Forwarding Mac keyboard to headset.")
        } else {
            captureArmed = false
            capture.stop()
            publishState(reason: "macOS blocked the event tap. Grant Accessibility, then restart forwarding.")
            sendCaptureState(reason: "Event tap could not start.")
            reconcileShortcutCapture()
        }
    }

    private func reconcileShortcutCapture() {
        guard !capture.isCapturing, permissionGranted else {
            shortcutCapture.stop()
            return
        }
        _ = shortcutCapture.start { [weak self] in
            DispatchQueue.main.async {
                self?.toggleArmed()
            }
        }
    }

    private func send(event: KeyboardRelayKeyEvent) -> Bool {
        sequence += 1
        var message = KeyboardRelayMessage(v: keyboardRelayProtocolVersion, type: "key")
        message.seq = sequence
        message.code = event.code
        message.text = event.text
        message.mods = event.mods
        message.action = event.action
        return server.send(message)
    }

    /// Fires when the user hits the emergency-stop chord; the app wires this to
    /// force Symbiosis pointer ownership back to the Mac too.
    var onEmergencyStopped: () -> Void = {}

    /// Fires when the user hits Control+Option+Command+M while keyboard capture is
    /// active (e.g. XR owns the pointer). Wired to Symbiosis pointer ownership toggle.
    var onPointerOwnershipToggle: () -> Void = {}

    private func disarm(reason: String) {
        captureArmed = false
        symbiosisActive = false
        capture.stop()
        publishState(reason: reason)
        sendCaptureState(reason: reason)
        reconcileShortcutCapture()
    }

    private func fail(_ message: String) {
        capture.stop()
        state = MacKeyboardRelayState(status: .error(message), detail: message)
        sendCaptureState(reason: message)
        reconcileShortcutCapture()
    }

    private func publishState(reason: String?) {
        permissionGranted = AXIsProcessTrusted()
        let wantsCapture = captureArmed || symbiosisActive
        if !permissionGranted && wantsCapture {
            state = MacKeyboardRelayState(
                status: .permissionNeeded,
                detail: reason ?? "Grant Accessibility to capture and suppress Mac keyboard events."
            )
        } else if wantsCapture && capture.isCapturing {
            state = MacKeyboardRelayState(
                status: .capturing,
                detail: reason ?? "Mac keyboard input is forwarding exclusively to the headset."
            )
        } else if captureArmed && !server.hasReadyClient {
            state = MacKeyboardRelayState(
                status: .armedWaitingForHeadset,
                detail: reason ?? "Armed. Capture starts when the headset opens the SSH tunnel."
            )
        } else if server.hasReadyClient {
            state = MacKeyboardRelayState(
                status: .ready,
                detail: reason ?? "Headset connected. Start forwarding to capture this Mac keyboard."
            )
        } else {
            state = MacKeyboardRelayState(
                status: .waitingForHeadset,
                detail: reason ?? "Waiting for the headset to open the SSH keyboard tunnel."
            )
        }
    }

    private func sendCaptureState(reason: String?) {
        var message = KeyboardRelayMessage(v: keyboardRelayProtocolVersion, type: "capture_state")
        message.armed = captureArmed
        message.capturing = capture.isCapturing
        message.permission = captureArmed ? (permissionGranted ? "granted" : "missing") : "not_required"
        message.reason = reason
        _ = server.send(message, requireReady: false)
    }

}

private struct KeyboardRelayMessage: Codable {
    var v: Int
    var type: String
    var seq: Int?
    var code: String?
    var text: String?
    var mods: Int?
    var action: String?
    var armed: Bool?
    var capturing: Bool?
    var permission: String?
    var reason: String?
    var role: String?
    var app: String?
}

private final class KeyboardRelayServer {
    var onClientReadyChanged: (Bool) -> Void = { _ in }
    var onError: (String) -> Void = { _ in }
    var onOverflow: () -> Void = {}

    private let queue = DispatchQueue(label: "dev.ghosttyxr.keyboard-relay.server")
    private let decoder = JSONDecoder()
    private var listener: NWListener?
    private var receiveBuffer = Data()

    private let stateLock = NSLock()
    private var connection: NWConnection?
    private var ready = false
    private var pendingSends = 0

    var hasReadyClient: Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return ready
    }

    func start() {
        queue.async {
            guard self.listener == nil else { return }
            do {
                let parameters = NWParameters.tcp
                parameters.requiredLocalEndpoint = .hostPort(host: .ipv4(.loopback), port: keyboardRelayPort)
                let listener = try NWListener(using: parameters)
                listener.newConnectionHandler = { [weak self] connection in
                    self?.queue.async {
                        self?.accept(connection)
                    }
                }
                listener.stateUpdateHandler = { [weak self] state in
                    if case .failed(let error) = state {
                        self?.onError("Keyboard relay listener failed: \(error.localizedDescription)")
                    }
                }
                listener.start(queue: self.queue)
                self.listener = listener
            } catch {
                self.onError("Keyboard relay listener could not bind 127.0.0.1:\(keyboardRelayPort): \(error.localizedDescription)")
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

    @discardableResult
    func send(_ message: KeyboardRelayMessage, requireReady: Bool = true) -> Bool {
        let encoder = JSONEncoder()
        guard let payload = try? encoder.encode(message) else { return false }
        var data = payload
        data.append(0x0A)

        stateLock.lock()
        let hasConnection = connection != nil
        let overflow = hasConnection && pendingSends >= keyboardRelayMaxQueuedEvents
        let canSend = hasConnection && (!requireReady || ready) && !overflow
        let target = connection
        if canSend {
            pendingSends += 1
        }
        stateLock.unlock()

        guard canSend, let target else {
            if overflow {
                onOverflow()
            }
            return false
        }
        target.send(content: data, completion: .contentProcessed { [weak self] error in
            guard let self else { return }
            self.stateLock.lock()
            self.pendingSends = max(0, self.pendingSends - 1)
            self.stateLock.unlock()
            if let error {
                self.onError("Keyboard relay send failed: \(error.localizedDescription)")
            }
        })
        return true
    }

    private func accept(_ newConnection: NWConnection) {
        stateLock.lock()
        if connection != nil {
            stateLock.unlock()
            newConnection.cancel()
            return
        }
        connection = newConnection
        ready = false
        pendingSends = 0
        stateLock.unlock()
        receiveBuffer.removeAll(keepingCapacity: true)
        onClientReadyChanged(false)

        newConnection.stateUpdateHandler = { [weak self] state in
            if case .failed(let error) = state {
                self?.onError("Keyboard relay connection failed: \(error.localizedDescription)")
                self?.queue.async { self?.cancelConnection() }
            } else if case .cancelled = state {
                self?.queue.async { self?.cancelConnection() }
            }
        }
        newConnection.start(queue: queue)
        receive(on: newConnection)
    }

    private func receive(on connection: NWConnection) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 4_096) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            self.queue.async {
                if let data, !data.isEmpty {
                    self.consume(data)
                }
                if isComplete || error != nil {
                    self.cancelConnection()
                } else {
                    self.receive(on: connection)
                }
            }
        }
    }

    private func consume(_ data: Data) {
        receiveBuffer.append(data)
        if receiveBuffer.count > keyboardRelayMaxFrameBytes {
            receiveBuffer.removeAll(keepingCapacity: true)
            onError("Keyboard relay frame exceeded \(keyboardRelayMaxFrameBytes) bytes.")
            cancelConnection()
            return
        }

        while let newline = receiveBuffer.firstIndex(of: 0x0A) {
            let lineData = receiveBuffer[..<newline]
            receiveBuffer.removeSubrange(receiveBuffer.startIndex...newline)
            guard !lineData.isEmpty else { continue }
            guard let message = try? decoder.decode(KeyboardRelayMessage.self, from: Data(lineData)) else {
                onError("Keyboard relay received malformed JSON.")
                cancelConnection()
                return
            }
            handle(message)
        }
    }

    private func handle(_ message: KeyboardRelayMessage) {
        guard message.v == keyboardRelayProtocolVersion else {
            onError("Keyboard relay protocol version mismatch.")
            cancelConnection()
            return
        }
        switch message.type {
        case "hello":
            var response = KeyboardRelayMessage(v: keyboardRelayProtocolVersion, type: "hello")
            response.role = "mac"
            response.app = "ghosttyxr-companion"
            _ = send(response, requireReady: false)
        case "ready":
            stateLock.lock()
            ready = true
            stateLock.unlock()
            onClientReadyChanged(true)
        case "ping":
            _ = send(KeyboardRelayMessage(v: keyboardRelayProtocolVersion, type: "pong"), requireReady: false)
        case "pong", "reset":
            break
        default:
            onError("Keyboard relay received unsupported message type \(message.type).")
        }
    }

    private func cancelConnection() {
        stateLock.lock()
        let old = connection
        connection = nil
        ready = false
        pendingSends = 0
        stateLock.unlock()
        receiveBuffer.removeAll(keepingCapacity: true)
        old?.cancel()
        onClientReadyChanged(false)
    }
}

private struct KeyboardRelayKeyEvent {
    var code: String
    var text: String?
    var mods: Int
    var action: String
}

private final class KeyboardShortcutCaptureService {
    private var eventTap: CFMachPort?
    private var runLoopSource: CFRunLoopSource?
    private var onToggle: (() -> Void)?

    func start(onToggle: @escaping () -> Void) -> Bool {
        guard eventTap == nil else { return true }
        self.onToggle = onToggle

        let mask =
            (1 << CGEventType.keyDown.rawValue) |
            (1 << CGEventType.keyUp.rawValue)
        guard let tap = CGEvent.tapCreate(
            tap: .cgSessionEventTap,
            place: .headInsertEventTap,
            options: .defaultTap,
            eventsOfInterest: CGEventMask(mask),
            callback: KeyboardShortcutCaptureService.eventTapCallback,
            userInfo: Unmanaged.passUnretained(self).toOpaque()
        ) else {
            self.onToggle = nil
            return false
        }
        guard let source = CFMachPortCreateRunLoopSource(kCFAllocatorDefault, tap, 0) else {
            CFMachPortInvalidate(tap)
            self.onToggle = nil
            return false
        }
        eventTap = tap
        runLoopSource = source
        CFRunLoopAddSource(CFRunLoopGetMain(), source, .commonModes)
        CGEvent.tapEnable(tap: tap, enable: true)
        return true
    }

    func stop() {
        stopEventTap(eventTap, runLoopSource: runLoopSource)
        eventTap = nil
        runLoopSource = nil
        onToggle = nil
    }

    private func handle(type: CGEventType, event: CGEvent) -> Unmanaged<CGEvent>? {
        let passThrough = Unmanaged.passUnretained(event)
        guard type == .keyDown,
              event.getIntegerValueField(.keyboardEventAutorepeat) == 0,
              KeyboardEventTranslator.isToggleShortcut(type: type, event: event)
        else {
            return passThrough
        }
        onToggle?()
        return nil
    }

    private static let eventTapCallback: CGEventTapCallBack = { _, type, event, userInfo in
        guard let userInfo else { return Unmanaged.passUnretained(event) }
        let service = Unmanaged<KeyboardShortcutCaptureService>.fromOpaque(userInfo).takeUnretainedValue()
        return service.handle(type: type, event: event)
    }
}

private final class KeyboardCaptureService {
    private var eventTap: CFMachPort?
    private var runLoopSource: CFRunLoopSource?
    private var onEvent: ((KeyboardRelayKeyEvent) -> Bool)?
    private var onEmergencyStop: (() -> Void)?
    private var onPointerOwnershipToggle: (() -> Void)?
    private var onDisabled: ((String) -> Void)?

    var isCapturing: Bool { eventTap != nil }

    func start(
        onEvent: @escaping (KeyboardRelayKeyEvent) -> Bool,
        onEmergencyStop: @escaping () -> Void,
        onPointerOwnershipToggle: @escaping () -> Void,
        onDisabled: @escaping (String) -> Void
    ) -> Bool {
        stop()
        self.onEvent = onEvent
        self.onEmergencyStop = onEmergencyStop
        self.onPointerOwnershipToggle = onPointerOwnershipToggle
        self.onDisabled = onDisabled

        let mask =
            (1 << CGEventType.keyDown.rawValue) |
            (1 << CGEventType.keyUp.rawValue) |
            (1 << CGEventType.flagsChanged.rawValue)
        guard let tap = CGEvent.tapCreate(
            tap: .cgSessionEventTap,
            place: .headInsertEventTap,
            options: .defaultTap,
            eventsOfInterest: CGEventMask(mask),
            callback: KeyboardCaptureService.eventTapCallback,
            userInfo: Unmanaged.passUnretained(self).toOpaque()
        ) else {
            return false
        }
        guard let source = CFMachPortCreateRunLoopSource(kCFAllocatorDefault, tap, 0) else {
            CFMachPortInvalidate(tap)
            return false
        }
        eventTap = tap
        runLoopSource = source
        CFRunLoopAddSource(CFRunLoopGetMain(), source, .commonModes)
        CGEvent.tapEnable(tap: tap, enable: true)
        return true
    }

    func stop() {
        stopEventTap(eventTap, runLoopSource: runLoopSource)
        eventTap = nil
        runLoopSource = nil
        onEvent = nil
        onEmergencyStop = nil
        onPointerOwnershipToggle = nil
        onDisabled = nil
    }

    private func handle(type: CGEventType, event: CGEvent) -> Unmanaged<CGEvent>? {
        let passThrough = Unmanaged.passUnretained(event)
        if type == .tapDisabledByTimeout || type == .tapDisabledByUserInput {
            onDisabled?("macOS disabled keyboard capture; local keyboard delivery restored.")
            return passThrough
        }
        if KeyboardEventTranslator.isEmergencyStop(type: type, event: event) {
            onEmergencyStop?()
            return nil
        }
        if KeyboardEventTranslator.isPointerOwnershipToggle(type: type, event: event) {
            onPointerOwnershipToggle?()
            return nil
        }
        guard let relayEvent = KeyboardEventTranslator.translate(type: type, event: event) else {
            return passThrough
        }
        return (onEvent?(relayEvent) == true) ? nil : passThrough
    }

    private static let eventTapCallback: CGEventTapCallBack = { _, type, event, userInfo in
        guard let userInfo else { return Unmanaged.passUnretained(event) }
        let service = Unmanaged<KeyboardCaptureService>.fromOpaque(userInfo).takeUnretainedValue()
        return service.handle(type: type, event: event)
    }

}

private enum KeyboardEventTranslator {
    private static let modShift = 1 << 0
    private static let modControl = 1 << 1
    private static let modAlt = 1 << 2
    private static let modSuper = 1 << 3
    private static let modCapsLock = 1 << 4
    private static let modNumLock = 1 << 5

    static func isEmergencyStop(type: CGEventType, event: CGEvent) -> Bool {
        // Ignore held-key autorepeat so one chord cannot disarm then re-arm.
        guard event.getIntegerValueField(.keyboardEventAutorepeat) == 0 else { return false }
        return isToggleShortcut(type: type, event: event)
    }

    static func isToggleShortcut(type: CGEventType, event: CGEvent) -> Bool {
        guard type == .keyDown else { return false }
        let keyCode = Int(event.getIntegerValueField(.keyboardEventKeycode))
        let flags = event.flags
        // ⌃⌥⌘K without Shift. K is key code 40 on the Mac virtual keyboard.
        return keyCode == 40 &&
            flags.contains(.maskControl) &&
            flags.contains(.maskAlternate) &&
            flags.contains(.maskCommand) &&
            !flags.contains(.maskShift)
    }

    static func isPointerOwnershipToggle(type: CGEventType, event: CGEvent) -> Bool {
        // Share detection with SymbiosisPointerService so both taps agree.
        SymbiosisPointerService.isPointerOwnershipToggle(type: type, event: event)
    }

    static func translate(type: CGEventType, event: CGEvent) -> KeyboardRelayKeyEvent? {
        let keyCode = Int(event.getIntegerValueField(.keyboardEventKeycode))
        guard let code = keyCodeToW3C[keyCode] else { return nil }
        let action: String
        switch type {
        case .keyDown:
            let repeatValue = event.getIntegerValueField(.keyboardEventAutorepeat)
            action = repeatValue == 0 ? "press" : "repeat"
        case .keyUp:
            action = "release"
        case .flagsChanged:
            action = modifierPressed(for: keyCode, flags: event.flags) ? "press" : "release"
        default:
            return nil
        }
        let text = (type == .keyDown) ? unicodeText(from: event) : nil
        return KeyboardRelayKeyEvent(
            code: code,
            text: text,
            mods: mods(from: event.flags),
            action: action
        )
    }

    private static func mods(from flags: CGEventFlags) -> Int {
        var out = 0
        if flags.contains(.maskShift) { out |= modShift }
        if flags.contains(.maskControl) { out |= modControl }
        if flags.contains(.maskAlternate) { out |= modAlt }
        if flags.contains(.maskCommand) { out |= modSuper }
        if flags.contains(.maskAlphaShift) { out |= modCapsLock }
        if flags.contains(.maskNumericPad) { out |= modNumLock }
        return out
    }

    private static func modifierPressed(for keyCode: Int, flags: CGEventFlags) -> Bool {
        switch keyCode {
        case 56, 60: return flags.contains(.maskShift)
        case 59, 62: return flags.contains(.maskControl)
        case 58, 61: return flags.contains(.maskAlternate)
        case 54, 55: return flags.contains(.maskCommand)
        case 57: return flags.contains(.maskAlphaShift)
        default: return false
        }
    }

    private static func unicodeText(from event: CGEvent) -> String? {
        var length = 0
        var chars = [UniChar](repeating: 0, count: 16)
        let maxLength = chars.count
        chars.withUnsafeMutableBufferPointer { buffer in
            event.keyboardGetUnicodeString(
                maxStringLength: maxLength,
                actualStringLength: &length,
                unicodeString: buffer.baseAddress
            )
        }
        guard length > 0 else { return nil }
        let text = chars.withUnsafeBufferPointer { buffer -> String? in
            guard let baseAddress = buffer.baseAddress else { return nil }
            return String(utf16CodeUnits: baseAddress, count: length)
        }
        // macOS reports "text" for non-printing keys too: arrows/Home/PageUp map to
        // Apple's private-use function-key codepoints (U+F700–U+F8FF), and keys like
        // Backspace/Escape/Enter map to C0 control characters. Forwarding those as
        // printable text makes the headset write junk bytes to the shell instead of
        // encoding the key properly, so suppress them and let the key code path win.
        guard let text, let first = text.unicodeScalars.first else { return nil }
        if first.value < 0x20 || first.value == 0x7F { return nil }
        if (0xF700...0xF8FF).contains(first.value) { return nil }
        return text
    }

    private static let keyCodeToW3C: [Int: String] = [
        0: "KeyA", 1: "KeyS", 2: "KeyD", 3: "KeyF", 4: "KeyH", 5: "KeyG",
        6: "KeyZ", 7: "KeyX", 8: "KeyC", 9: "KeyV", 11: "KeyB",
        12: "KeyQ", 13: "KeyW", 14: "KeyE", 15: "KeyR", 16: "KeyY", 17: "KeyT",
        18: "Digit1", 19: "Digit2", 20: "Digit3", 21: "Digit4", 22: "Digit6",
        23: "Digit5", 24: "Equal", 25: "Digit9", 26: "Digit7", 27: "Minus",
        28: "Digit8", 29: "Digit0", 30: "BracketRight", 31: "KeyO", 32: "KeyU",
        33: "BracketLeft", 34: "KeyI", 35: "KeyP", 36: "Enter", 37: "KeyL",
        38: "KeyJ", 39: "Quote", 40: "KeyK", 41: "Semicolon", 42: "Backslash",
        43: "Comma", 44: "Slash", 45: "KeyN", 46: "KeyM", 47: "Period",
        48: "Tab", 49: "Space", 50: "Backquote", 51: "Backspace", 53: "Escape",
        54: "MetaRight", 55: "MetaLeft", 56: "ShiftLeft", 57: "CapsLock",
        58: "AltLeft", 59: "ControlLeft", 60: "ShiftRight", 61: "AltRight",
        62: "ControlRight", 63: "Fn", 64: "F17", 65: "NumpadDecimal",
        67: "NumpadMultiply", 69: "NumpadAdd", 71: "NumLock", 75: "NumpadDivide",
        76: "NumpadEnter", 78: "NumpadSubtract", 79: "F18", 80: "F19",
        81: "NumpadEqual", 82: "Numpad0", 83: "Numpad1", 84: "Numpad2",
        85: "Numpad3", 86: "Numpad4", 87: "Numpad5", 88: "Numpad6",
        89: "Numpad7", 90: "F20", 91: "Numpad8", 92: "Numpad9",
        96: "F5", 97: "F6", 98: "F7", 99: "F3", 100: "F8", 101: "F9",
        103: "F11", 105: "F13", 106: "F16", 107: "F14", 109: "F10",
        111: "F12", 113: "F15", 114: "Insert", 115: "Home", 116: "PageUp",
        117: "Delete", 118: "F4", 119: "End", 120: "F2", 121: "PageDown",
        122: "F1", 123: "ArrowLeft", 124: "ArrowRight", 125: "ArrowDown",
        126: "ArrowUp",
    ]
}

private func stopEventTap(_ eventTap: CFMachPort?, runLoopSource: CFRunLoopSource?) {
    if let eventTap {
        CGEvent.tapEnable(tap: eventTap, enable: false)
        CFMachPortInvalidate(eventTap)
    }
    if let runLoopSource {
        CFRunLoopRemoveSource(CFRunLoopGetMain(), runLoopSource, .commonModes)
    }
}
