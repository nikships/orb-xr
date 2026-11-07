import AppKit
import CoreGraphics
import Foundation
import ScreenCaptureKit

struct WindowMirrorTarget: Identifiable {
    let id: CGWindowID
    let window: SCWindow
    let title: String
    let appName: String
    let processID: pid_t
    let frame: CGRect

    var displayTitle: String {
        title.isEmpty ? appName : "\(appName) · \(title)"
    }
}

/// A full-display share target (Symbiosis mode). Main display only in v1.
struct DisplayMirrorTarget {
    let display: SCDisplay
    /// Display bounds in global (CGWindow) coordinates, in points.
    let frame: CGRect
}

/// What the mirror pipeline is capturing: a single window or a whole display.
enum MirrorTarget {
    case window(WindowMirrorTarget)
    case display(DisplayMirrorTarget)

    var displayTitle: String {
        switch self {
        case .window(let target): return target.displayTitle
        case .display: return "Mac Screen"
        }
    }

    var frame: CGRect {
        switch self {
        case .window(let target): return target.frame
        case .display(let target): return target.frame
        }
    }

    var kind: String {
        switch self {
        case .window: return "window"
        case .display: return "display"
        }
    }

    var metadataID: UInt32 {
        switch self {
        case .window(let target): return UInt32(target.id)
        case .display(let target): return UInt32(target.display.displayID)
        }
    }

    var windowTarget: WindowMirrorTarget? {
        if case .window(let target) = self { return target }
        return nil
    }

    var isDisplay: Bool {
        if case .display = self { return true }
        return false
    }
}

/// Resolves the Mac's main display as a share target via ScreenCaptureKit.
@MainActor
enum DisplaySelectionService {
    static func mainDisplayTarget() async throws -> DisplayMirrorTarget {
        let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
        let mainID = CGMainDisplayID()
        guard let display = content.displays.first(where: { $0.displayID == mainID }) ?? content.displays.first else {
            throw NSError(domain: "DisplaySelectionService", code: 1, userInfo: [
                NSLocalizedDescriptionKey: "No shareable display was found."
            ])
        }
        return DisplayMirrorTarget(
            display: display,
            frame: CGDisplayBounds(display.displayID)
        )
    }
}

@MainActor
final class WindowSelectionService {
    private var overlay: WindowSelectionOverlayController?

    func beginSelection(
        onSelected: @escaping (WindowMirrorTarget) -> Void,
        onCancelled: @escaping () -> Void,
        onError: @escaping (String) -> Void
    ) {
        Task { @MainActor in
            do {
                let targets = try await loadTargets()
                guard !targets.isEmpty else {
                    onError("No selectable Mac windows were found.")
                    return
                }
                overlay?.dismiss()
                let overlay = WindowSelectionOverlayController(
                    targets: targets,
                    onSelected: { [weak self] target in
                        self?.overlay?.dismiss()
                        self?.overlay = nil
                        onSelected(target)
                    },
                    onCancelled: { [weak self] in
                        self?.overlay?.dismiss()
                        self?.overlay = nil
                        onCancelled()
                    }
                )
                self.overlay = overlay
                overlay.present()
            } catch {
                onError("Could not list Mac windows: \(error.localizedDescription)")
            }
        }
    }

    func cancel() {
        overlay?.dismiss()
        overlay = nil
    }

    private func loadTargets() async throws -> [WindowMirrorTarget] {
        let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
        let currentPID = pid_t(ProcessInfo.processInfo.processIdentifier)
        let currentBundle = Bundle.main.bundleIdentifier
        let orderedInfo = CGWindowListCopyWindowInfo([.optionOnScreenOnly, .excludeDesktopElements], kCGNullWindowID) as? [[String: Any]] ?? []
        let orderedIDs: [CGWindowID] = orderedInfo.compactMap { $0[kCGWindowNumber as String] as? CGWindowID }
        let order = Dictionary(uniqueKeysWithValues: orderedIDs.enumerated().map { ($0.element, $0.offset) })
        let boundsByID: [CGWindowID: CGRect] = Dictionary(
            uniqueKeysWithValues: orderedInfo.compactMap { info in
                guard let id = info[kCGWindowNumber as String] as? CGWindowID,
                      let bounds = info[kCGWindowBounds as String] as? [String: Any],
                      let rect = CGRect(dictionaryRepresentation: bounds as CFDictionary)
                else { return nil }
                return (id, rect)
            }
        )

        return content.windows.compactMap { window -> WindowMirrorTarget? in
            guard let app = window.owningApplication else { return nil }
            if app.processID == currentPID { return nil }
            if let currentBundle, app.bundleIdentifier == currentBundle { return nil }
            let frame = boundsByID[window.windowID] ?? window.frame
            guard frame.width >= 80, frame.height >= 80 else { return nil }
            let title = window.title ?? ""
            let appName = app.applicationName
            if title.isEmpty && appName.isEmpty { return nil }
            return WindowMirrorTarget(
                id: window.windowID,
                window: window,
                title: title,
                appName: appName,
                processID: app.processID,
                frame: frame
            )
        }
        .sorted { lhs, rhs in
            (order[lhs.id] ?? Int.max) < (order[rhs.id] ?? Int.max)
        }
    }
}

@MainActor
private final class WindowSelectionOverlayController {
    private let targets: [WindowMirrorTarget]
    private let onSelected: (WindowMirrorTarget) -> Void
    private let onCancelled: () -> Void
    private var windows: [NSWindow] = []
    private let desktopHeight: CGFloat

    init(
        targets: [WindowMirrorTarget],
        onSelected: @escaping (WindowMirrorTarget) -> Void,
        onCancelled: @escaping () -> Void
    ) {
        self.targets = targets
        self.onSelected = onSelected
        self.onCancelled = onCancelled
        self.desktopHeight = NSScreen.screens.map { $0.frame.maxY }.max() ?? 0
    }

    func present() {
        NSCursor.crosshair.set()
        windows = NSScreen.screens.map { screen in
            let view = WindowSelectionOverlayView(
                screenFrame: screen.frame,
                targets: targets,
                desktopHeight: desktopHeight,
                onSelected: onSelected,
                onCancelled: onCancelled
            )
            let window = SelectionOverlayWindow(screen: screen, contentView: view)
            window.makeKeyAndOrderFront(nil)
            return window
        }
        NSApp.activate(ignoringOtherApps: true)
    }

    func dismiss() {
        NSCursor.arrow.set()
        windows.forEach { $0.orderOut(nil) }
        windows.removeAll()
    }
}

private final class SelectionOverlayWindow: NSPanel {
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { true }

    init(screen: NSScreen, contentView: NSView) {
        super.init(
            contentRect: screen.frame,
            styleMask: [.borderless],
            backing: .buffered,
            defer: false
        )
        self.contentView = contentView
        isOpaque = false
        backgroundColor = .clear
        hasShadow = false
        level = .screenSaver
        ignoresMouseEvents = false
        acceptsMouseMovedEvents = true
        collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary]
    }
}

private final class WindowSelectionOverlayView: NSView {
    private let screenFrame: CGRect
    private let targets: [WindowMirrorTarget]
    private let desktopHeight: CGFloat
    private let onSelected: (WindowMirrorTarget) -> Void
    private let onCancelled: () -> Void
    private var hovered: WindowMirrorTarget? {
        didSet { needsDisplay = true }
    }

    init(
        screenFrame: CGRect,
        targets: [WindowMirrorTarget],
        desktopHeight: CGFloat,
        onSelected: @escaping (WindowMirrorTarget) -> Void,
        onCancelled: @escaping () -> Void
    ) {
        self.screenFrame = screenFrame
        self.targets = targets
        self.desktopHeight = desktopHeight
        self.onSelected = onSelected
        self.onCancelled = onCancelled
        super.init(frame: CGRect(origin: .zero, size: screenFrame.size))
        wantsLayer = true
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override var acceptsFirstResponder: Bool { true }

    override func updateTrackingAreas() {
        trackingAreas.forEach(removeTrackingArea)
        addTrackingArea(
            NSTrackingArea(
                rect: bounds,
                options: [.mouseMoved, .activeAlways, .inVisibleRect],
                owner: self,
                userInfo: nil
            )
        )
        super.updateTrackingAreas()
    }

    override func draw(_ dirtyRect: NSRect) {
        NSColor.black.withAlphaComponent(0.42).setFill()
        bounds.fill()

        guard let hovered else {
            drawInstruction("Click a window to bring it into XR · Esc cancels")
            return
        }

        let rect = nsRect(for: hovered).insetBy(dx: -3, dy: -3)
        NSColor.clear.setFill()
        rect.fill()
        NSColor(calibratedRed: 0.20, green: 0.95, blue: 0.95, alpha: 0.95).setStroke()
        let path = NSBezierPath(roundedRect: rect, xRadius: 8, yRadius: 8)
        path.lineWidth = 3
        path.stroke()
        drawLabel(hovered.displayTitle, at: rect)
    }

    override func mouseMoved(with event: NSEvent) {
        hovered = target(at: globalPoint(for: event))
    }

    override func mouseDragged(with event: NSEvent) {
        hovered = target(at: globalPoint(for: event))
    }

    override func mouseDown(with event: NSEvent) {
        if let target = target(at: globalPoint(for: event)) {
            onSelected(target)
        }
    }

    override func keyDown(with event: NSEvent) {
        if event.keyCode == 53 {
            onCancelled()
        } else {
            super.keyDown(with: event)
        }
    }

    private func globalPoint(for event: NSEvent) -> CGPoint {
        guard let window else { return .zero }
        let point = convert(event.locationInWindow, from: nil)
        return CGPoint(x: window.frame.minX + point.x, y: window.frame.minY + point.y)
    }

    private func target(at nsPoint: CGPoint) -> WindowMirrorTarget? {
        let cgPoint = CGPoint(x: nsPoint.x, y: desktopHeight - nsPoint.y)
        return targets.first { $0.frame.contains(cgPoint) }
    }

    private func nsRect(for target: WindowMirrorTarget) -> CGRect {
        let rect = CGRect(
            x: target.frame.minX,
            y: desktopHeight - target.frame.maxY,
            width: target.frame.width,
            height: target.frame.height
        )
        return rect
            .intersection(screenFrame)
            .offsetBy(dx: -screenFrame.minX, dy: -screenFrame.minY)
    }

    private func drawInstruction(_ text: String) {
        let attributes: [NSAttributedString.Key: Any] = [
            .font: NSFont.systemFont(ofSize: 18, weight: .semibold),
            .foregroundColor: NSColor.white
        ]
        let attributed = NSAttributedString(string: text, attributes: attributes)
        let size = attributed.size()
        let rect = CGRect(x: bounds.midX - size.width / 2, y: bounds.midY - size.height / 2, width: size.width, height: size.height)
        attributed.draw(in: rect)
    }

    private func drawLabel(_ text: String, at rect: CGRect) {
        let attributes: [NSAttributedString.Key: Any] = [
            .font: NSFont.systemFont(ofSize: 14, weight: .semibold),
            .foregroundColor: NSColor.white
        ]
        let attributed = NSAttributedString(string: text, attributes: attributes)
        let size = attributed.size()
        let labelRect = CGRect(
            x: rect.minX,
            y: min(bounds.maxY - size.height - 16, rect.maxY + 8),
            width: size.width + 18,
            height: size.height + 10
        )
        NSColor.black.withAlphaComponent(0.72).setFill()
        NSBezierPath(roundedRect: labelRect, xRadius: 8, yRadius: 8).fill()
        attributed.draw(in: labelRect.insetBy(dx: 9, dy: 5))
    }
}
