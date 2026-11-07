package com.ghosttyxr.app.terminal.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghosttyxr.app.R
import com.ghosttyxr.app.ui.chrome.GlowIndication
import com.ghosttyxr.app.ui.chrome.LocalGxrContentColor
import com.ghosttyxr.app.ui.chrome.LocalGxrTextStyle

/**
 * Central design tokens for the Orb XR spatial UI **chrome** — everything around the
 * terminal glyphs, which libghostty paints itself. Pulling these out of scattered
 * `Color(0x..)` literals keeps the floating panels, controls, and overlays visually
 * coherent and easy to retune in one place.
 *
 * The design language is "official Ghostty": the chrome reads as a TUI floating in
 * space — JetBrains Mono everywhere (Ghostty's default face), near-black ink surfaces,
 * box-drawing section frames, one bright cyan accent, and ANSI-mapped status colours.
 * Chrome text targets the >=7:1 contrast the XR visual-design guidance recommends for
 * reading at distance.
 */
object GhosttyTokens {
    // Surfaces (deep ink; intentionally not pure black so panels read as solid slabs).
    val Background = Color(0xFF0A0E13)
    val Surface = Color(0xFF10151C)
    val SurfaceContainer = Color(0xFF141B24)
    val SurfaceContainerHigh = Color(0xFF10151C)
    val Outline = Color(0xFF2C3A49)

    // Content.
    val OnSurface = Color(0xFFE7EDF4)
    val OnSurfaceVariant = Color(0xFFA6B6C7)
    val OnSurfaceFaint = Color(0xFF7E8EA0)

    // Accent + status (status trio maps to ANSI green/yellow/red).
    val Accent = Color(0xFF3DDADF)         // focused pane + interactive highlight
    val IdleBorder = Color(0x33A6B6C7)     // unfocused pane border (subtle)
    val Online = Color(0xFF6BE675)
    val Danger = Color(0xFFFF8A80)
    val Warning = Color(0xFFFFC773)

    // Orb-jewel mesh palette. Kept here so Pro/coach chrome share one luminous
    // hardware-painted language instead of scattering decorative color literals.
    val JewelInk = Color(0xFF070A12)
    val JewelViolet = Color(0xFF4A1F78)
    val JewelBlue = Color(0xFF174C88)
    val JewelCyan = Color(0xFF148F99)
    val JewelRose = Color(0xFF7A285E)
    val JewelHighlight = Color(0xFF52E5DD)
    val JewelOverlay = Color(0xB810151C)
    val JewelOutline = Color(0x9948DDE0)

    // Translucent scrim for overlays floating over the terminal canvas.
    val ScrimStrong = Color(0xCC0A0E13)

    // Geometry. 24dp content corners sit just under the 32dp panel-corner guidance,
    // leaving room for the panel's own inset. Chrome inside panels squares off to a
    // tight 6-14dp so cells read as terminal chrome, not Material pills.
    val PaneCorner = 24.dp
    val ChromeCorner = 10.dp
    val GlowOutset = 16.dp

    // JetBrains Mono — Ghostty's default typeface, bundled in res/font.
    val Mono = FontFamily(
        Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
        Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
        Font(R.font.jetbrains_mono_semibold, FontWeight.SemiBold),
        Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
    )

    // Type scale. Everything chrome is monospace so labels line up with the terminal grid.
    val Body = TextStyle(fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Normal)
    val PaneTitle = TextStyle(fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    val StatusLabel = TextStyle(fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    val Caption = TextStyle(fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Normal)
}

/**
 * App-wide theme for Orb XR's bespoke chrome kit (`ui/chrome`). Installs the JetBrains
 * Mono base text style, the ambient content colour, and the phosphor-glow indication that
 * replaces the Material ripple. Wrap the whole composition in this so every panel, overlay,
 * and control inherits the terminal-native look — there is intentionally no MaterialTheme
 * underneath.
 */
@Composable
fun GhosttyXRTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalGxrContentColor provides GhosttyTokens.OnSurface,
        LocalGxrTextStyle provides GhosttyTokens.Body,
        LocalIndication provides GlowIndication,
        content = content,
    )
}
