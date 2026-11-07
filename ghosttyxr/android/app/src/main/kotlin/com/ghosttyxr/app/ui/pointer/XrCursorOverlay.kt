package com.ghosttyxr.app.ui.pointer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

/**
 * Draws the Symbiosis XR cursor when it's inside target [id]. Layer this over the
 * target's content (it's per-panel: each SpatialPanel window draws its own slice
 * of the shared cockpit-space cursor).
 */
@Composable
fun XrCursorOverlay(router: XrPointerRouter?, id: String, modifier: Modifier = Modifier) {
    if (router == null) return
    val cursor by router.cursor.collectAsState()
    val layout by router.layout.collectAsState()
    if (!cursor.active || cursor.targetId != id) return
    val rect = layout[id] ?: return
    val (fx, fy) = CockpitSpace.fractionIn(rect, cursor.x, cursor.y)

    Canvas(modifier = modifier.fillMaxSize()) {
        val center = Offset(fx * size.width, fy * size.height)
        drawCircle(color = Color.Black.copy(alpha = 0.55f), radius = 13f, center = center)
        drawCircle(color = Color.White, radius = 9f, center = center)
        drawCircle(color = Color(0xFF20F2F2), radius = 4f, center = center)
    }
}
