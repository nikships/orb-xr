import AppKit
import CoreGraphics
import Foundation
import ScreenCaptureKit
import SwiftUI

enum MacWindowMirrorStatus: Equatable {
    case waitingForHeadset
    case ready
    case selecting
    case streaming
    case screenRecordingNeeded
    case error(String)
}

struct MacWindowMirrorState: Equatable {
    var status: MacWindowMirrorStatus = .waitingForHeadset
    var detail: String = "Waiting for the headset to open the secure mirror connection."
    var windowTitle: String?
    var interactive: Bool = false
    /// True while a full display is being shared (Symbiosis mode).
    var isDisplayShare: Bool = false
    /// Who owns the physical mouse while Symbiosis is active.
    var pointerOwner: SymbiosisPointerOwner = .mac
}

@MainActor
final class MacWindowMirrorController: ObservableObject {
    @Published private(set) var state = MacWindowMirrorState()
    /// Target bitrate ceiling in Mbps (8–100). Persisted; applies live to the
    /// running encoder session without a restart.
    @Published var bitrateMbps: Double {
        didSet {
            UserDefaults.standard.set(bitrateMbps, forKey: Self.bitrateDefaultsKey)
            adaptedBitrateMbps = bitrateMbps
            server.setBitrateCeiling(Int(bitrateMbps * 1_000_000))
        }
    }

    /// Video codec preference. HEVC costs ~30% fewer bits at equal quality but
    /// not every headset decoder handles it at 90fps, so H.264 stays the default.
    @Published var codec: WindowMirrorVideoCodec {
        didSet {
            UserDefaults.standard.set(codec.rawValue, forKey: Self.codecDefaultsKey)
            server.setPreferredCodec(codec)
        }
    }

    /// AES-GCM on the direct media socket. On by default; can be turned off on a
    /// trusted LAN to shave the encrypt/decrypt cost per slice.
    @Published var encryptMedia: Bool {
        didSet {
            UserDefaults.standard.set(encryptMedia, forKey: Self.encryptDefaultsKey)
            server.setEncryptMedia(encryptMedia)
        }
    }

    @Published private(set) var activeBitrateMbps: Double
    @Published private(set) var streamStats = WindowMirrorStreamStats()

    /// What the latency controller is currently asking the encoder for. Always
    /// less than or equal to `bitrateMbps`.
    private var adaptedBitrateMbps: Double

    /// Fires when Symbiosis pointer ownership changes; the app wires this to the
    /// keyboard relay so keys follow the pointer.
    var onPointerOwnerChanged: (SymbiosisPointerOwner) -> Void = { _ in }

    private static let bitrateDefaultsKey = "windowMirrorBitrateMbps"
    private static let codecDefaultsKey = "windowMirrorCodec"
    private static let encryptDefaultsKey = "windowMirrorEncryptMedia"

    /// Above this measured end-to-end latency we back the bitrate off; below the
    /// recovery threshold we creep back toward the user's ceiling. The gap between
    /// the two is deliberate hysteresis so the controller does not oscillate.
    private static let latencyBackoffMs = 90.0
    private static let latencyRecoveryMs = 55.0
    private static let backoffFactor = 0.85
    private static let recoveryStepMbps = 2.0
    private static let minAdaptedMbps = 5.0

    private let server = WindowMirrorRelayServer()
    private let selector = WindowSelectionService()
    private let capture = WindowCaptureService()
    private let input = RemoteInputInjectionService()
    private let symbiosis = SymbiosisPointerService()
    private var target: MirrorTarget?
    private var activationObserver: NSObjectProtocol?
    private var captureGeneration = 0
    private var captureOperation: Task<Void, Never>?

    init() {
        let storedBitrate = UserDefaults.standard.double(forKey: Self.bitrateDefaultsKey)
        let initialBitrate = storedBitrate > 0 ? min(max(storedBitrate, 8), 100) : 25
        bitrateMbps = initialBitrate
        adaptedBitrateMbps = initialBitrate
        activeBitrateMbps = 0
        let storedCodec = UserDefaults.standard.string(forKey: Self.codecDefaultsKey)
        codec = storedCodec.flatMap(WindowMirrorVideoCodec.init(rawValue:)) ?? .h264
        encryptMedia = UserDefaults.standard.object(forKey: Self.encryptDefaultsKey) as? Bool ?? true

        let relayServer = server
        relayServer.setBitrateCeiling(Int(initialBitrate * 1_000_000))
        relayServer.setPreferredCodec(codec)
        relayServer.setEncryptMedia(encryptMedia)
        server.onClientReadyChanged = { [weak self] ready in
            Task { @MainActor in
                self?.clientReadyChanged(ready)
            }
        }
        server.onInput = { [weak self] message in
            Task { @MainActor in
                self?.input.handle(message)
            }
        }
        server.onPointerReturn = { [weak self] message in
            Task { @MainActor in
                self?.symbiosis.returnPointer(x: message.x, y: message.y)
            }
        }
        server.onClose = { [weak self] reason in
            Task { @MainActor in
                self?.stopStreaming(reason: reason ?? "Window mirror closed from XR.")
            }
        }
        server.onError = { [weak self] message in
            Task { @MainActor in
                self?.fail(message)
            }
        }
        server.onStats = { [weak self] stats in
            Task { @MainActor in
                guard let self else { return }
                self.streamStats = stats
                self.activeBitrateMbps = Double(stats.bitrateBps) / 1_000_000
                self.adaptBitrate(endToEndMs: stats.endToEndMs)
            }
        }
        capture.onSampleBuffer = { sampleBuffer in
            relayServer.submit(sampleBuffer)
        }
        capture.onStopped = { [weak self] reason in
            Task { @MainActor in
                self?.stopStreaming(reason: reason)
            }
        }
        // Symbiosis pointer plumbing. The mouse tap callbacks fire on the main run
        // loop already, but hop through Task { @MainActor } for the state mutations.
        symbiosis.onHandoff = { [relayServer] seq, edge, y in
            relayServer.sendPointerHandoff(seq: seq, edge: edge, y: y)
        }
        symbiosis.onMouseDelta = { [relayServer] seq, dx, dy in
            relayServer.sendMouseDelta(seq: seq, dx: dx, dy: dy)
        }
        symbiosis.onMouseButton = { [relayServer] seq, button, action in
            relayServer.sendMouseButton(seq: seq, button: button, action: action)
        }
        symbiosis.onMouseScroll = { [relayServer] seq, dx, dy in
            relayServer.sendMouseScroll(seq: seq, dx: dx, dy: dy)
        }
        symbiosis.onOwnerChanged = { [weak self] owner in
            Task { @MainActor in
                self?.pointerOwnerChanged(owner)
            }
        }
        symbiosis.onToggleRequested = { [weak self] in
            Task { @MainActor in
                self?.togglePointerOwnership()
            }
        }
        symbiosis.onDisabled = { [weak self] reason in
            Task { @MainActor in
                guard let self, self.target?.isDisplay == true else { return }
                self.state.detail = reason
            }
        }
        server.start()
        publishIdle()

        activationObserver = NotificationCenter.default.addObserver(
            forName: NSApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                self?.refreshAccessibilityState()
            }
        }
    }

    deinit {
        server.stop()
        symbiosis.stop()
        if let activationObserver {
            NotificationCenter.default.removeObserver(activationObserver)
        }
    }

    var statusLabel: String {
        switch state.status {
        case .waitingForHeadset: return "mirror waiting"
        case .ready: return "mirror ready"
        case .selecting: return "selecting window"
        case .streaming: return state.isDisplayShare ? "sharing screen" : "mirroring window"
        case .screenRecordingNeeded: return "mirror permission"
        case .error: return "mirror error"
        }
    }

    var statusSymbol: String {
        switch state.status {
        case .streaming: return "rectangle.on.rectangle"
        case .selecting: return "cursorarrow.rays"
        case .ready: return "rectangle.connected.to.line.below"
        case .screenRecordingNeeded: return "hand.raised"
        case .error: return "exclamationmark.triangle"
        case .waitingForHeadset: return "rectangle.dashed"
        }
    }

    var statusTint: Color {
        switch state.status {
        case .streaming: return GhosttyTheme.neonGreen
        case .ready, .selecting: return GhosttyTheme.neonCyan
        case .screenRecordingNeeded, .error: return GhosttyTheme.neonAmber
        case .waitingForHeadset: return GhosttyTheme.dimText
        }
    }

    func bringWindowIntoXR() {
        guard ensureScreenRecordingPermission() else { return }
        state = MacWindowMirrorState(
            status: .selecting,
            detail: "Click the Mac window to mirror into XR. Press Escape to cancel.",
            windowTitle: nil,
            interactive: input.hasAccessibilityPermission
        )
        selector.beginSelection(
            onSelected: { [weak self] target in
                self?.startStreaming(.window(target))
            },
            onCancelled: { [weak self] in
                self?.publishIdle(detail: "Window selection cancelled.")
            },
            onError: { [weak self] message in
                self?.fail(message)
            }
        )
    }

    /// Share the Mac's main display into XR and enable Symbiosis (edge-crossing
    /// mouse + keyboard-follows-pointer).
    func shareFullScreen() {
        guard ensureScreenRecordingPermission() else { return }
        Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                let displayTarget = try await DisplaySelectionService.mainDisplayTarget()
                self.startStreaming(.display(displayTarget))
            } catch {
                self.fail("Could not share the main display: \(error.localizedDescription)")
            }
        }
    }

    func stopMirror() {
        stopStreaming(reason: "Window mirror stopped.")
    }

    /// Force Symbiosis pointer ownership back to the Mac (emergency chord).
    /// Notifies the headset so the XR cursor is dismissed.
    func forcePointerToMac() {
        guard state.pointerOwner == .xr else { return }
        symbiosis.forceMacOwnership()
        server.notifyPointerReturned()
    }

    /// Toggle Mac ↔ XR pointer ownership via Control+Option+Command+M.
    /// Only active while a full-screen display share is streaming.
    func togglePointerOwnership() {
        guard state.isDisplayShare, state.status == .streaming else { return }
        guard input.hasAccessibilityPermission else {
            state.detail = "Grant Accessibility to toggle Symbiosis mouse ownership."
            return
        }
        if state.pointerOwner == .xr {
            forcePointerToMac()
        } else {
            // Ensure the mouse tap is running before handing off.
            reconcileSymbiosis()
            guard symbiosis.isRunning else {
                state.detail = "Symbiosis mouse tap is not running — check Accessibility."
                return
            }
            symbiosis.handOffToXR()
        }
    }

    func openScreenRecordingSettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture") {
            NSWorkspace.shared.open(url)
        }
    }

    func openAccessibilitySettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility") {
            NSWorkspace.shared.open(url)
        }
    }

    private func ensureScreenRecordingPermission() -> Bool {
        if CGPreflightScreenCaptureAccess() {
            return true
        }
        if CGRequestScreenCaptureAccess() {
            return true
        }
        state = MacWindowMirrorState(
            status: .screenRecordingNeeded,
            detail: "Grant Screen Recording to Orb XR, then choose Bring Window into XR again.",
            windowTitle: nil,
            interactive: false
        )
        server.sendStreamState(status: "permission", detail: state.detail, interactive: false)
        return false
    }

    private func startStreaming(_ target: MirrorTarget) {
        self.target = target
        input.setTarget(target)
        publishStreaming(target: target, pointerOwner: .mac)
        captureGeneration += 1
        queueCaptureStart(target, generation: captureGeneration)
    }

    private func stopStreaming(reason: String) {
        let oldTarget = teardownStream()
        server.sendClose(reason: reason)
        server.sendStreamState(status: "idle", detail: reason, interactive: input.hasAccessibilityPermission)
        publishIdle(detail: oldTarget.map { "\(reason) \($0.displayTitle)" } ?? reason)
    }

    private func streamingDetail(for target: MirrorTarget, interactive: Bool) -> String {
        if target.isDisplay {
            return interactive
                ? "Sharing main display into XR · mouse on Mac. Toggle XR mouse: Control+Option+Command+M"
                : "Sharing main display into XR (view-only — grant Accessibility for Symbiosis)."
        }
        return interactive
            ? "Streaming \(target.displayTitle) into XR."
            : "Streaming \(target.displayTitle) into XR (view-only)."
    }

    private func publishStreaming(target: MirrorTarget, pointerOwner: SymbiosisPointerOwner) {
        let interactive = input.hasAccessibilityPermission
        state = MacWindowMirrorState(
            status: .streaming,
            detail: streamingDetail(for: target, interactive: interactive),
            windowTitle: target.displayTitle,
            interactive: interactive,
            isDisplayShare: target.isDisplay,
            pointerOwner: pointerOwner
        )
        sendMetadata(for: target)
        server.sendStreamState(
            status: "streaming",
            detail: state.detail,
            interactive: interactive,
            transport: server.activeTransportName
        )
        reconcileSymbiosis()
    }

    /// Stops selection, Symbiosis, encoding, and capture; clears the target.
    /// Returns the target that was streaming, if any.
    private func teardownStream() -> MirrorTarget? {
        selector.cancel()
        symbiosis.stop()
        captureGeneration += 1
        let oldTarget = target
        target = nil
        input.setTarget(nil)
        queueCaptureStop()
        return oldTarget
    }

    /// Start/stop the Symbiosis mouse tap to match the current stream: running only
    /// while a display share is streaming with Accessibility granted.
    private func reconcileSymbiosis() {
        guard let target, case .display(let displayTarget) = target, input.hasAccessibilityPermission else {
            symbiosis.stop()
            return
        }
        guard !symbiosis.isRunning else { return }
        if !symbiosis.start(displayFrame: displayTarget.frame) {
            state.detail = "Sharing main display (Symbiosis mouse tap could not start — check Accessibility)."
        }
    }

    private func pointerOwnerChanged(_ owner: SymbiosisPointerOwner) {
        state.pointerOwner = owner
        if state.isDisplayShare, state.status == .streaming {
            state.detail = owner == .xr
                ? "Symbiosis: mouse + keyboard are in XR. Toggle back: Control+Option+Command+M"
                : "Sharing main display into XR · mouse on Mac. Toggle XR mouse: Control+Option+Command+M"
        }
        onPointerOwnerChanged(owner)
    }

    /// Steers bitrate on the latency the headset actually measured.
    ///
    /// Every adjustment is an in-place encoder reconfiguration. Nothing is torn
    /// down and nothing is renegotiated, which is why adaptation is invisible
    /// here where the old restart-the-sender approach caused a visible freeze.
    private func adaptBitrate(endToEndMs: Double) {
        guard endToEndMs > 0 else { return }
        let previous = adaptedBitrateMbps
        if endToEndMs > Self.latencyBackoffMs {
            adaptedBitrateMbps = max(Self.minAdaptedMbps, adaptedBitrateMbps * Self.backoffFactor)
        } else if endToEndMs < Self.latencyRecoveryMs, adaptedBitrateMbps < bitrateMbps {
            adaptedBitrateMbps = min(bitrateMbps, adaptedBitrateMbps + Self.recoveryStepMbps)
        }
        guard abs(adaptedBitrateMbps - previous) > 0.01 else { return }
        server.setBitrateCeiling(Int(adaptedBitrateMbps * 1_000_000))
    }

    private func clientReadyChanged(_ ready: Bool) {
        if ready, let target {
            sendMetadata(for: target)
            server.sendStreamState(
                status: "streaming",
                detail: state.detail,
                interactive: state.interactive,
                transport: server.activeTransportName
            )
            reconcileSymbiosis()
        } else if ready {
            server.sendStreamState(status: "idle", detail: "Choose Bring Window into XR from the Mac companion.", interactive: input.hasAccessibilityPermission)
        } else {
            // Headset gone: never strand the physical mouse in XR ownership.
            symbiosis.forceMacOwnership()
            symbiosis.stop()
        }
        publishIdle()
    }

    private func sendMetadata(for target: MirrorTarget) {
        let title: String
        let app: String
        switch target {
        case .window(let windowTarget):
            title = windowTarget.title
            app = windowTarget.appName
        case .display:
            title = "Mac Screen"
            app = ""
        }
        server.sendMetadata(
            WindowMirrorMetadata(
                id: target.metadataID,
                title: title,
                app: app,
                width: max(1, Int(target.frame.width)),
                height: max(1, Int(target.frame.height)),
                scale: Double(NSScreen.main?.backingScaleFactor ?? 2),
                interactive: input.hasAccessibilityPermission,
                kind: target.kind,
                codec: "h264"
            )
        )
    }

    private func publishIdle(detail: String? = nil) {
        guard target == nil else { return }
        if case .screenRecordingNeeded = state.status,
           !CGPreflightScreenCaptureAccess() {
            return
        }
        let ready = server.hasReadyClient
        state = MacWindowMirrorState(
            status: ready ? .ready : .waitingForHeadset,
            detail: detail ?? (ready
                ? "Headset connected. Choose a Mac window to mirror."
                : "Waiting for the headset to open the secure mirror connection."),
            windowTitle: nil,
            interactive: input.hasAccessibilityPermission
        )
    }

    private func fail(_ message: String) {
        let oldTarget = teardownStream()
        state = MacWindowMirrorState(status: .error(message), detail: message, windowTitle: oldTarget?.displayTitle, interactive: false)
        server.sendStreamState(status: "error", detail: message, interactive: false)
    }

    private func queueCaptureStart(_ target: MirrorTarget, generation: Int) {
        let previousOperation = captureOperation
        captureOperation = Task { [weak self] in
            await previousOperation?.value
            guard let self,
                  self.captureGeneration == generation,
                  self.target?.metadataID == target.metadataID
            else {
                return
            }

            do {
                try await self.capture.start(target: target)
                if self.captureGeneration != generation || self.target?.metadataID != target.metadataID {
                    try? await self.capture.stop()
                }
            } catch {
                guard self.captureGeneration == generation else { return }
                self.fail("Could not capture \(target.displayTitle): \(error.localizedDescription)")
            }
        }
    }

    private func queueCaptureStop() {
        let previousOperation = captureOperation
        captureOperation = Task { [weak self] in
            await previousOperation?.value
            guard let self else { return }
            try? await self.capture.stop()
        }
    }

    /// Re-reads Accessibility TCC state when the app regains focus so an active
    /// view-only mirror becomes interactive after the user grants permission.
    private func refreshAccessibilityState() {
        guard let target else {
            publishIdle()
            return
        }
        guard input.hasAccessibilityPermission != state.interactive else { return }
        publishStreaming(target: target, pointerOwner: state.pointerOwner)
    }
}
