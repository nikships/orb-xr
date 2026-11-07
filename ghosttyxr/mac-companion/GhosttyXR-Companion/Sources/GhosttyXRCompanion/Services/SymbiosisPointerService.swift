import AppKit
import ApplicationServices
import CoreGraphics
import Foundation

/// Who currently owns the physical mouse in Symbiosis (full-screen share) mode.
enum SymbiosisPointerOwner: String, Equatable {
    case mac
    case xr
}

/// Keyboard-toggle pointer ownership for Symbiosis mode (full-screen display share).
///
/// While the Mac owns the pointer, mouse events pass through untouched. Pressing
/// Control+Option+Command+M hands ownership to XR: every subsequent mouse event is
/// suppressed locally, the cursor is pinned (`CGAssociateMouseAndMouseCursorPosition(false)`),
/// and raw deltas / button transitions / scrolls stream to the headset. The same chord
/// (or an emergency stop / disconnect) returns ownership to the Mac.
///
/// Requires Accessibility (the tap is an active `.defaultTap`, same TCC gate as the
/// keyboard relay). Any tap disablement or stop force-returns ownership to the Mac so
/// the physical mouse can never be stranded.
final class SymbiosisPointerService {
    var onOwnerChanged: (SymbiosisPointerOwner) -> Void = { _ in }
    var onHandoff: (_ seq: Int, _ edge: String, _ yFraction: Double) -> Void = { _, _, _ in }
    var onMouseDelta: (_ seq: Int, _ dx: Double, _ dy: Double) -> Void = { _, _, _ in }
    var onMouseButton: (_ seq: Int, _ button: String, _ action: String) -> Void = { _, _, _ in }
    var onMouseScroll: (_ seq: Int, _ dx: Double, _ dy: Double) -> Void = { _, _, _ in }
    var onDisabled: (String) -> Void = { _ in }
    /// Fired when the user presses Control+Option+Command+M. The controller toggles
    /// ownership so headset notify stays centralized.
    var onToggleRequested: () -> Void = {}

    private var eventTap: CFMachPort?
    private var runLoopSource: CFRunLoopSource?
    private var displayFrame: CGRect = .zero
    private var owner: SymbiosisPointerOwner = .mac
    private var pinnedPoint: CGPoint = .zero

    /// Per-pointer-session sequence stamped on every forwarded event so the
    /// headset can detect dropped frames and cancel a stranded press. The
    /// handoff itself is 1; each subsequent event increments. Sends are never
    /// "un-counted" on failure — a failed send IS a dropped event, and hiding
    /// it from the numbering would defeat the headset's gap detection.
    private var sequence = 0

    /// ANSI keycode for the letter M.
    private static let pointerToggleKeyCode = 46

    var isRunning: Bool { eventTap != nil }
    var currentOwner: SymbiosisPointerOwner { owner }

    /// Start the Symbiosis mouse/key tap for the given display bounds
    /// (global coordinates, as from `CGDisplayBounds`).
    func start(displayFrame: CGRect) -> Bool {
        stop()
        self.displayFrame = displayFrame
        owner = .mac

        let mask =
            (1 << CGEventType.mouseMoved.rawValue) |
            (1 << CGEventType.leftMouseDown.rawValue) |
            (1 << CGEventType.leftMouseUp.rawValue) |
            (1 << CGEventType.leftMouseDragged.rawValue) |
            (1 << CGEventType.rightMouseDown.rawValue) |
            (1 << CGEventType.rightMouseUp.rawValue) |
            (1 << CGEventType.rightMouseDragged.rawValue) |
            (1 << CGEventType.otherMouseDown.rawValue) |
            (1 << CGEventType.otherMouseUp.rawValue) |
            (1 << CGEventType.otherMouseDragged.rawValue) |
            (1 << CGEventType.scrollWheel.rawValue) |
            (1 << CGEventType.keyDown.rawValue)
        guard let tap = CGEvent.tapCreate(
            tap: .cgSessionEventTap,
            place: .headInsertEventTap,
            options: .defaultTap,
            eventsOfInterest: CGEventMask(mask),
            callback: SymbiosisPointerService.eventTapCallback,
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
        let wasXROwned = owner == .xr
        if wasXROwned {
            releasePointer(warpTo: nil)
        }
        if let tap = eventTap {
            CGEvent.tapEnable(tap: tap, enable: false)
            CFMachPortInvalidate(tap)
        }
        if let source = runLoopSource {
            CFRunLoopRemoveSource(CFRunLoopGetMain(), source, .commonModes)
        }
        eventTap = nil
        runLoopSource = nil
        owner = .mac
        if wasXROwned {
            // Stopping mid-handoff must release keyboard-follows-pointer too.
            onOwnerChanged(.mac)
        }
    }

    /// Hand the physical mouse to XR and notify the headset (spawn at center).
    func handOffToXR() {
        guard owner == .mac else { return }
        let pin = CGEvent(source: nil)?.location ?? pinnedPoint
        beginXROwnership(pinAt: pin)
        sequence = 1
        onHandoff(sequence, "center", 0.5)
    }

    /// Headset reports pointer return at fractional (x, y) on the shared display.
    /// Kept for protocol compatibility; ownership toggle no longer requires re-entry.
    func returnPointer(x: Double, y: Double) {
        guard owner == .xr else { return }
        let point = CGPoint(
            x: displayFrame.minX + displayFrame.width * CGFloat(min(max(x, 0), 1)),
            y: displayFrame.minY + displayFrame.height * CGFloat(min(max(y, 0), 1))
        )
        releasePointer(warpTo: point)
        owner = .mac
        onOwnerChanged(.mac)
    }

    /// Force ownership back to the Mac (disconnect, stream stop, emergency chord, toggle).
    func forceMacOwnership() {
        guard owner == .xr else { return }
        releasePointer(warpTo: nil)
        owner = .mac
        onOwnerChanged(.mac)
    }

    // MARK: - Tap handling

    private func handle(type: CGEventType, event: CGEvent) -> Unmanaged<CGEvent>? {
        let passThrough = Unmanaged.passUnretained(event)
        // Events injected by RemoteInputInjectionService (headset taps on the
        // mirror pane) carry a marker tag. They must pass through untouched so they
        // are never swallowed with the user's physical mouse while XR owns it.
        if event.getIntegerValueField(.eventSourceUserData) == windowMirrorInjectedEventTag {
            return passThrough
        }
        if type == .tapDisabledByTimeout || type == .tapDisabledByUserInput {
            if let tap = eventTap, type == .tapDisabledByTimeout {
                // Timeout disables are transient (system stall); re-enable and keep going.
                CGEvent.tapEnable(tap: tap, enable: true)
                return passThrough
            }
            stop()
            onDisabled("macOS disabled Symbiosis mouse capture; local mouse restored.")
            return passThrough
        }

        if Self.isPointerOwnershipToggle(type: type, event: event) {
            onToggleRequested()
            return nil
        }

        switch owner {
        case .mac:
            return passThrough
        case .xr:
            // Non-toggle keys must reach the keyboard relay (installed as a separate tap).
            if type == .keyDown || type == .keyUp {
                return passThrough
            }
            forward(type: type, event: event)
            return nil
        }
    }

    private func beginXROwnership(pinAt point: CGPoint) {
        owner = .xr
        pinnedPoint = point
        CGAssociateMouseAndMouseCursorPosition(0)
        onOwnerChanged(.xr)
    }

    private func releasePointer(warpTo point: CGPoint?) {
        CGAssociateMouseAndMouseCursorPosition(1)
        if let point {
            CGWarpMouseCursorPosition(point)
        }
    }

    private func forward(type: CGEventType, event: CGEvent) {
        switch type {
        case .mouseMoved, .leftMouseDragged, .rightMouseDragged, .otherMouseDragged:
            let dx = event.getDoubleValueField(.mouseEventDeltaX)
            let dy = event.getDoubleValueField(.mouseEventDeltaY)
            if dx != 0 || dy != 0 {
                sequence += 1
                onMouseDelta(sequence, dx, dy)
            }
            // Keep the hidden local cursor pinned even if association leaks a move.
            CGWarpMouseCursorPosition(pinnedPoint)
        case .leftMouseDown:
            sequence += 1
            onMouseButton(sequence, "left", "down")
        case .leftMouseUp:
            sequence += 1
            onMouseButton(sequence, "left", "up")
        case .rightMouseDown, .rightMouseUp, .otherMouseDown, .otherMouseUp:
            // Right/middle buttons are swallowed (not forwarded) in v1 so they can't
            // fire on the Mac under the pinned cursor.
            break
        case .scrollWheel:
            let dy = event.getDoubleValueField(.scrollWheelEventPointDeltaAxis1)
            let dx = event.getDoubleValueField(.scrollWheelEventPointDeltaAxis2)
            if dx != 0 || dy != 0 {
                sequence += 1
                onMouseScroll(sequence, dx, dy)
            }
        default:
            break
        }
    }

    /// Control+Option+Command+M (no Shift). Ignores autorepeat.
    static func isPointerOwnershipToggle(type: CGEventType, event: CGEvent) -> Bool {
        guard type == .keyDown else { return false }
        guard event.getIntegerValueField(.keyboardEventAutorepeat) == 0 else { return false }
        let keyCode = Int(event.getIntegerValueField(.keyboardEventKeycode))
        let flags = event.flags
        return keyCode == pointerToggleKeyCode &&
            flags.contains(.maskControl) &&
            flags.contains(.maskAlternate) &&
            flags.contains(.maskCommand) &&
            !flags.contains(.maskShift)
    }

    private static let eventTapCallback: CGEventTapCallBack = { _, type, event, userInfo in
        guard let userInfo else { return Unmanaged.passUnretained(event) }
        let service = Unmanaged<SymbiosisPointerService>.fromOpaque(userInfo).takeUnretainedValue()
        return service.handle(type: type, event: event)
    }
}
