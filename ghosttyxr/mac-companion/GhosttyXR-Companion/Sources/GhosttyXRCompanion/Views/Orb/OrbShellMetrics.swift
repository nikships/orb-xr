import CoreGraphics
import Foundation

/// Geometry shared by the orb shell's SwiftUI layout and the AppKit hit-testing that
/// makes the rest of the transparent panel click-through. Both sides derive from these
/// constants, so they cannot drift apart.
///
/// All points are in the shell's SwiftUI content space: origin top-left, y down.
enum OrbShellMetrics {
    /// One seed for the Mac's orb, so it always looks like the same creature.
    static let seed = "orb-desktop"

    static let orbSize: CGFloat = 132
    /// Panel is deliberately much larger than the visible chrome: the menu fans above
    /// the orb and panels drop below it, and neither may be clipped by the window.
    static let contentSize = CGSize(width: 480, height: 780)
    /// Leaves room for the topmost menu item (96pt up) plus its 24pt radius.
    static let orbCenter = CGPoint(x: 240, y: 140)

    static let menuRadius: CGFloat = 116
    static let menuItemSize: CGFloat = 48
    static let panelWidth: CGFloat = 300
    static let panelGap: CGFloat = 18

    static var orbRadius: CGFloat { orbSize / 2 }
    /// Where a panel's top edge sits — just below the orb.
    static var panelTop: CGFloat { orbCenter.y + orbRadius + panelGap }
    static var panelMaxHeight: CGFloat { contentSize.height - panelTop - 8 }

    static var orbFrame: CGRect {
        CGRect(
            x: orbCenter.x - orbRadius,
            y: orbCenter.y - orbRadius,
            width: orbSize,
            height: orbSize
        )
    }

    /// Offset of a menu item's centre from the orb centre, matching the reference
    /// prototype's arc (y grows downward, so negative angles fan upward).
    static func menuOffset(degrees: Double) -> CGPoint {
        let radians = degrees * .pi / 180
        return CGPoint(x: cos(radians) * menuRadius, y: sin(radians) * menuRadius)
    }

    static func menuItemFrame(degrees: Double) -> CGRect {
        let offset = menuOffset(degrees: degrees)
        let centre = CGPoint(x: orbCenter.x + offset.x, y: orbCenter.y + offset.y)
        return CGRect(
            x: centre.x - menuItemSize / 2,
            y: centre.y - menuItemSize / 2,
            width: menuItemSize,
            height: menuItemSize
        )
    }
}

/// The interactive regions of the orb shell at this instant. Everything else in the
/// panel is click-through so the terminal underneath stays usable.
///
/// Written by the SwiftUI shell, read by `OrbContainerView.hitTest`.
@MainActor
final class OrbHitRegions {
    /// True while the radial menu is fanned out.
    var menuOpen = false
    /// The active panel's rect in content space, if a panel is showing. Measured by
    /// SwiftUI rather than assumed, because panel height depends on its content.
    var panelRect: CGRect?

    func contains(_ point: CGPoint) -> Bool {
        if hypot(point.x - OrbShellMetrics.orbCenter.x, point.y - OrbShellMetrics.orbCenter.y)
            <= OrbShellMetrics.orbRadius {
            return true
        }
        if menuOpen {
            for item in OrbMenuItem.allCases {
                let frame = OrbShellMetrics.menuItemFrame(degrees: item.angle)
                let centre = CGPoint(x: frame.midX, y: frame.midY)
                if hypot(point.x - centre.x, point.y - centre.y) <= OrbShellMetrics.menuItemSize / 2 {
                    return true
                }
            }
        }
        if let panelRect, panelRect.contains(point) {
            return true
        }
        return false
    }
}
