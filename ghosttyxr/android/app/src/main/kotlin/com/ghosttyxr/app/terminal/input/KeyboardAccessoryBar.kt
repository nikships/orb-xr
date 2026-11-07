package com.ghosttyxr.app.terminal.input

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.SoundEffectOnInteraction
import androidx.compose.ui.unit.dp

/**
 * Standalone on-screen accessory row: sticky modifier chips (Ctrl / Alt / Shift / Meta)
 * followed by buttons for Esc, Tab, arrows, page nav, Home / End, Del — see
 * [StickyKeyDispatcher] for the latch-until-next-press semantics.
 *
 * Standalone from [OnScreenKeyboard] because the team plan calls for this bar to be reusable
 * outside the full-keyboard fallback — e.g. always-visible accessory chrome above the panel,
 * or in the in-XR settings panel later.
 */
@Composable
fun KeyboardAccessoryBar(
    sink: TerminalInputSink,
    modifier: Modifier = Modifier,
) {
    val dispatcher = rememberStickyKeyDispatcher(sink)
    SoundEffectOnInteraction(enabled = false) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(KeySurfaceBackground)
                .padding(8.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StickyModifierChips(dispatcher)
            AccessoryKeyButtons(dispatcher)
        }
    }
}
