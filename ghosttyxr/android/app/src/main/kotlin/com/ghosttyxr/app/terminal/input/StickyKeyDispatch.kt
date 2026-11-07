package com.ghosttyxr.app.terminal.input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.ghosttyxr.app.ui.chrome.GxrChip
import com.ghosttyxr.app.ui.chrome.KeyCapButton

/** Shared translucent backdrop for the on-screen key surfaces. */
internal val KeySurfaceBackground = Color(0xCC0B0F14)

/**
 * Shared dispatch state for the on-screen key surfaces ([KeyboardAccessoryBar],
 * [OnScreenKeyboard]): sticky modifiers latch until the next non-modifier press,
 * then auto-clear — matching chuchu's accessory behaviour and the common
 * terminal-keyboard convention.
 */
internal class StickyKeyDispatcher(
    private val sink: State<TerminalInputSink>,
) {
    var stickyMods by mutableStateOf(0)
        private set

    fun toggleMod(modBit: Int) {
        stickyMods = stickyMods xor modBit
    }

    /** Applies latched modifiers to unmodified keys, clearing them after a press. */
    fun fire(key: MappedKey) {
        val combined = if (stickyMods != 0 && key.mods == 0) key.copy(mods = stickyMods) else key
        sink.value.onKey(combined)
        if (stickyMods != 0 && key.action == GhosttyKeyAction.Press) {
            stickyMods = 0
        }
    }

    /**
     * Emits a [GhosttyKeyAction.Press] then [GhosttyKeyAction.Release] so libghostty sees a
     * complete key transition (matters for Kitty keyboard protocol).
     */
    fun tap(key: Int, codepoint: Int) {
        fire(MappedKey(key = key, codepoint = codepoint, mods = 0, action = GhosttyKeyAction.Press))
        fire(MappedKey(key = key, codepoint = codepoint, mods = 0, action = GhosttyKeyAction.Release))
    }
}

@Composable
internal fun rememberStickyKeyDispatcher(sink: TerminalInputSink): StickyKeyDispatcher {
    val currentSink = rememberUpdatedState(sink)
    return remember { StickyKeyDispatcher(currentSink) }
}

/** Sticky modifier chips (Ctrl / Alt / Shift / Meta) bound to [dispatcher]. */
@Composable
internal fun StickyModifierChips(dispatcher: StickyKeyDispatcher) {
    ModChip("Ctrl", GhosttyKeyMods.CONTROL, dispatcher)
    ModChip("Alt", GhosttyKeyMods.ALT, dispatcher)
    ModChip("Shift", GhosttyKeyMods.SHIFT, dispatcher)
    ModChip("Meta", GhosttyKeyMods.SUPER, dispatcher)
}

@Composable
private fun ModChip(label: String, modBit: Int, dispatcher: StickyKeyDispatcher) {
    GxrChip(
        label = label,
        selected = dispatcher.stickyMods and modBit != 0,
        onClick = { dispatcher.toggleMod(modBit) },
    )
}

/** Buttons for keys that don't surface as plain text via the system IME. */
@Composable
internal fun AccessoryKeyButtons(dispatcher: StickyKeyDispatcher) {
    KeyCapButton(label = "Esc", onClick = { dispatcher.tap(GhosttyKey.escape, 0) })
    KeyCapButton(label = "Tab", onClick = { dispatcher.tap(GhosttyKey.tab, '\t'.code) })
    KeyCapButton(label = "↑", onClick = { dispatcher.tap(GhosttyKey.arrowUp, 0) })
    KeyCapButton(label = "↓", onClick = { dispatcher.tap(GhosttyKey.arrowDown, 0) })
    KeyCapButton(label = "←", onClick = { dispatcher.tap(GhosttyKey.arrowLeft, 0) })
    KeyCapButton(label = "→", onClick = { dispatcher.tap(GhosttyKey.arrowRight, 0) })
    KeyCapButton(label = "PgUp", onClick = { dispatcher.tap(GhosttyKey.pageUp, 0) })
    KeyCapButton(label = "PgDn", onClick = { dispatcher.tap(GhosttyKey.pageDown, 0) })
    KeyCapButton(label = "Home", onClick = { dispatcher.tap(GhosttyKey.home, 0) })
    KeyCapButton(label = "End", onClick = { dispatcher.tap(GhosttyKey.end, 0) })
    KeyCapButton(label = "Del", onClick = { dispatcher.tap(GhosttyKey.delete, 0) })
}
