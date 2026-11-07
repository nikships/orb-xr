package com.ghosttyxr.app.terminal.voice.tts

/**
 * Speaks one short pet utterance out loud (issue #55). Implementations synthesize [speak]'s text
 * and play it to completion; the call suspends for the whole synthesize + playback span so a
 * caller can supersede an utterance by cancelling the coroutine (and calling [stop]).
 *
 * The production implementations are the Live model-audio path ([PlayerLiveSpeechOutput] into
 * [PetSpeechPlayer], spatialized onto the pet entity) and [SilentSpeechSynthesizer] for
 * transcript fallback; tests inject a fake so the trigger logic ([PetSpeechCoordinator]) is
 * verified without network or audio hardware.
 */
interface PetSpeechSynthesizer {

    /** Synthesize and play [text]; suspends until playback finishes or the coroutine is cancelled. */
    suspend fun speak(text: String)

    /** Halt any in-flight playback immediately (supersede / hide). Safe to call at any time. */
    fun stop()

    /** Final teardown; the synthesizer is not reusable afterwards. */
    fun release()
}
