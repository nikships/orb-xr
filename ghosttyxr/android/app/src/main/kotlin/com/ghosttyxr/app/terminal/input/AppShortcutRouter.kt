package com.ghosttyxr.app.terminal.input

import android.view.KeyEvent

/** App-level keyboard shortcuts handled before terminal input. */
sealed interface AppShortcutAction {
    data object ToggleVoiceCapture : AppShortcutAction
    data class FocusPane(val paneId: Int) : AppShortcutAction
}

/**
 * Shared shortcut router for locally attached Android keyboards and the Mac
 * host-keyboard relay. Keeping these rules in one place makes remote input
 * behave like a native headset keyboard for app-scoped shortcuts.
 */
object AppShortcutRouter {
    fun local(event: KeyEvent, layoutLocked: Boolean): AppShortcutAction? {
        if (VoiceHotkey.isVoiceToggle(event)) {
            return if (VoiceHotkey.isTogglePress(event)) AppShortcutAction.ToggleVoiceCapture else null
        }
        if (layoutLocked && PaneNavShortcuts.isShortcutKey(event)) {
            return PaneNavShortcuts.match(event)?.let(AppShortcutAction::FocusPane)
        }
        return null
    }

    fun shouldConsumeLocal(event: KeyEvent, layoutLocked: Boolean): Boolean {
        if (VoiceHotkey.isVoiceToggle(event)) return true
        return layoutLocked && PaneNavShortcuts.isShortcutKey(event)
    }

    fun remote(code: String, mods: Int, action: Int, layoutLocked: Boolean): AppShortcutAction? {
        if (isRemoteVoiceToggle(code, mods)) {
            return if (action == GhosttyKeyAction.Press) AppShortcutAction.ToggleVoiceCapture else null
        }
        if (layoutLocked && isRemotePaneShortcut(code, mods)) {
            return remotePaneIndex(code)?.takeIf { action == GhosttyKeyAction.Press }?.let(AppShortcutAction::FocusPane)
        }
        return null
    }

    fun shouldConsumeRemote(code: String, mods: Int, layoutLocked: Boolean): Boolean {
        if (isRemoteVoiceToggle(code, mods)) return true
        return layoutLocked && isRemotePaneShortcut(code, mods)
    }

    private fun isRemoteVoiceToggle(code: String, mods: Int): Boolean {
        return code == "Space" && hasRemoteAltWithoutControlOrSuper(mods)
    }

    private fun isRemotePaneShortcut(code: String, mods: Int): Boolean {
        return hasRemoteAltWithoutControlOrSuper(mods) && remotePaneIndex(code) != null
    }

    private fun hasRemoteAltWithoutControlOrSuper(mods: Int): Boolean =
        mods and GhosttyKeyMods.CTRL_ALT_SUPER == GhosttyKeyMods.ALT

    private fun remotePaneIndex(code: String): Int? {
        if (!code.startsWith("Digit")) return null
        val digit = code.removePrefix("Digit").toIntOrNull() ?: return null
        if (digit !in 1..PaneNavShortcuts.MAX_PANES) return null
        return digit - 1
    }
}
