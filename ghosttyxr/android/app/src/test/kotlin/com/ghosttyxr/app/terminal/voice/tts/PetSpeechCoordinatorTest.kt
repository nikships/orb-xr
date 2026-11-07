package com.ghosttyxr.app.terminal.voice.tts

import com.ghosttyxr.app.terminal.voice.VoiceMessages
import com.ghosttyxr.app.terminal.voice.command.VoiceCommandState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PetSpeechCoordinatorTest {

    /**
     * Records every [speak] and never returns (playback in flight) so supersede/dispose
     * cancellation is observable. Runs on the Unconfined scope, matching the session tests.
     */
    private class FakeSynthesizer : PetSpeechSynthesizer {
        val spoken = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        var stopCalls = 0
        var releaseCalls = 0

        override suspend fun speak(text: String) {
            spoken += text
            try {
                awaitCancellation()
            } finally {
                cancelled += text
            }
        }

        override fun stop() {
            stopCalls++
        }

        override fun release() {
            releaseCalls++
        }
    }

    private val voiceState = MutableStateFlow<VoiceCommandState>(VoiceCommandState.Idle)
    private val synth = FakeSynthesizer()

    private fun coordinator(enabled: () -> Boolean = { true }) = PetSpeechCoordinator(
        scope = CoroutineScope(Dispatchers.Unconfined),
        voiceState = voiceState,
        synthesizer = synth,
        enabled = enabled,
    ).also { it.start() }

    @Test
    fun `only exact no-speech error plays the bundled notice`() {
        coordinator()
        voiceState.value = VoiceCommandState.Result("Opened terminal three.")
        voiceState.value = VoiceCommandState.Idle
        voiceState.value = VoiceCommandState.Error("Session failed.")
        voiceState.value = VoiceCommandState.Idle
        voiceState.value = VoiceCommandState.Error(VoiceMessages.NO_SPEECH_MESSAGE)

        assertEquals(listOf(VoiceMessages.NO_SPEECH_MESSAGE), synth.spoken)
    }

    @Test
    fun `non terminal states stay silent`() {
        coordinator()
        voiceState.value = VoiceCommandState.Listening
        voiceState.value = VoiceCommandState.Transcribing
        voiceState.value = VoiceCommandState.Thinking
        voiceState.value = VoiceCommandState.Executing("new terminal")
        assertTrue(synth.spoken.isEmpty())
    }

    @Test
    fun `new no-speech notice supersedes an in-flight notice`() {
        coordinator()
        voiceState.value = VoiceCommandState.Error(VoiceMessages.NO_SPEECH_MESSAGE)
        voiceState.value = VoiceCommandState.Idle
        voiceState.value = VoiceCommandState.Error(VoiceMessages.NO_SPEECH_MESSAGE)

        assertEquals(
            listOf(VoiceMessages.NO_SPEECH_MESSAGE, VoiceMessages.NO_SPEECH_MESSAGE),
            synth.spoken,
        )
        assertEquals(listOf(VoiceMessages.NO_SPEECH_MESSAGE), synth.cancelled)
        assertTrue(synth.stopCalls >= 1)
    }

    @Test
    fun `disabled setting silences all triggers`() {
        coordinator(enabled = { false })
        voiceState.value = VoiceCommandState.Error(VoiceMessages.NO_SPEECH_MESSAGE)
        assertTrue(synth.spoken.isEmpty())
    }

    @Test
    fun `dispose cancels playback and releases the synthesizer`() {
        val coordinator = coordinator()
        voiceState.value = VoiceCommandState.Error(VoiceMessages.NO_SPEECH_MESSAGE)
        assertEquals(listOf(VoiceMessages.NO_SPEECH_MESSAGE), synth.spoken)

        coordinator.dispose()
        assertEquals(listOf(VoiceMessages.NO_SPEECH_MESSAGE), synth.cancelled)
        assertTrue(synth.stopCalls >= 1)
        assertEquals(1, synth.releaseCalls)

        // Post-dispose triggers are inert (collectors cancelled).
        voiceState.value = VoiceCommandState.Idle
        voiceState.value = VoiceCommandState.Error(VoiceMessages.NO_SPEECH_MESSAGE)
        assertEquals(listOf(VoiceMessages.NO_SPEECH_MESSAGE), synth.spoken)
    }
}
