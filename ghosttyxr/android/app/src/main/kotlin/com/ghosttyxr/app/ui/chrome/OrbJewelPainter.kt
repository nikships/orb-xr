package com.ghosttyxr.app.ui.chrome

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.MeshGradientPainter
import com.ghosttyxr.app.terminal.theme.GhosttyTokens

/**
 * Shared hardware-rendered jewel mesh for premium and first-flight chrome.
 * The 2×2 mesh has 3×3 vertices; a dark perimeter keeps text contrast high
 * while cyan/violet light burns through the middle like the voice orb.
 */
@Composable
fun rememberOrbJewelPainter(): MeshGradientPainter = remember {
    MeshGradientPainter(rows = 2, columns = 2, hasBicubicColor = true) {
        val colors = listOf(
            GhosttyTokens.JewelInk,
            GhosttyTokens.JewelViolet,
            GhosttyTokens.JewelInk,
            GhosttyTokens.JewelBlue,
            GhosttyTokens.JewelHighlight,
            GhosttyTokens.JewelRose,
            GhosttyTokens.JewelInk,
            GhosttyTokens.JewelCyan,
            GhosttyTokens.JewelInk,
        )
        for (row in 0..2) {
            for (column in 0..2) {
                setVertex(
                    row = row,
                    column = column,
                    position = Offset(column / 2f, row / 2f),
                    color = colors[row * 3 + column],
                )
            }
        }
    }
}
