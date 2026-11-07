package com.ghosttyxr.app.ui.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghosttyxr.app.terminal.theme.GhosttyTokens

/**
 * Shell-prompt text field — the chrome kit's input primitive: a dark cell with a `>`
 * prompt prefix and an accent block cursor, framed by a hairline that brightens to the
 * accent on focus. Supports password masking via [visualTransformation] like the former
 * `OutlinedTextField` call sites.
 */
@Composable
fun GxrTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(6.dp)
    val edge = if (focused) GhosttyTokens.Accent else GhosttyTokens.Outline

    val textStyle = LocalGxrTextStyle.current.copy(
        color = GhosttyTokens.OnSurface,
        fontSize = 13.sp,
    )

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        textStyle = textStyle,
        singleLine = singleLine,
        cursorBrush = SolidColor(GhosttyTokens.Accent),
        visualTransformation = visualTransformation,
        interactionSource = interactionSource,
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .defaultMinSize(minHeight = 42.dp)
                    .clip(shape)
                    .background(GhosttyTokens.Background.copy(alpha = 0.6f), shape)
                    .border(1.dp, edge, shape)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GxrText(
                    text = ">",
                    color = if (focused) GhosttyTokens.Accent else GhosttyTokens.OnSurfaceFaint,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Box(modifier = Modifier.weight(1f)) {
                    if (value.isEmpty() && placeholder != null) {
                        GxrText(
                            text = placeholder,
                            color = GhosttyTokens.OnSurfaceFaint,
                            fontSize = 13.sp,
                            maxLines = 1,
                        )
                    }
                    innerTextField()
                }
                if (focused) {
                    GxrText(text = "▉", color = GhosttyTokens.Accent.copy(alpha = 0.35f), fontSize = 13.sp)
                }
            }
        },
    )
}
