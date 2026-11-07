package com.ghosttyxr.app.ui.chrome

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghosttyxr.app.terminal.theme.GhosttyTokens

/**
 * TUI-framed settings group — the chrome kit's section card. Draws a real box-drawing
 * frame (`┌─ TITLE ───┐` style) as crisp Canvas strokes with the title text inset into a
 * gap on the top rule, exactly like a terminal UI's titled border. Replaces the previous
 * Material card-per-section look.
 *
 * [iconRes] optionally insets a small glyph in the top rule's gap, ahead of the title —
 * the generated cyan HUD icon set under `drawable-nodpi/gxr_ic_*`.
 */
@Composable
fun GxrSection(
    title: String,
    modifier: Modifier = Modifier,
    @DrawableRes iconRes: Int? = null,
    content: @Composable () -> Unit,
) {
    val frameColor = GhosttyTokens.Outline
    val titleColor = GhosttyTokens.Accent
    val titleStyle = LocalGxrTextStyle.current.merge(
        TextStyle(color = titleColor, fontSize = 11.sp),
    )
    val textMeasurer = rememberTextMeasurer()
    val titleLayout = remember(title, titleStyle, textMeasurer) {
        textMeasurer.measure(title, style = titleStyle)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val stroke = Stroke(width = 1.dp.toPx())
                val inset = 0.5.dp.toPx()
                val topY = 8.dp.toPx() // frame rule sits at the title's vertical centre
                val corner = 6.dp.toPx()
                val gapStart = 14.dp.toPx()
                val iconSpan = if (iconRes != null) 20.dp.toPx() else 0f
                val gapEnd = gapStart + iconSpan + titleLayout.size.width + 8.dp.toPx()
                val path = Path().apply {
                    // Top rule, split around the title gap.
                    moveTo(gapEnd, topY)
                    lineTo(size.width - inset - corner, topY)
                    arcTo(
                        Rect(
                            Offset(size.width - inset - 2 * corner, topY),
                            Size(2 * corner, 2 * corner),
                        ),
                        startAngleDegrees = 270f,
                        sweepAngleDegrees = 90f,
                        forceMoveTo = false,
                    )
                    lineTo(size.width - inset, size.height - inset - corner)
                    arcTo(
                        Rect(
                            Offset(size.width - inset - 2 * corner, size.height - inset - 2 * corner),
                            Size(2 * corner, 2 * corner),
                        ),
                        startAngleDegrees = 0f,
                        sweepAngleDegrees = 90f,
                        forceMoveTo = false,
                    )
                    lineTo(inset + corner, size.height - inset)
                    arcTo(
                        Rect(
                            Offset(inset, size.height - inset - 2 * corner),
                            Size(2 * corner, 2 * corner),
                        ),
                        startAngleDegrees = 90f,
                        sweepAngleDegrees = 90f,
                        forceMoveTo = false,
                    )
                    lineTo(inset, topY + corner)
                    arcTo(
                        Rect(Offset(inset, topY), Size(2 * corner, 2 * corner)),
                        startAngleDegrees = 180f,
                        sweepAngleDegrees = 90f,
                        forceMoveTo = false,
                    )
                    lineTo(gapStart, topY)
                }
                drawPath(path, frameColor, style = stroke)
            },
    ) {
        // The title floats in the top rule's gap, verbatim (tests match on it).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 18.dp),
        ) {
            iconRes?.let { res ->
                Image(
                    painter = painterResource(res),
                    contentDescription = null,
                    modifier = Modifier.padding(end = 4.dp).size(16.dp),
                )
            }
            GxrText(
                text = title,
                style = titleStyle,
                maxLines = 1,
            )
        }
        Column(
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
    }
}
