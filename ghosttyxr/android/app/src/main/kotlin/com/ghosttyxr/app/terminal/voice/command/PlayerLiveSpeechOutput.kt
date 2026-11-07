package com.ghosttyxr.app.terminal.voice.command

import com.ghosttyxr.app.terminal.voice.tts.PetSpeechPlayer

/**
 * [LiveSpeechOutput] backed by [PetSpeechPlayer]: model speech PCM streams into the same
 * primed streaming `AudioTrack` path the old `petTts` pipeline used, including the
 * point-source spatialized factory production wires (pet's voice emanates from the pet entity).
 *
 * No network, no `petTts` function, no Deepgram: the PCM comes straight from the Live session.
 * `PetSpeechPlayer.write` throws when no stream is active; the engine guards ordering
 * (start-then-write), and this wrapper defensively no-ops instead of crashing on stray frames.
 */
class PlayerLiveSpeechOutput(
    private val player: PetSpeechPlayer = PetSpeechPlayer(),
) : LiveSpeechOutput {

    @Volatile
    private var started = false

    override fun start(sampleRateHz: Int) {
        runCatching { player.stop() }
        player.start(sampleRateHz)
        started = true
    }

    override fun write(chunk: ByteArray) {
        if (!started) return
        runCatching { player.write(chunk) }.onFailure { started = false }
    }

    override suspend fun finish() {
        if (!started) return
        started = false
        runCatching { player.finish() }
    }

    override fun stop() {
        started = false
        runCatching { player.stop() }
    }
}
