package com.ghosttyxr.app.ui.chrome

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.dp
import com.ghosttyxr.app.terminal.theme.GhosttyTokens
import kotlinx.coroutines.launch

/**
 * Orb XR's replacement for the Material ripple: interactive chrome answers hover/focus
 * (gaze/ray on XR) with a faint accent glow and press with a brighter flash + 1px edge,
 * like a phosphor terminal cell lighting up. Installed app-wide via `LocalIndication`
 * in [com.ghosttyxr.app.terminal.theme.GhosttyXRTheme].
 */
object GlowIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        GlowIndicationNode(interactionSource)

    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = "GlowIndication".hashCode()
}

private class GlowIndicationNode(
    private val interactionSource: InteractionSource,
) : Modifier.Node(), DrawModifierNode {
    private val hoverAlpha = Animatable(0f)
    private val pressAlpha = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            var hovers = 0
            var focuses = 0
            var presses = 0
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is HoverInteraction.Enter -> hovers++
                    is HoverInteraction.Exit -> hovers = (hovers - 1).coerceAtLeast(0)
                    is FocusInteraction.Focus -> focuses++
                    is FocusInteraction.Unfocus -> focuses = (focuses - 1).coerceAtLeast(0)
                    is PressInteraction.Press -> presses++
                    is PressInteraction.Release -> presses = (presses - 1).coerceAtLeast(0)
                    is PressInteraction.Cancel -> presses = (presses - 1).coerceAtLeast(0)
                }
                launch {
                    hoverAlpha.animateTo(
                        targetValue = if (hovers > 0 || focuses > 0) 1f else 0f,
                        animationSpec = tween(durationMillis = 120),
                    ) { invalidateDraw() }
                }
                launch {
                    pressAlpha.animateTo(
                        targetValue = if (presses > 0) 1f else 0f,
                        animationSpec = tween(durationMillis = 90),
                    ) { invalidateDraw() }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val fill = hoverAlpha.value * 0.06f + pressAlpha.value * 0.10f
        if (fill > 0f) {
            drawRect(color = GhosttyTokens.Accent, alpha = fill)
        }
        val edge = hoverAlpha.value * 0.35f + pressAlpha.value * 0.25f
        if (edge > 0f) {
            drawRect(color = GhosttyTokens.Accent, alpha = edge, style = Stroke(width = 1.dp.toPx()))
        }
    }
}
