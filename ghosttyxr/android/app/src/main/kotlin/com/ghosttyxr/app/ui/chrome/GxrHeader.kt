package com.ghosttyxr.app.ui.chrome

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghosttyxr.app.terminal.theme.GhosttyTokens

/**
 * Branded panel header: `👻 Orb XR` prompt line with a blinking block cursor — the
 * signature "this is a terminal" motif — plus a minimize affordance, over a fading accent
 * rule. Replaces the former Material wordmark header on the settings panel.
 */
@Composable
fun GxrPanelHeader(
    onMinimize: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GxrText("👻", fontSize = 16.sp)
                GxrText(
                    text = " Orb XR",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = GhosttyTokens.OnSurface,
                )
                BlinkingCursor()
            }
            GxrIconButton(
                onClick = onMinimize,
                modifier = Modifier.semantics { contentDescription = "Minimize panel" },
            ) {
                GxrText(text = "▾", fontSize = 18.sp, color = GhosttyTokens.OnSurfaceVariant)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(GhosttyTokens.Accent.copy(alpha = 0.55f), Color.Transparent),
                    ),
                ),
        )
    }
}

/** Hard-blinking block cursor (terminal cadence: no fade, on/off at ~1.1s period). */
@Composable
fun BlinkingCursor(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "gxr-cursor")
    val on by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1100
                1f at 0 using LinearEasing
                1f at 549
                0f at 550
                0f at 1099
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "gxr-cursor-blink",
    )
    GxrText(
        text = "▉",
        fontSize = 15.sp,
        color = GhosttyTokens.Accent,
        modifier = modifier.graphicsLayer { alpha = on },
    )
}
