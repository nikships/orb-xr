package com.ghosttyxr.app.ui.chrome

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import com.ghosttyxr.app.terminal.theme.GhosttyTokens
import kotlin.math.max
import kotlin.math.round

/**
 * The chrome kit's slider, drawn like a terminal progress rail: a thin track with faint
 * tick marks, a filled accent run, and a block-cursor `▉` thumb. Drag or tap anywhere on
 * the rail; [onValueChangeFinished] fires on release like the Material contract. Exposes
 * `progressBarRangeInfo` + `setProgress` semantics for accessibility and UI tests.
 *
 * @param steps number of *interior* stops (Material convention); 0 = continuous.
 */
@Composable
fun GxrSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val currentValue by rememberUpdatedState(value)
    val currentOnChange by rememberUpdatedState(onValueChange)
    val currentOnFinished by rememberUpdatedState(onValueChangeFinished)
    val range by rememberUpdatedState(valueRange)
    val stepCount by rememberUpdatedState(steps)

    var widthPx by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    val trackColor = GhosttyTokens.Outline
    val fillColor = if (enabled) GhosttyTokens.Accent else GhosttyTokens.OnSurfaceFaint
    val thumbColor = if (enabled) GhosttyTokens.Accent else GhosttyTokens.OnSurfaceFaint
    val tickColor = GhosttyTokens.Outline.copy(alpha = 0.8f)
    val thumbWidthDp = 10.dp
    val thumbHeightDp = 22.dp
    val density = LocalDensity.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp)
            .onSizeChanged { size -> widthPx = size.width.toFloat() }
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(currentValue, range, stepCount)
                if (enabled) {
                    setProgress { target ->
                        currentOnChange(snapSliderValue(target, range, stepCount))
                        currentOnFinished?.invoke()
                        true
                    }
                }
            }
            .focusable(enabled)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onTap = { offset ->
                    currentOnChange(sliderValueAt(offset.x, widthPx, range, stepCount))
                    currentOnFinished?.invoke()
                })
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        currentOnChange(sliderValueAt(offset.x, widthPx, range, stepCount))
                    },
                    onDragEnd = {
                        dragging = false
                        currentOnFinished?.invoke()
                    },
                    onDragCancel = {
                        dragging = false
                        currentOnFinished?.invoke()
                    },
                ) { change, _ ->
                    change.consume()
                    currentOnChange(sliderValueAt(change.position.x, widthPx, range, stepCount))
                }
            }
            .drawBehind {
                val centerY = size.height / 2f
                val trackHeight = 3.dp.toPx()
                val span = range.endInclusive - range.start
                val fraction = if (span > 0f) {
                    ((currentValue - range.start) / span).coerceIn(0f, 1f)
                } else {
                    0f
                }
                val thumbX = fraction * size.width

                // Rail.
                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(0f, centerY - trackHeight / 2f),
                    size = Size(size.width, trackHeight),
                    cornerRadius = CornerRadius(trackHeight / 2f),
                )
                // Sparse tick marks (at most ~12 so dense step counts don't dissolve into noise).
                if (stepCount in 1..48) {
                    val tickEvery = max(1, (stepCount + 1) / 12)
                    for (i in 1..stepCount) {
                        if (i % tickEvery != 0) continue
                        val x = size.width * i / (stepCount + 1)
                        drawLine(
                            color = tickColor,
                            start = Offset(x, centerY - 5.dp.toPx()),
                            end = Offset(x, centerY + 5.dp.toPx()),
                            strokeWidth = 1f,
                        )
                    }
                }
                // Filled run.
                drawRoundRect(
                    color = fillColor,
                    topLeft = Offset(0f, centerY - trackHeight / 2f),
                    size = Size(thumbX, trackHeight),
                    cornerRadius = CornerRadius(trackHeight / 2f),
                )
                // Block-cursor thumb.
                val thumbW = with(density) { thumbWidthDp.toPx() }
                val thumbH = with(density) { thumbHeightDp.toPx() } * if (dragging) 1.15f else 1f
                drawRoundRect(
                    color = thumbColor,
                    topLeft = Offset(
                        // size.width can be momentarily narrower than the thumb while the
                        // settings panel is animating between its collapsed (72dp gear) and
                        // expanded sizes — clamp the upper bound so coerceIn never sees
                        // max < min and throws.
                        (thumbX - thumbW / 2f).coerceIn(0f, (size.width - thumbW).coerceAtLeast(0f)),
                        centerY - thumbH / 2f,
                    ),
                    size = Size(thumbW, thumbH),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                )
            },
    )
}

private fun sliderValueAt(
    x: Float,
    width: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
): Float {
    if (width <= 0f) return range.start
    val fraction = (x / width).coerceIn(0f, 1f)
    return snapSliderValue(
        raw = range.start + fraction * (range.endInclusive - range.start),
        range = range,
        steps = steps,
    )
}

private fun snapSliderValue(
    raw: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
): Float {
    val clamped = raw.coerceIn(range.start, range.endInclusive)
    val span = range.endInclusive - range.start
    if (steps <= 0 || span <= 0f) return clamped
    val stepSize = span / (steps + 1)
    val index = round((clamped - range.start) / stepSize)
    return (range.start + index * stepSize).coerceIn(range.start, range.endInclusive)
}
