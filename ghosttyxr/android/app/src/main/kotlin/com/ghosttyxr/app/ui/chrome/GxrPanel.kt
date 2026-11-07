package com.ghosttyxr.app.ui.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LayerOutsets
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ghosttyxr.app.terminal.theme.GhosttyTokens

/**
 * The chrome kit's surface primitive — a terminal-window slab: flat fill, hairline
 * outline, no elevation/tonal tricks. Provides [LocalGxrContentColor] to its content.
 * Replaces the former `material3.Surface` call sites.
 */
@Composable
fun GxrPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(GhosttyTokens.ChromeCorner),
    color: Color = GhosttyTokens.Surface,
    backgroundPainter: Painter? = null,
    contentColor: Color = GhosttyTokens.OnSurface,
    borderColor: Color? = GhosttyTokens.Outline,
    borderWidth: Dp = 1.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            // SpatialPanel's backing layer otherwise crops GlowIndication at the panel edge.
            // Keep this outer layer un-clipped; only the backdrop slab below follows [shape].
            .graphicsLayer(outsets = LayerOutsets(GhosttyTokens.GlowOutset)),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
            .then(
                backgroundPainter?.let {
                    Modifier.paint(it, contentScale = ContentScale.FillBounds)
                } ?: Modifier,
            )
            .background(color, shape)
            .then(
                if (borderColor != null) Modifier.border(borderWidth, borderColor, shape)
                else Modifier,
            ),
        )
        CompositionLocalProvider(LocalGxrContentColor provides contentColor) {
            content()
        }
    }
}
