package com.ghosttyxr.app.ui.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghosttyxr.app.terminal.theme.GhosttyTokens

/**
 * Selectable bracket chip — the chrome kit's `FilterChip`/segmented-option cell. Selected
 * chips light up their brackets and label in the accent; unselected chips sit as faint
 * outlines. Uses [Modifier.selectable] so semantics report selection state.
 */
@Composable
fun GxrChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(6.dp)
    val edge = if (selected) GhosttyTokens.Accent else GhosttyTokens.Outline.copy(alpha = 0.7f)
    val fill = if (selected) GhosttyTokens.Accent.copy(alpha = 0.14f) else Color.Transparent
    val labelColor = if (selected) GhosttyTokens.Accent else GhosttyTokens.OnSurfaceVariant

    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 34.dp)
            .clip(shape)
            .background(fill, shape)
            .border(1.dp, edge, shape)
            .selectable(selected = selected, role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GxrText(
            text = if (selected) "[x]" else "[ ]",
            color = if (selected) GhosttyTokens.Accent else GhosttyTokens.OnSurfaceFaint,
            fontSize = 12.sp,
            modifier = Modifier.padding(end = 7.dp),
        )
        GxrText(text = label, color = labelColor, fontSize = 12.sp, maxLines = 1)
    }
}
