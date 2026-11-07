import AppKit
import SwiftUI

/// Presents the pairing QR on its own borderless window that covers the entire
/// screen, so it can be scanned from across the room. A plain SwiftUI overlay
/// can't do this because the main window is a fixed-size utility window.
@MainActor
final class FullScreenQRController {
    static let shared = FullScreenQRController()

    private var window: NSWindow?
    private var monitor: Any?

    func show(urlString: String) {
        dismiss()

        guard let screen = NSScreen.main else { return }
        let window = NSWindow(
            contentRect: screen.frame,
            styleMask: [.borderless],
            backing: .buffered,
            defer: false
        )
        window.level = .mainMenu + 1
        window.isOpaque = true
        window.backgroundColor = GhosttyTheme.nsJet
        window.appearance = NSAppearance(named: .darkAqua)
        window.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        window.isReleasedWhenClosed = false
        window.setFrame(screen.frame, display: true)

        let content = FullScreenQRView(urlString: urlString) { [weak self] in
            self?.dismiss()
        }
        window.contentView = NSHostingView(rootView: content)
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)

        // Esc / any key press dismisses.
        monitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
            if event.keyCode == 53 { // Escape
                self?.dismiss()
                return nil
            }
            return event
        }

        self.window = window
    }

    func dismiss() {
        if let monitor { NSEvent.removeMonitor(monitor) }
        monitor = nil
        window?.orderOut(nil)
        window?.close()
        window = nil
    }
}

private struct FullScreenQRView: View {
    let urlString: String
    let onDismiss: () -> Void

    var body: some View {
        ZStack {
            GhosttyTheme.jet.ignoresSafeArea()
            GeometryReader { geo in
                let side = min(geo.size.width, geo.size.height) * 0.82
                ZStack {
                    RoundedRectangle(cornerRadius: GhosttyTheme.corner)
                        .fill(.white)
                    QRImageView(payload: urlString)
                        .padding(side * 0.06)
                }
                .frame(width: side, height: side)
                .overlay(
                    RoundedRectangle(cornerRadius: GhosttyTheme.corner)
                        .strokeBorder(GhosttyTheme.neonCyan.opacity(0.5), lineWidth: 1)
                )
                .position(x: geo.size.width / 2, y: geo.size.height / 2)
            }
        }
        .preferredColorScheme(.dark)
        .overlay(alignment: .topLeading) {
            VStack(alignment: .leading, spacing: 6) {
                OrbSectionLabel(index: "01", title: "Pair headset")
                Text("ORB / XR")
                    .font(.system(size: 13, weight: .semibold, design: .monospaced))
                    .tracking(1.6)
                    .foregroundStyle(GhosttyTheme.textPrimary)
            }
            .padding(28)
        }
        .overlay(alignment: .topTrailing) {
            Button(action: onDismiss) {
                Image(systemName: "xmark")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(GhosttyTheme.textPrimary)
                    .frame(width: 40, height: 40)
                    .background(GhosttyTheme.raisedSurface)
                    .overlay(Rectangle().strokeBorder(GhosttyTheme.panelStrokeStrong, lineWidth: 1))
            }
            .buttonStyle(.plain)
            .help("Exit full screen (Esc)")
            .padding(28)
        }
        .overlay(alignment: .bottom) {
            Text("ESC  /  CLICK TO EXIT")
                .font(.system(size: 10, weight: .semibold, design: .monospaced))
                .tracking(0.9)
                .foregroundStyle(GhosttyTheme.dimText)
                .padding(.bottom, 28)
        }
        .contentShape(Rectangle())
        .onTapGesture(perform: onDismiss)
    }
}

struct QRImageView: View {
    let payload: String

    var body: some View {
        if let image = try? QRCodeService.generate(payload: payload, targetSize: 480, correctionLevel: "M") {
            Image(nsImage: image)
                .interpolation(.none)
                .resizable()
                .aspectRatio(contentMode: .fit)
        } else {
            ZStack {
                Color.red.opacity(0.2)
                Label("QR generation failed", systemImage: "xmark.octagon")
                    .foregroundStyle(.red)
            }
        }
    }
}
