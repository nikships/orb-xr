package com.ghosttyxr.app.ui.chrome

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghosttyxr.app.terminal.theme.GhosttyTokens

private val TrackWidth = 74.dp
private val TrackHeight = 30.dp
private val ThumbSize = 22.dp

/**
 * TUI bracket toggle — the chrome kit's switch. A `[ ON ]` / `[OFF ]` cell whose block
 * thumb slides between the ends, the label swapping sides like a status flag flipping.
 * Exposes real `Role.Switch` semantics via [Modifier.toggleable] so accessibility and
 * Compose tests see a genuine switch.
 */
@Composable
fun GxrSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(6.dp)
    val edge by animateColorAsState(
        targetValue = when {
            !enabled -> GhosttyTokens.Outline.copy(alpha = 0.4f)
            checked -> GhosttyTokens.Accent
            else -> GhosttyTokens.Outline
        },
        animationSpec = tween(140),
        label = "gxr-switch-edge",
    )
    val thumbColor by animateColorAsState(
        targetValue = when {
            !enabled -> GhosttyTokens.OnSurfaceFaint
            checked -> GhosttyTokens.Accent
            else -> GhosttyTokens.OnSurfaceVariant
        },
        animationSpec = tween(140),
        label = "gxr-switch-thumb",
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) TrackWidth - ThumbSize - 4.dp else 4.dp,
        animationSpec = tween(140),
        label = "gxr-switch-offset",
    )

    Box(
        modifier = modifier
            .size(width = TrackWidth, height = TrackHeight)
            .clip(shape)
            .background(if (checked) GhosttyTokens.Accent.copy(alpha = 0.12f) else Color.Transparent, shape)
            .border(1.dp, edge, shape)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
    ) {
        GxrText(
            text = if (checked) "ON" else "OFF",
            color = if (checked) GhosttyTokens.Accent else GhosttyTokens.OnSurfaceFaint,
            fontSize = 11.sp,
            modifier = Modifier
                .align(if (checked) Alignment.CenterStart else Alignment.CenterEnd)
                .padding(horizontal = 9.dp),
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset(x = thumbOffset.roundToPx(), y = 0) }
                .size(ThumbSize)
                .clip(RoundedCornerShape(4.dp))
                .background(thumbColor),
        )
    }
}
