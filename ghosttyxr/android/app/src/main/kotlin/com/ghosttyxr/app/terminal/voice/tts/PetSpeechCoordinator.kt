package com.ghosttyxr.app.terminal.voice.tts

import com.ghosttyxr.app.terminal.voice.VoiceMessages
import com.ghosttyxr.app.terminal.voice.command.VoiceCommandState
import com.ghosttyxr.app.util.GxrLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val TAG = "PetSpeech"

/**
 * Owns fixed bundled pet notices that are not model speech. Transcript-without-audio replies are
 * spoken only by `LiveCommandEngine.events`; this coordinator handles the exact no-speech notice
 * so its prebaked clip remains available without becoming a second owner of normal results.
 *
 * Rules:
 *  - Voice is additive: synthesis/playback failures are logged and never affect the turn or
 *    the visual toast.
 *  - Single-flight, latest-wins: a newer message cancels an in-flight utterance.
 *  - Every trigger re-checks [enabled] so the Settings toggle applies immediately.
 *
 * Pure logic + injected synthesizer: unit-tested without network or audio (see
 * `PetSpeechCoordinatorTest`); the Android-touching construction lives in
 * [com.ghosttyxr.app.wiring.buildVoicePetSurface].
 */
class PetSpeechCoordinator(
    private val scope: CoroutineScope,
    private val voiceState: StateFlow<VoiceCommandState>,
    private val synthesizer: PetSpeechSynthesizer,
    private val enabled: () -> Boolean = { true },
) {
    private var voiceJob: Job? = null
    private var speakJob: Job? = null

    fun start() {
        if (voiceJob != null) return
        voiceJob = scope.launch {
            voiceState.collect { state ->
                when (state) {
                    is VoiceCommandState.Error ->
                        if (state.message == VoiceMessages.NO_SPEECH_MESSAGE) speak(state.message)
                    else -> Unit
                }
            }
        }
    }

    private fun speak(message: String) {
        if (!enabled() || message.isBlank()) return
        speakJob?.cancel()
        synthesizer.stop()
        speakJob = scope.launch {
            try {
                synthesizer.speak(message)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (err: Throwable) {
                GxrLog.w(TAG, "pet speech failed: ${err.message}")
            }
        }
    }

    fun dispose() {
        voiceJob?.cancel()
        voiceJob = null
        speakJob?.cancel()
        speakJob = null
        synthesizer.stop()
        synthesizer.release()
    }
}
