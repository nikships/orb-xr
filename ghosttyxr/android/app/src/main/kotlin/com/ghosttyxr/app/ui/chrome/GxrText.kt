package com.ghosttyxr.app.ui.chrome

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import com.ghosttyxr.app.terminal.theme.GhosttyTokens

/**
 * Ambient content colour for the bespoke chrome kit — the hand-rolled equivalent of
 * Material's `LocalContentColor`, provided by [GxrPanel] surfaces and the app theme.
 */
val LocalGxrContentColor = compositionLocalOf { GhosttyTokens.OnSurface }

/** Ambient base text style (JetBrains Mono) merged under every [GxrText]. */
val LocalGxrTextStyle = compositionLocalOf { GhosttyTokens.Body }

/**
 * The chrome kit's text primitive: [BasicText] + the ambient JetBrains Mono style and
 * content colour. Drop-in for the former `material3.Text` call sites.
 */
@Composable
fun GxrText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    style: TextStyle = LocalGxrTextStyle.current,
) {
    val resolvedColor = when {
        color.isSpecified -> color
        style.color.isSpecified -> style.color
        else -> LocalGxrContentColor.current
    }
    val merged = style.merge(
        TextStyle(
            color = resolvedColor,
            fontSize = fontSize,
            fontWeight = fontWeight,
            textAlign = textAlign ?: TextAlign.Unspecified,
        ),
    )
    BasicText(
        text = text,
        modifier = modifier,
        style = merged,
        maxLines = maxLines,
        overflow = overflow,
    )
}
