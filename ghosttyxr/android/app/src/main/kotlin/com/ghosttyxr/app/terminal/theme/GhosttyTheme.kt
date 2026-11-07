package com.ghosttyxr.app.terminal.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

/**
 * Parsed representation of a single Ghostty theme file (`themes/<name>` in app assets).
 *
 * The file format is a flat key/value list — see Ghostty's [theme documentation]
 * (https://ghostty.org/docs/config/reference#theme). We parse the same subset chuchu does:
 *
 *  - `background`, `foreground`
 *  - `cursor-color`, `cursor-text`
 *  - `selection-background`, `selection-foreground`
 *  - `palette = <idx>=<#rrggbb>` lines for indices `0..15`
 *
 * Anything else (font, integers, etc.) is ignored.
 */
data class GhosttyTheme(
    val name: String,
    val background: Color,
    val foreground: Color,
    val cursorColor: Color,
    val cursorText: Color,
    val selectionBackground: Color,
    val selectionForeground: Color,
    val palette: List<Color>,
) {
    companion object {
        fun parse(name: String, content: String): GhosttyTheme {
            val props = mutableMapOf<String, String>()
            val paletteColors = arrayOfNulls<Color>(16)

            content.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .forEach { line ->
                    val eqIndex = line.indexOf('=')
                    if (eqIndex < 0) return@forEach
                    val key = line.substring(0, eqIndex).trim()
                    val value = line.substring(eqIndex + 1).trim()

                    if (key != "palette") {
                        props[key] = value
                        return@forEach
                    }

                    val sepIndex = value.indexOf('=')
                    if (sepIndex < 0) return@forEach
                    val index = value.substring(0, sepIndex).toIntOrNull() ?: return@forEach
                    if (index !in paletteColors.indices) return@forEach
                    paletteColors[index] = parseHexColor(value.substring(sepIndex + 1))
                }

            val bg = parseHexColor(props["background"] ?: "#000000")
            val fg = parseHexColor(props["foreground"] ?: "#ffffff")

            return GhosttyTheme(
                name = name,
                background = bg,
                foreground = fg,
                cursorColor = parseHexColor(props["cursor-color"] ?: props["foreground"] ?: "#ffffff"),
                cursorText = parseHexColor(props["cursor-text"] ?: props["background"] ?: "#000000"),
                selectionBackground = parseHexColor(props["selection-background"] ?: props["foreground"] ?: "#ffffff"),
                selectionForeground = parseHexColor(props["selection-foreground"] ?: props["background"] ?: "#000000"),
                palette = List(16) { paletteColors[it] ?: defaultTerminalPaletteColor(it) },
            )
        }

        private fun parseHexColor(hex: String): Color {
            val h = hex.trimStart('#')
            val argb = when (h.length) {
                6 -> h.toLongOrNull(16)?.let { 0xFF000000 or it }
                8 -> h.toLongOrNull(16)
                else -> null
            }
            return Color((argb ?: 0xFF000000).toInt())
        }
    }
}

/** Encodes the 256-color palette into the byte layout libghostty expects from `nativeSetDefaultColors`. */
fun GhosttyTheme.toTerminalPaletteBytes(): ByteArray {
    val colors = MutableList(256, ::defaultTerminalPaletteColor)
    for (index in palette.indices) {
        colors[index] = palette[index]
    }

    return ByteArray(colors.size * 3).also { bytes ->
        colors.forEachIndexed { index, color ->
            val offset = index * 3
            bytes[offset] = colorChannelToByte(color.red)
            bytes[offset + 1] = colorChannelToByte(color.green)
            bytes[offset + 2] = colorChannelToByte(color.blue)
        }
    }
}

/** Packs a [Color] as `[r, g, b]` ints in 0..255. Useful for the JNI fg/bg arguments. */
fun Color.toRgbIntArray(): IntArray = intArrayOf(
    colorChannelToInt(red),
    colorChannelToInt(green),
    colorChannelToInt(blue),
)

/**
 * Pre-packed argument bundle for `GhosttyBridge.nativeSetDefaultColors`. Lets call sites push
 * a whole theme to libghostty in one struct without each call site importing the individual
 * conversion extensions.
 */
data class BridgeColorArgs(
    val fg: IntArray,
    val bg: IntArray,
    val cursor: IntArray,
    val palette: ByteArray,
)

fun toBridgeColorArgs(theme: GhosttyTheme): BridgeColorArgs = BridgeColorArgs(
    fg = theme.foreground.toRgbIntArray(),
    bg = theme.background.toRgbIntArray(),
    cursor = theme.cursorColor.toRgbIntArray(),
    palette = theme.toTerminalPaletteBytes(),
)

private fun colorChannelToInt(value: Float): Int =
    (value * 255f).roundToInt().coerceIn(0, 255)

private fun colorChannelToByte(value: Float): Byte = colorChannelToInt(value).toByte()

private fun defaultTerminalPaletteColor(index: Int): Color {
    if (index in DEFAULT_ANSI_COLORS.indices) {
        return DEFAULT_ANSI_COLORS[index]
    }

    return when (index) {
        in 16..231 -> {
            val cubeIndex = index - 16
            val red = XTERM_CUBE_LEVELS[cubeIndex / 36]
            val green = XTERM_CUBE_LEVELS[(cubeIndex / 6) % 6]
            val blue = XTERM_CUBE_LEVELS[cubeIndex % 6]
            Color(red = red / 255f, green = green / 255f, blue = blue / 255f)
        }

        in 232..255 -> {
            val level = 8 + (index - 232) * 10
            Color(red = level / 255f, green = level / 255f, blue = level / 255f)
        }

        else -> Color(0xFF000000)
    }
}

private val DEFAULT_ANSI_COLORS = listOf(
    Color(0xFF000000),
    Color(0xFF800000),
    Color(0xFF008000),
    Color(0xFF808000),
    Color(0xFF000080),
    Color(0xFF800080),
    Color(0xFF008080),
    Color(0xFFC0C0C0),
    Color(0xFF808080),
    Color(0xFFFF0000),
    Color(0xFF00FF00),
    Color(0xFFFFFF00),
    Color(0xFF0000FF),
    Color(0xFFFF00FF),
    Color(0xFF00FFFF),
    Color(0xFFFFFFFF),
)

private val XTERM_CUBE_LEVELS = intArrayOf(0, 95, 135, 175, 215, 255)
