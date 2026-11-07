import AppKit
import SwiftUI

/// Orb XR's dark industrial visual language.
///
/// The palette stays rooted in the product's cyan, violet, emerald, and sunset
/// accents. Chrome uses flat technical surfaces, precise hairlines, compact corners,
/// and monospaced labels rather than soft glass or decorative depth.
enum GhosttyTheme {
    // MARK: Surfaces

    /// Page / panel base.
    static let jet = Color(red: 0.039, green: 0.039, blue: 0.039) // #0a0a0a
    /// Flat panel and inset surfaces over jet.
    static let glassFill = Color.white.opacity(0.055)
    static let glassFillStrong = Color.white.opacity(0.09)
    static let raisedSurface = Color(red: 0.063, green: 0.063, blue: 0.063) // #101010
    static let insetSurface = Color.white.opacity(0.03)

    /// Hairline rings.
    static let panelStroke = Color.white.opacity(0.10)
    static let panelStrokeSubtle = Color.white.opacity(0.05)
    static let panelStrokeStrong = Color.white.opacity(0.18)

    // MARK: Geometry

    static let corner: CGFloat = 3
    static let insetCorner: CGFloat = 2

    // MARK: Text

    /// `--foreground` / nimbus.
    static let textPrimary = Color(red: 0.961, green: 0.961, blue: 0.961) // #f5f5f5
    /// Secondary body (~primary/70).
    static let textSecondary = Color.white.opacity(0.70)
    /// `--muted-foreground`.
    static let dimText = Color(red: 0.553, green: 0.565, blue: 0.584) // #8d9095

    // MARK: Accents (softened toward site cyan / emerald / sunset)

    static let neonCyan = Color(red: 0.55, green: 0.88, blue: 0.95) // cyan-200-ish
    static let neonMagenta = Color(red: 0.78, green: 0.62, blue: 0.98) // soft violet
    static let neonGreen = Color(red: 0.45, green: 0.90, blue: 0.70) // emerald
    static let neonAmber = Color(red: 1.00, green: 0.59, blue: 0.40) // sunset #ff9766

    /// AppKit jet for window chrome (must match `jet`).
    static var nsJet: NSColor {
        NSColor(srgbRed: 0.039, green: 0.039, blue: 0.039, alpha: 1)
    }
}

/// Numbered technical eyebrow used above sections and panel titles.
struct OrbSectionLabel: View {
    let index: String
    let title: String
    var tint: Color = GhosttyTheme.neonCyan

    var body: some View {
        HStack(spacing: 6) {
            Text(index)
                .foregroundStyle(tint)
            Text(title.uppercased())
                .foregroundStyle(GhosttyTheme.dimText)
        }
        .font(.system(size: 9, weight: .semibold, design: .monospaced))
        .tracking(1.15)
    }
}

/// Flat bordered surface for status, metadata, and settings groups.
struct OrbInsetPanel<Content: View>: View {
    var padding: CGFloat = 10
    @ViewBuilder var content: () -> Content

    var body: some View {
        content()
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(GhosttyTheme.insetSurface)
            .overlay(
                RoundedRectangle(cornerRadius: GhosttyTheme.insetCorner)
                    .strokeBorder(GhosttyTheme.panelStrokeSubtle, lineWidth: 1)
            )
    }
}

/// Square, dark text fields that match the rest of the companion chrome.
struct OrbTextFieldStyle: TextFieldStyle {
    func _body(configuration: TextField<Self._Label>) -> some View {
        configuration
            .font(.system(size: 11, design: .monospaced))
            .foregroundStyle(GhosttyTheme.textPrimary)
            .padding(.horizontal, 9)
            .padding(.vertical, 7)
            .background(GhosttyTheme.insetSurface)
            .overlay(
                RoundedRectangle(cornerRadius: GhosttyTheme.corner)
                    .strokeBorder(GhosttyTheme.panelStroke, lineWidth: 1)
            )
    }
}
