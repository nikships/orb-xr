package com.ghosttyxr.app.ui.panel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.sp
import com.ghosttyxr.app.R
import com.ghosttyxr.app.terminal.settings.GhosttySettingsStore
import com.ghosttyxr.app.terminal.theme.GhosttyTokens
import com.ghosttyxr.app.ui.chrome.GxrSection
import com.ghosttyxr.app.ui.chrome.GxrText
import com.ghosttyxr.app.ui.chrome.GxrTextField
import com.ghosttyxr.app.ui.chrome.GxrButton
import com.ghosttyxr.app.terminal.voice.GeminiKeyStore

/**
 * Voice settings, including the user's Gemini key and safety switches.
 *
 * Auto-submit carries a one-line warning since inserting a misheard transcript into
 * a live shell that then runs it is the obvious footgun (plan §5/§11.4).
 */
@Composable
internal fun VoicePanelSection(
    voiceEnabled: Boolean,
    onVoiceEnabledChanged: (Boolean) -> Unit,
    voiceAutoSubmit: Boolean,
    onVoiceAutoSubmitChanged: (Boolean) -> Unit,
    voiceSilenceAutoStop: Boolean,
    onVoiceSilenceAutoStopChanged: (Boolean) -> Unit,
    voiceCommandsEnabled: Boolean,
    onVoiceCommandsEnabledChanged: (Boolean) -> Unit,
) {
    GxrSection("Voice", iconRes = R.drawable.gxr_ic_voice) {
        var key by remember { mutableStateOf("") }
        var saved by remember { mutableStateOf(GeminiKeyStore.get() != null) }
        GxrText("Your Gemini API key", fontSize = 13.sp)
        GxrTextField(
            value = key,
            onValueChange = { key = it },
            placeholder = if (saved) "Key saved; enter a replacement" else "Paste your Gemini API key",
            visualTransformation = PasswordVisualTransformation(),
        )
        GxrButton(onClick = {
            if (key.isNotBlank()) {
                GeminiKeyStore.set(key)
                key = ""
                saved = true
            }
        }) { GxrText("Save key") }
        if (saved) {
            GxrButton(onClick = { GeminiKeyStore.set(""); key = ""; saved = false }) {
                GxrText("Remove key")
            }
        }
        GxrText("Voice audio and terminal context are sent directly to Gemini using your key.",
            fontSize = 11.sp, color = GhosttyTokens.OnSurfaceVariant)

        SettingToggleRow(
            label = "Enable voice input",
            checked = voiceEnabled,
            onCheckedChange = onVoiceEnabledChanged,
        )
        GxrText(
            text = "Tap-to-talk dictation into the focused terminal using your Gemini key.",
            fontSize = 11.sp,
            color = GhosttyTokens.OnSurfaceVariant,
        )

        if (voiceEnabled) {
            SettingToggleRow(
                label = "Auto-submit on transcript",
                checked = voiceAutoSubmit,
                onCheckedChange = onVoiceAutoSubmitChanged,
            )
            GxrText(
                text = "⚠ Runs the transcript immediately (appends Enter). A misheard command " +
                    "will execute. Leave off to review before running.",
                fontSize = 11.sp,
                color = GhosttyTokens.Warning,
            )

            SettingToggleRow(
                label = "Silence auto-stop",
                checked = voiceSilenceAutoStop,
                onCheckedChange = onVoiceSilenceAutoStopChanged,
            )
            GxrText(
                text = "Stop recording automatically after a pause. Off avoids clipping mid-thought.",
                fontSize = 11.sp,
                color = GhosttyTokens.OnSurfaceVariant,
            )
        }

        SettingToggleRow(
            label = "Voice commands",
            checked = voiceCommandsEnabled,
            onCheckedChange = onVoiceCommandsEnabledChanged,
        )
        GxrText(
            text = "Operate the cockpit by voice — \"new terminal\", \"focus terminal two\", " +
                "\"tell terminal three to refactor auth\". Tap the command mic, speak one command; " +
                "Gemini handles the request and shows a short confirmation.",
            fontSize = 11.sp,
            color = GhosttyTokens.OnSurfaceVariant,
        )

        // Reads the store directly (like GhosttyXRApp's handMenuEnabled) rather than threading
        // another param pair through TerminalControlPanel's already-wide signature.
        val petSpeechEnabled by GhosttySettingsStore.petSpeechEnabled.collectAsState()
        SettingToggleRow(
            label = "Speak replies",
            checked = petSpeechEnabled,
            onCheckedChange = GhosttySettingsStore::setPetSpeechEnabled,
        )
        GxrText(
            text = "The orb also says its confirmations and alerts out loud, " +
                "so you don't have to re-read the toast. Off keeps them visual-only.",
            fontSize = 11.sp,
            color = GhosttyTokens.OnSurfaceVariant,
        )
    }
}
