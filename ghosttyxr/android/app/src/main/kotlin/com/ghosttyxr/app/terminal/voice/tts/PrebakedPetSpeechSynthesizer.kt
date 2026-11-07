package com.ghosttyxr.app.terminal.voice.tts

import androidx.annotation.RawRes
import com.ghosttyxr.app.R
import com.ghosttyxr.app.terminal.voice.VoiceMessages
import kotlinx.coroutines.CancellationException

/**
 * Speaks a fixed set of orb notices from clips bundled in `res/raw` and delegates everything
 * else to the cloud synthesizer.
 *
 * The motivating case is "no speech picked up". That notice is produced exactly when the voice
 * path already failed — an empty capture buffer, or a blank transcript — and the old behaviour
 * was to answer it with *another* network round-trip to `petTts`. On the failure modes that
 * cause it most often (mic never opened, the user dismissed the orb without speaking, the
 * network is down) that second request is slow or fails too, so the orb went silent precisely
 * when the user most needed to be told nothing was heard. The line is also a fixed string, so
 * synthesizing it fresh every time buys nothing.
 *
 * Matching is by exact string against the canonical constants (not a substring or fuzzy match)
 * so a model-authored message that merely resembles a notice still goes to the cloud and is
 * spoken verbatim. Any text with no bundled clip delegates unchanged.
 *
 * Pure routing + injected [clipPlayer]: unit-tested on the host JVM with a fake player and a
 * fake delegate, no MediaPlayer and no network.
 */
class PrebakedPetSpeechSynthesizer(
    private val delegate: PetSpeechSynthesizer,
    private val clipPlayer: PetSpeechClipPlayer,
    private val clips: Map<String, Int> = DEFAULT_CLIPS,
) : PetSpeechSynthesizer {

    override suspend fun speak(text: String) {
        val clip = clips[text.trim()]
        if (clip == null) {
            delegate.speak(text)
            return
        }
        try {
            clipPlayer.play(clip)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (err: Throwable) {
            // A bundled clip that won't play is a local decode/audio-focus problem, not a reason
            // to drop the notice: fall back to the cloud voice rather than staying silent.
            delegate.speak(text)
        }
    }

    override fun stop() {
        clipPlayer.stop()
        delegate.stop()
    }

    override fun release() {
        clipPlayer.release()
        delegate.release()
    }

    companion object {
        /**
         * Canonical notice text → bundled clip. Keys must stay byte-identical to the constants
         * they mirror, and the recorded line must match the text the toast shows, so the orb
         * never says something different from what is on screen.
         */
        val DEFAULT_CLIPS: Map<String, Int> = mapOf(
            VoiceMessages.NO_SPEECH_MESSAGE to R.raw.orb_no_speech,
        )
    }
}

/**
 * Plays one bundled `res/raw` clip to completion, suspending for the whole span so the
 * coordinator's single-flight cancellation supersedes it exactly like a cloud utterance.
 */
interface PetSpeechClipPlayer {

    suspend fun play(@RawRes resourceId: Int)

    /** Halt playback immediately (supersede / hide). Safe to call at any time. */
    fun stop()

    /** Final teardown; the player is not reusable afterwards. */
    fun release()
}
