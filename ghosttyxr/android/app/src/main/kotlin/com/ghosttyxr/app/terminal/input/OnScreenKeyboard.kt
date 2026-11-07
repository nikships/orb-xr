package com.ghosttyxr.app.terminal.input

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.SoundEffectOnInteraction
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Compose-only on-screen keyboard fallback. Three layers stacked vertically:
 *
 *  1. **Sticky modifier chips** (Ctrl / Alt / Shift / Meta) — see [StickyKeyDispatcher] for
 *     the latch-until-next-press semantics.
 *  2. **Accessory row** for keys that don't surface as plain text via the IME — Esc, Tab,
 *     arrows, page nav, Home / End, Del.
 *  3. **Near-invisible [BasicTextField]** that holds focus and shows the system IME. Every
 *     character the user types is converted into a synthetic [MappedKey] Press+Release pair
 *     and handed to [sink]. The field is reset to a zero-width sentinel after each emission
 *     so the IME doesn't accumulate state; backspace is detected by the sentinel
 *     disappearing and surfaced as a synthetic Backspace press+release.
 */
@Composable
fun OnScreenKeyboard(
    sink: TerminalInputSink,
    modifier: Modifier = Modifier,
) {
    val dispatcher = rememberStickyKeyDispatcher(sink)

    SoundEffectOnInteraction(enabled = false) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(KeySurfaceBackground)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StickyModifierChips(dispatcher)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AccessoryKeyButtons(dispatcher)
            }
            ImeBridgeField(onText = { codepoint, key -> dispatcher.tap(key, codepoint) })
        }
    }
}

/** Zero-width space — invisible but non-empty so the IME never sees a fully-empty buffer. */
private const val SENTINEL = "​"

@Composable
private fun ImeBridgeField(onText: (codepoint: Int, key: Int) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(SENTINEL)) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focusRequester) {
        runCatching { focusRequester.requestFocus() }
    }
    Box(modifier = Modifier.size(width = 1.dp, height = 1.dp)) {
        BasicTextField(
            value = value,
            onValueChange = { new ->
                when {
                    new.text.isEmpty() -> {
                        // IME deleted the sentinel — treat as backspace.
                        onText(0, GhosttyKey.backspace)
                        value = TextFieldValue(SENTINEL)
                    }
                    new.text == SENTINEL -> value = new
                    else -> {
                        val added = new.text.removePrefix(SENTINEL)
                        var i = 0
                        while (i < added.length) {
                            val cp = added.codePointAt(i)
                            onText(cp, physicalKeyFor(cp))
                            i += Character.charCount(cp)
                        }
                        value = TextFieldValue(SENTINEL)
                    }
                }
            },
            modifier = Modifier.focusRequester(focusRequester),
            textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
            cursorBrush = SolidColor(Color.Transparent),
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = false,
                capitalization = KeyboardCapitalization.None,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Default,
            ),
            singleLine = true,
        )
    }
}

/**
 * Best-effort mapping from a printable codepoint back to the physical [GhosttyKey] the user
 * "would have pressed." Used so libghostty's encoder sees a sensible key on top of the UTF-8
 * payload. Returns [GhosttyKey.unidentified] for anything we can't classify — libghostty's
 * legacy path handles those via the codepoint alone.
 */
private fun physicalKeyFor(codepoint: Int): Int = when (codepoint) {
    '\n'.code, '\r'.code -> GhosttyKey.enter
    '\t'.code -> GhosttyKey.tab
    in Char.MIN_VALUE.code..Char.MAX_VALUE.code ->
        codepoint.toChar().toGhosttyKey() ?: GhosttyKey.unidentified
    else -> GhosttyKey.unidentified
}
