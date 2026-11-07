package com.ghosttyxr.app.wiring

import com.ghosttyxr.app.terminal.session.CockpitLifecycle
import com.ghosttyxr.app.terminal.session.CockpitState
import com.ghosttyxr.app.terminal.settings.GhosttySettingsStore
import com.ghosttyxr.app.terminal.voice.GeminiKeyStore
import com.ghosttyxr.app.terminal.handoff.HandoffResult
import com.ghosttyxr.app.terminal.voice.command.LiveAudioStreamer
import com.ghosttyxr.app.terminal.voice.command.LiveCommandEngine
import com.ghosttyxr.app.terminal.voice.command.LiveCommandSession
import com.ghosttyxr.app.terminal.voice.command.LiveDictationController
import com.ghosttyxr.app.terminal.voice.command.PlayerLiveSpeechOutput
import com.ghosttyxr.app.terminal.voice.command.TranscriptSpeech
import com.ghosttyxr.app.terminal.voice.command.VoiceCommandController
import com.ghosttyxr.app.terminal.voice.tts.PetSpeechPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Construction of the voice controllers, factored out of `MainActivity` so the Activity stays
 * lifecycle-only. Every cockpit dependency the controllers need (pane count, focus, send-to-pane)
 * is injected here as a lambda — the same sources the rest of the input layer reads — keeping the
 * controllers' own logic Android-/Firebase-free and unit-testable (see their tests).
 */

/**
 * Build the voice dictation controller: Live input-audio transcriptions → focused pane.
 * Replaces the old batch path (record utterance → `stt` Cloud Function → insert). The mic button
 * drives [LiveDictationController.toggle]; MainActivity wires the controller into the shared
 * [LiveCommandSession] (`.dictation`) so transcriptions flow from the one Live connection.
 * Injection mirrors the old batch-STT builder so routing stays unit-testable.
 */
fun buildVoiceDictationController(
    scope: CoroutineScope,
    cockpit: CockpitLifecycle,
    focusedPaneId: () -> Int,
): LiveDictationController = LiveDictationController(
    scope = scope,
    paneCount = { cockpit.activePanes().size },
    // submitCommand (not sendInput): a dictated line is injected in one shot, so it must wait
    // for the channel to go inbound-quiescent first — otherwise a remote program's in-flight
    // capability-query reply (DA/Kitty) can share its write window and the shell splices the
    // reply's printable tail ("0u", "62;…") onto the command line.
    sendToPane = { paneId, bytes -> cockpit.activePanes().getOrNull(paneId)?.submitCommand(bytes) },
    focusedPaneIdProvider = focusedPaneId,
    autoSubmitProvider = { GhosttySettingsStore.voiceAutoSubmit.value },
    hasKeyProvider = { GeminiKeyStore.get() != null },
)

/**
 * Build the voice **command** controller (cockpit control). Pure dispatcher: the repository/focus
 * operations are injected as lambdas — the same sources the rest of the input layer uses — so the
 * controller's logic (index validation, result JSON) stays Firebase-free and unit-tested.
 * [LiveCommandEngine] adapts the model's function calls onto it. Close-terminal maps onto the
 * same [CockpitLifecycle.removePane] path as the control panel.
 */
fun buildVoiceCommandController(
    cockpit: CockpitLifecycle,
    focusPane: (paneId: Int) -> Unit,
    /** Live focused pane so new terminals inherit that pane's working directory. */
    focusedPaneId: () -> Int = { 0 },
    /** Live capability check — hoisted out of composition by MainActivity. */
    immersiveAvailable: () -> Boolean = { false },
    /**
     * Send all live terminals to the Mac companion (attach handoff). MainActivity passes
     * [com.ghosttyxr.app.terminal.handoff.TerminalHandoffController.handoffAll]; the default stub
     * reports the feature as unavailable so a partially-wired build degrades to a spoken error.
     */
    sendTerminalsToMac: suspend () -> HandoffResult = {
        HandoffResult(0, "Sending terminals to the Mac isn't available right now.")
    },
): VoiceCommandController = VoiceCommandController(
    paneCount = { cockpit.activePanes().size },
    addPane = { launchCommand ->
        cockpit.addPane(
            launchCommand = launchCommand,
            inheritFrom = cockpit.activePanes().getOrNull(focusedPaneId())?.id,
        )?.let { pane ->
            cockpit.activePanes().indexOfFirst { it.id == pane }
                .takeIf { it >= 0 }
                ?.also(focusPane)
        }
    },
    focusPane = focusPane,
    removePane = { paneId -> cockpit.activePanes().getOrNull(paneId)?.let { cockpit.removePane(it.id) } },
    // submitCommand (not sendInput): see buildVoiceController — a Gemini command line is injected
    // in one shot and must wait for channel quiescence so query replies can't merge into it.
    sendToPane = { paneId, bytes -> cockpit.activePanes().getOrNull(paneId)?.submitCommand(bytes) },
    // Read the pane's latest visible screen so the model can inspect a pane before acting.
    readPane = { paneId, lines -> cockpit.activePanes().getOrNull(paneId)?.snapshot?.value?.lastNonBlankLines(lines).orEmpty() },
    immersiveAvailable = immersiveAvailable,
    immersiveMode = { GhosttySettingsStore.immersiveMode.value },
    setImmersiveMode = GhosttySettingsStore::setImmersiveMode,
    sendTerminalsToMac = sendTerminalsToMac,
)

/**
 * Build the voice **command** session over the Gemini Live API (`gemini-3.8-live`): one
 * persistent bidirectional stream carries mic audio in and model speech + function calls back,
 * replacing the old three-hop pipeline (record → `stt` function → `gemini-3.5-flash` chat →
 * `petTts` function). The engine + controller stay
 * Firebase-free and unit-tested; this session is the only Firebase/audio-touching file.
 *
 * [fallbackSpeech] speaks reply text when the model produced transcript but no audio frames
 * (or playback failed). Production passes the prebaked-clip path so fixed notices
 * ("didn't catch that") still play bundled audio with zero network.
 */
fun buildVoiceCommandSession(
    controller: VoiceCommandController,
    scope: CoroutineScope,
    cockpit: CockpitLifecycle,
    fallbackSpeech: TranscriptSpeech = TranscriptSpeech { },
    speechOutput: com.ghosttyxr.app.terminal.voice.command.LiveSpeechOutput =
        PlayerLiveSpeechOutput(
            player = PetSpeechPlayer(),
        ),
): LiveCommandSession {
    val engine = LiveCommandEngine(
        controller = controller,
        audioOutput = speechOutput,
    )
    // Plaintext fallback only fires for transcript-without-audio turns (the engine gates it),
    // so the common case (user heard model speech) never double-speaks.
    scope.launchSpeechFallback(engine, fallbackSpeech)
    return LiveCommandSession(
        controller = controller,
        engine = engine,
        streamer = LiveAudioStreamer(),
        scope = scope,
        ambientContext = { cockpit.voiceAmbientContext() },
    )
}

private fun CoroutineScope.launchSpeechFallback(
    engine: LiveCommandEngine,
    fallbackSpeech: TranscriptSpeech,
) {
    launch {
        engine.events.collect { event ->
            when (event) {
                is com.ghosttyxr.app.terminal.voice.command.LiveCommandEvent.SpeakText ->
                    runCatching { fallbackSpeech.speak(event.text) }
            }
        }
    }
}

private fun CockpitLifecycle.activePanes() =
    (state.value as? CockpitState.Active)?.panes.orEmpty()

private fun CockpitLifecycle.voiceAmbientContext(): String {
    val paneLines = activePanes().mapIndexed { index, pane ->
        "- terminal ${index + 1}: key=${pane.id.value}, state=${pane.state.value::class.simpleName}"
    }
    return buildString {
        appendLine("Current terminals:")
        append(paneLines.joinToString("\n").ifEmpty { "- none" })
    }
}
