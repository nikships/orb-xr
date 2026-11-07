import AppKit
import ApplicationServices
import CoreGraphics
import Foundation

/// Marker stamped on every CGEvent this app injects (`.eventSourceUserData`), so
/// the app's own event taps (Symbiosis pointer, keyboard relay) can recognize and
/// pass through self-generated events instead of treating them as user input.
/// ASCII "GXRM".
let windowMirrorInjectedEventTag: Int64 = 0x4758_524D

final class RemoteInputInjectionService {
    private var target: MirrorTarget?
    private var leftMouseDown = false

    var hasAccessibilityPermission: Bool {
        AXIsProcessTrusted()
    }

    func setTarget(_ target: MirrorTarget?) {
        self.target = target
        leftMouseDown = false
    }

    func handle(_ message: WindowMirrorInputMessage) {
        guard hasAccessibilityPermission else { return }
        switch message.kind {
        case "pointer":
            handlePointer(message)
        case "scroll":
            handleScroll(message)
        case "key":
            handleKey(message)
        default:
            break
        }
    }

    private func handlePointer(_ message: WindowMirrorInputMessage) {
        guard let action = message.action,
              let point = windowPoint(x: message.x, y: message.y)
        else { return }
        focusTarget()

        let eventType: CGEventType
        let button: CGMouseButton = .left
        switch action {
        case "down":
            leftMouseDown = true
            eventType = .leftMouseDown
        case "up":
            leftMouseDown = false
            eventType = .leftMouseUp
        case "move":
            eventType = leftMouseDown ? .leftMouseDragged : .mouseMoved
        default:
            return
        }
        guard let event = CGEvent(mouseEventSource: nil, mouseType: eventType, mouseCursorPosition: point, mouseButton: button) else {
            return
        }
        event.setIntegerValueField(.eventSourceUserData, value: windowMirrorInjectedEventTag)
        event.post(tap: .cghidEventTap)
    }

    private func handleScroll(_ message: WindowMirrorInputMessage) {
        focusTarget()
        let dx = Int32((message.dx ?? 0).rounded())
        let dy = Int32((message.dy ?? 0).rounded())
        guard dx != 0 || dy != 0,
              let event = CGEvent(
                scrollWheelEvent2Source: nil,
                units: .pixel,
                wheelCount: 2,
                wheel1: -dy,
                wheel2: -dx,
                wheel3: 0
              )
        else { return }
        event.setIntegerValueField(.eventSourceUserData, value: windowMirrorInjectedEventTag)
        event.post(tap: .cghidEventTap)
    }

    private func handleKey(_ message: WindowMirrorInputMessage) {
        guard let code = message.code,
              let keyCode = Self.w3cToKeyCode[code],
              let action = message.action
        else { return }
        focusTarget()

        let keyDown = action == "press" || action == "repeat"
        guard let event = CGEvent(keyboardEventSource: nil, virtualKey: CGKeyCode(keyCode), keyDown: keyDown) else {
            return
        }
        event.flags = flags(from: message.mods ?? 0)
        if keyDown, let text = message.text, !text.isEmpty {
            let utf16 = Array(text.utf16)
            utf16.withUnsafeBufferPointer { buffer in
                event.keyboardSetUnicodeString(stringLength: utf16.count, unicodeString: buffer.baseAddress)
            }
        }
        event.setIntegerValueField(.eventSourceUserData, value: windowMirrorInjectedEventTag)
        event.post(tap: .cghidEventTap)
    }

    private func focusTarget() {
        // Display shares behave like a real mouse: clicks land wherever they land,
        // no app activation / AX raise (which would fight the user's own focus).
        guard let windowTarget = target?.windowTarget else { return }
        NSRunningApplication(processIdentifier: windowTarget.processID)?.activate()

        let appElement = AXUIElementCreateApplication(windowTarget.processID)
        var value: CFTypeRef?
        if AXUIElementCopyAttributeValue(appElement, kAXWindowsAttribute as CFString, &value) == .success,
           let windows = value as? [AXUIElement] {
            for window in windows {
                AXUIElementPerformAction(window, kAXRaiseAction as CFString)
                AXUIElementSetAttributeValue(appElement, kAXFocusedWindowAttribute as CFString, window)
                break
            }
        }
    }

    private func windowPoint(x: Double?, y: Double?) -> CGPoint? {
        guard let target, let x, let y else { return nil }
        let frame: CGRect
        switch target {
        case .window(let windowTarget):
            frame = currentFrame(for: windowTarget.id) ?? windowTarget.frame
        case .display(let displayTarget):
            frame = displayTarget.frame
        }
        return CGPoint(
            x: frame.minX + frame.width * CGFloat(x.clamped(to: 0...1)),
            y: frame.minY + frame.height * CGFloat(y.clamped(to: 0...1))
        )
    }

    private func currentFrame(for windowID: CGWindowID) -> CGRect? {
        let info = CGWindowListCopyWindowInfo([.optionIncludingWindow], windowID) as? [[String: Any]]
        guard let bounds = info?.first?[kCGWindowBounds as String] as? [String: Any] else { return nil }
        return CGRect(dictionaryRepresentation: bounds as CFDictionary)
    }

    private func flags(from mods: Int) -> CGEventFlags {
        var flags: CGEventFlags = []
        if (mods & 1) != 0 { flags.insert(.maskShift) }
        if (mods & (1 << 1)) != 0 { flags.insert(.maskControl) }
        if (mods & (1 << 2)) != 0 { flags.insert(.maskAlternate) }
        if (mods & (1 << 3)) != 0 { flags.insert(.maskCommand) }
        if (mods & (1 << 4)) != 0 { flags.insert(.maskAlphaShift) }
        return flags
    }

    private static let w3cToKeyCode: [String: Int] = [
        "KeyA": 0, "KeyS": 1, "KeyD": 2, "KeyF": 3, "KeyH": 4, "KeyG": 5,
        "KeyZ": 6, "KeyX": 7, "KeyC": 8, "KeyV": 9, "KeyB": 11,
        "KeyQ": 12, "KeyW": 13, "KeyE": 14, "KeyR": 15, "KeyY": 16, "KeyT": 17,
        "Digit1": 18, "Digit2": 19, "Digit3": 20, "Digit4": 21, "Digit6": 22,
        "Digit5": 23, "Equal": 24, "Digit9": 25, "Digit7": 26, "Minus": 27,
        "Digit8": 28, "Digit0": 29, "BracketRight": 30, "KeyO": 31, "KeyU": 32,
        "BracketLeft": 33, "KeyI": 34, "KeyP": 35, "Enter": 36, "KeyL": 37,
        "KeyJ": 38, "Quote": 39, "KeyK": 40, "Semicolon": 41, "Backslash": 42,
        "Comma": 43, "Slash": 44, "KeyN": 45, "KeyM": 46, "Period": 47,
        "Tab": 48, "Space": 49, "Backquote": 50, "Backspace": 51, "Escape": 53,
        "MetaRight": 54, "MetaLeft": 55, "ShiftLeft": 56, "CapsLock": 57,
        "AltLeft": 58, "ControlLeft": 59, "ShiftRight": 60, "AltRight": 61,
        "ControlRight": 62, "F17": 64, "NumpadDecimal": 65, "NumpadMultiply": 67,
        "NumpadAdd": 69, "NumLock": 71, "NumpadDivide": 75, "NumpadEnter": 76,
        "NumpadSubtract": 78, "F18": 79, "F19": 80, "NumpadEqual": 81,
        "Numpad0": 82, "Numpad1": 83, "Numpad2": 84, "Numpad3": 85,
        "Numpad4": 86, "Numpad5": 87, "Numpad6": 88, "Numpad7": 89,
        "F20": 90, "Numpad8": 91, "Numpad9": 92, "F5": 96, "F6": 97,
        "F7": 98, "F3": 99, "F8": 100, "F9": 101, "F11": 103, "F13": 105,
        "F16": 106, "F14": 107, "F10": 109, "F12": 111, "Insert": 114,
        "Home": 115, "PageUp": 116, "Delete": 117, "F4": 118, "End": 119,
        "F2": 120, "PageDown": 121, "F1": 122, "ArrowLeft": 123,
        "ArrowRight": 124, "ArrowDown": 125, "ArrowUp": 126
    ]
}

private extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        min(max(self, range.lowerBound), range.upperBound)
    }
}
