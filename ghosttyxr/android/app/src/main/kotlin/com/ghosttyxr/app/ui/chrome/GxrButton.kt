package com.ghosttyxr.app.ui.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghosttyxr.app.terminal.theme.GhosttyTokens

/** Visual grammar for [GxrButton]: how loud the affordance is. */
enum class GxrButtonStyle { Primary, Outline, Danger, DangerFilled }

private data class ButtonColors(
    val container: Color,
    val content: Color,
    val edge: Color,
)

/**
 * Bracket button — the chrome kit's action affordance, styled after a TUI's
 * `[ label ]` hotspot: monospace label between accent brackets. [GxrButtonStyle.Primary]
 * fills with the accent; the others stay dark with a coloured edge.
 */
@Composable
fun GxrButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: GxrButtonStyle = GxrButtonStyle.Outline,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(6.dp)
    val colors = when (style) {
        GxrButtonStyle.Primary ->
            ButtonColors(GhosttyTokens.Accent, Color(0xFF00201F), GhosttyTokens.Accent)
        GxrButtonStyle.Outline ->
            ButtonColors(Color.Transparent, GhosttyTokens.OnSurface, GhosttyTokens.Outline)
        GxrButtonStyle.Danger ->
            ButtonColors(Color.Transparent, GhosttyTokens.Danger, GhosttyTokens.Danger.copy(alpha = 0.6f))
        GxrButtonStyle.DangerFilled ->
            ButtonColors(GhosttyTokens.Danger, Color.Black, GhosttyTokens.Danger)
    }
    val resolvedContent = if (enabled) colors.content else GhosttyTokens.OnSurfaceFaint
    val resolvedEdge = if (enabled) colors.edge else GhosttyTokens.Outline.copy(alpha = 0.4f)
    val resolvedFill = if (enabled) colors.container else Color.Transparent
    val bracket = when {
        !enabled -> GhosttyTokens.OnSurfaceFaint
        style == GxrButtonStyle.Primary -> resolvedContent
        style == GxrButtonStyle.Outline -> GhosttyTokens.Accent
        else -> resolvedContent
    }

    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 38.dp)
            .clip(shape)
            .background(resolvedFill, shape)
            .border(1.dp, resolvedEdge, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalGxrContentColor provides resolvedContent) {
            GxrText("[", color = bracket, fontSize = 13.sp)
            Box(modifier = Modifier.padding(horizontal = 8.dp)) { content() }
            GxrText("]", color = bracket, fontSize = 13.sp)
        }
    }
}

/**
 * Compact keycap — the accessory-row key button (Esc, Tab, arrows…). Styled as a physical
 * keyboard cap: fixed-size dark cell, hairline edge, monospace legend.
 */
@Composable
fun KeyCapButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(5.dp)
    Row(
        modifier = modifier
            .size(width = 60.dp, height = 36.dp)
            .clip(shape)
            .background(GhosttyTokens.SurfaceContainer, shape)
            .border(1.dp, GhosttyTokens.Outline, shape)
            .clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GxrText(label, fontSize = 13.sp, color = GhosttyTokens.OnSurface)
    }
}

/**
 * Minimal square hotspot for a single glyph — the chrome kit's `IconButton`.
 * No container by default; the glow indication provides feedback.
 */
@Composable
fun GxrIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 40.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
