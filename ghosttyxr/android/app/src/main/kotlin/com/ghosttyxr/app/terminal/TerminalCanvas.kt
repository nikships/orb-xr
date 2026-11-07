package com.ghosttyxr.app.terminal

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * Canvas-based terminal grid renderer. Ports the proven batched-text-run approach from chuchu's
 * TerminalCanvas: backgrounds are drawn as merged horizontal rectangles per row, glyphs are
 * grouped into runs of same fg color and rendered with one [android.graphics.Canvas.drawText]
 * call per run. Phase 1: read-only; future phases will add selection, scrolling, gestures,
 * and image (kitty protocol) placement.
 */
@Composable
fun TerminalCanvas(
    snapshot: TerminalSnapshot,
    modifier: Modifier = Modifier,
    fontSizeSp: Float = 14f,
    cursorColor: Color = Color.White.copy(alpha = 0.35f),
    cursorTextColor: Color? = null,
    neonGlow: Boolean = false,
    backgroundOpacity: Float = 1f,
    paneLabel: String = "Terminal",
    onResize: (cols: Int, rows: Int, cellWidth: Int, cellHeight: Int, widthPx: Int, heightPx: Int) -> Unit =
        { _, _, _, _, _, _ -> },
) {
    val density = LocalDensity.current
    val fontSizePx = with(density) { fontSizeSp.sp.toPx() }
    val context = LocalContext.current
    val terminalTypeface = remember(context) { loadTerminalTypeface(context) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var lastResizedGrid by remember { mutableStateOf(Pair(0, 0)) }

    val textPaint = remember(fontSizePx, terminalTypeface) {
        createTextPaint(fontSizePx, terminalTypeface)
    }
    // Neon glow: pool one Paint per fg so setShadowLayer is not reconfigured every run.
    val glowPaintPool = remember(fontSizePx, terminalTypeface) { HashMap<Int, Paint>(64) }
    val bgPaint = remember {
        Paint().apply {
            isAntiAlias = false
            style = Paint.Style.FILL
        }
    }
    val cursorPaint = remember {
        Paint().apply {
            isAntiAlias = false
            style = Paint.Style.FILL
        }
    }
    val glyphCache = remember {
        HashMap<Int, String>(256).apply {
            for (cp in 33..126) {
                this[cp] = cp.toChar().toString()
            }
        }
    }
    val drawBuffer = remember { StringBuilder(256) }
    val imageSrcRect = remember { Rect() }
    val imageDstRect = remember { RectF() }
    val fontMetrics = textPaint.fontMetrics
    val measuredHeight = fontMetrics.descent - fontMetrics.ascent
    val cellHeightPx = if (measuredHeight > 1f) measuredHeight else 16f
    val measuredWidth = textPaint.measureText("M")
    val cellWidthPx = if (measuredWidth > 1f) measuredWidth else 8f
    val baselineOffset = -fontMetrics.ascent
    val cellWidthInt = max(1, ceil(cellWidthPx).toInt())
    val cellHeightInt = max(1, ceil(cellHeightPx).toInt())
    // Half-cell padding so glyphs near rounded corners aren't clipped; scales with font size.
    val edgePaddingDp = with(density) { (cellWidthPx / 2f).toDp() }
    val edgePaddingVerticalDp = with(density) { (cellHeightPx / 2f).toDp() }
    val clampedBackgroundOpacity = backgroundOpacity.coerceIn(0f, 1f)
    val cursorColorArgb = cursorColor.toArgb()
    val cursorTextColorArgb = cursorTextColor?.toArgb()

    // Side effect only: writing State during composition would loop snapshot updates.
    LaunchedEffect(canvasSize, cellWidthPx, cellHeightPx) {
        if (canvasSize.width <= 0 || canvasSize.height <= 0) return@LaunchedEffect
        val cols = max(1, floor(canvasSize.width / cellWidthPx).toInt())
        val rows = max(1, floor(canvasSize.height / cellHeightPx).toInt())
        val grid = cols to rows
        if (grid != lastResizedGrid) {
            lastResizedGrid = grid
            onResize(cols, rows, cellWidthInt, cellHeightInt, canvasSize.width, canvasSize.height)
        }
    }

    // Explicit semantics: Skia drawText does not create Compose semantics nodes.
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = edgePaddingDp, vertical = edgePaddingVerticalDp)
            .testTag(TERMINAL_CANVAS_TEST_TAG)
            .semantics {
                contentDescription = "$paneLabel (${snapshot.cols}×${snapshot.rows})"
            }
            .onSizeChanged { size -> canvasSize = size },
    ) {
        val cols = max(snapshot.cols, 1)
        val rows = max(snapshot.rows, 1)

        if (clampedBackgroundOpacity > 0f) {
            drawRect(color = Color(snapshot.defaultBgArgb).copy(alpha = clampedBackgroundOpacity))
        }

        drawIntoCanvas { canvas ->
            val nCanvas = canvas.nativeCanvas
            val sb = drawBuffer
            val defaultBg = snapshot.defaultBgArgb

            for (row in 0 until rows) {
                val rowStart = row * cols
                val y = row * cellHeightPx
                val baseline = y + baselineOffset

                // Background runs: merge consecutive cells with the same bg color.
                var i = rowStart
                val rowEnd = rowStart + cols
                while (i < rowEnd) {
                    val bg = snapshot.bgArgb[i]
                    var j = i + 1
                    while (j < rowEnd && snapshot.bgArgb[j] == bg) {
                        j++
                    }
                    if (bg != defaultBg && clampedBackgroundOpacity > 0f) {
                        bgPaint.color = withAlpha(bg, clampedBackgroundOpacity)
                        nCanvas.drawRect(
                            (i - rowStart) * cellWidthPx,
                            y,
                            (j - rowStart) * cellWidthPx,
                            y + cellHeightPx,
                            bgPaint,
                        )
                    }
                    i = j
                }

                // Text runs: group consecutive non-blank glyphs sharing the same fg color into
                // one drawText call.
                i = rowStart
                while (i < rowEnd) {
                    val cp = snapshot.codepoints[i]
                    if (isBlankCell(cp)) {
                        i++
                        continue
                    }
                    val fg = snapshot.fgArgb[i]
                    sb.setLength(0)
                    val startCol = i - rowStart

                    while (i < rowEnd) {
                        val c = snapshot.codepoints[i]
                        val nextFg = snapshot.fgArgb[i]
                        if (isBlankCell(c) || nextFg != fg) break
                        val glyph = glyphCache.getOrPut(c) { String(Character.toChars(c)) }
                        sb.append(glyph)
                        i++
                    }

                    val runPaint = if (neonGlow) {
                        glowPaintPool.getOrPut(fg) {
                            createTextPaint(fontSizePx, terminalTypeface).apply {
                                color = fg
                                setShadowLayer(fontSizePx * 0.35f, 0f, 0f, fg)
                            }
                        }
                    } else {
                        textPaint.color = fg
                        textPaint
                    }
                    nCanvas.drawText(sb, 0, sb.length, startCol * cellWidthPx, baseline, runPaint)
                }
            }

            // Image placements (kitty protocol). No-op when JNI is not yet wired.
            for (img in snapshot.images) {
                imageSrcRect.set(img.srcX, img.srcY, img.srcX + img.srcW, img.srcY + img.srcH)
                imageDstRect.set(
                    img.destX.toFloat(),
                    img.destY.toFloat(),
                    (img.destX + img.destW).toFloat(),
                    (img.destY + img.destH).toFloat(),
                )
                nCanvas.drawBitmap(img.bitmap, imageSrcRect, imageDstRect, null)
            }

            if (snapshot.cursorVisible &&
                snapshot.cursorX in 0 until cols &&
                snapshot.cursorY in 0 until rows
            ) {
                val cursorLeft = snapshot.cursorX * cellWidthPx
                val cursorTop = snapshot.cursorY * cellHeightPx
                cursorPaint.color = cursorColorArgb
                nCanvas.drawRect(
                    cursorLeft,
                    cursorTop,
                    cursorLeft + cellWidthPx,
                    cursorTop + cellHeightPx,
                    cursorPaint,
                )

                val cursorIndex = snapshot.cursorY * cols + snapshot.cursorX
                if (cursorTextColorArgb != null && cursorIndex in snapshot.codepoints.indices) {
                    val codepoint = snapshot.codepoints[cursorIndex]
                    if (!isBlankCell(codepoint)) {
                        val glyph = glyphCache.getOrPut(codepoint) {
                            String(Character.toChars(codepoint))
                        }
                        textPaint.color = cursorTextColorArgb
                        nCanvas.drawText(
                            glyph,
                            cursorLeft,
                            cursorTop + baselineOffset,
                            textPaint,
                        )
                    }
                }
            }
        }
    }
}

private fun isBlankCell(codepoint: Int): Boolean = codepoint == 0 || codepoint == 32

private fun createTextPaint(fontSizePx: Float, typeface: Typeface): Paint =
    Paint().apply {
        isAntiAlias = true
        textAlign = Paint.Align.LEFT
        textSize = fontSizePx
        this.typeface = typeface
    }

private fun withAlpha(argb: Int, alpha: Float): Int {
    val alphaByte = (alpha.coerceIn(0f, 1f) * 255f).toInt()
    return (argb and 0x00FFFFFF) or (alphaByte shl 24)
}

// JetBrains Mono Nerd Font Mono: fixed-width with Powerline/Nerd icons (single-cell).
// Process-wide cache; falls back to MONOSPACE if the asset is missing.
@Volatile
private var cachedTerminalTypeface: Typeface? = null

private fun loadTerminalTypeface(context: android.content.Context): Typeface {
    cachedTerminalTypeface?.let { return it }
    return synchronized(TerminalCanvasFontLock) {
        cachedTerminalTypeface ?: run {
            val loaded = runCatching {
                Typeface.createFromAsset(
                    context.applicationContext.assets,
                    "fonts/JetBrainsMonoNerdFontMono-Regular.ttf",
                )
            }.getOrNull() ?: Typeface.MONOSPACE
            cachedTerminalTypeface = loaded
            loaded
        }
    }
}

private object TerminalCanvasFontLock
