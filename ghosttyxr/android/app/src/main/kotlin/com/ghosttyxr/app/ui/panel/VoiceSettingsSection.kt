package com.ghosttyxr.app.ui.panel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.sp
import com.ghosttyxr.app.R
import com.ghosttyxr.app.terminal.settings.GhosttySettingsStore
import com.ghosttyxr.app.terminal.theme.GhosttyTokens
import com.ghosttyxr.app.ui.chrome.GxrSection
import com.ghosttyxr.app.ui.chrome.GxrText

/**
 * "Voice" settings: the feature toggles and safety switches. All voice features are
 * Pro-only (server-managed STT/Gemini/TTS — no user API keys); when the user isn't
 * Pro the toggles still render but a one-line upsell explains the gate, and the
 * runtime paths surface "needs Pro" errors instead of recording.
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
    isPro: Boolean,
) {
    GxrSection("Voice", iconRes = R.drawable.gxr_ic_voice) {
        if (!isPro) {
            GxrText(
                text = "Voice features are part of Orb XR Pro — see the Pro section above.",
                fontSize = 11.sp,
                color = GhosttyTokens.Warning,
            )
        }

        SettingToggleRow(
            label = "Enable voice input",
            checked = voiceEnabled,
            onCheckedChange = onVoiceEnabledChanged,
        )
        GxrText(
            text = "Tap-to-talk dictation into the focused terminal. Audio is transcribed in " +
                "our cloud; training-data retention is opted out by default.",
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
                "it's transcribed and run by Gemini 3.5 Flash, with a short on-screen " +
                "confirmation. Closing terminals by voice isn't supported.",
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
            text = "The orb also says its confirmations and alerts out loud (Gemini Flash TTS), " +
                "so you don't have to re-read the toast. Off keeps them visual-only.",
            fontSize = 11.sp,
            color = GhosttyTokens.OnSurfaceVariant,
        )
    }
}
