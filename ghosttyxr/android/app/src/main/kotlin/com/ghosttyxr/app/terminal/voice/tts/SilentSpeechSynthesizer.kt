package com.ghosttyxr.app.terminal.voice.tts

import com.ghosttyxr.app.util.GxrLog

private const val TAG = "SilentSpeech"

/**
 * [PetSpeechSynthesizer] that speaks nothing: `speak` logs and returns, so the toast stands
 * alone. Wired as the [PrebakedPetSpeechSynthesizer] delegate for the Live transcript-fallback
 * path — bundled clips (e.g. the no-speech notice) still play, and any other transcript text
 * was already heard as model audio in the common case (the engine only emits fallback events
 * for transcript-without-audio turns).
 *
 * Replaces the deleted `CloudPetSpeechSynthesizer` → `petTts` Cloud Function delegate: the Live
 * session's own speech is the pet's voice now, and there is no cloud TTS left to call.
 */
class SilentSpeechSynthesizer : PetSpeechSynthesizer {

    override suspend fun speak(text: String) {
        GxrLog.w(TAG, "transcript fallback is toast-only; model audio already played or clips cover it")
    }

    override fun stop() = Unit

    override fun release() = Unit
}
