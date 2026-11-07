package com.ghosttyxr.app.terminal.voice.tts

import com.ghosttyxr.app.terminal.voice.VoiceMessages
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PrebakedPetSpeechSynthesizerTest {

    private class FakeDelegate : PetSpeechSynthesizer {
        val spoken = mutableListOf<String>()
        var stopCalls = 0
        var releaseCalls = 0

        override suspend fun speak(text: String) {
            spoken += text
        }

        override fun stop() {
            stopCalls++
        }

        override fun release() {
            releaseCalls++
        }
    }

    private class FakeClipPlayer : PetSpeechClipPlayer {
        val played = mutableListOf<Int>()
        var stopCalls = 0
        var releaseCalls = 0
        var failure: Throwable? = null
        /** When set, [play] suspends on it instead of returning, so cancellation is observable. */
        var block: CompletableDeferred<Unit>? = null

        override suspend fun play(resourceId: Int) {
            played += resourceId
            failure?.let { throw it }
            block?.await()
        }

        override fun stop() {
            stopCalls++
        }

        override fun release() {
            releaseCalls++
        }
    }

    private val delegate = FakeDelegate()
    private val clipPlayer = FakeClipPlayer()

    private val clipRes = 4321

    private fun synthesizer() = PrebakedPetSpeechSynthesizer(
        delegate = delegate,
        clipPlayer = clipPlayer,
        clips = mapOf(VoiceMessages.NO_SPEECH_MESSAGE to clipRes),
    )

    @Test
    fun `no-speech notice plays the bundled clip and never reaches the cloud`() = runBlocking {
        synthesizer().speak(VoiceMessages.NO_SPEECH_MESSAGE)

        assertEquals(listOf(clipRes), clipPlayer.played)
        assertTrue("prebaked notice must not hit the network", delegate.spoken.isEmpty())
    }

    @Test
    fun `surrounding whitespace still matches the bundled clip`() = runBlocking {
        synthesizer().speak("  ${VoiceMessages.NO_SPEECH_MESSAGE}  ")

        assertEquals(listOf(clipRes), clipPlayer.played)
        assertTrue(delegate.spoken.isEmpty())
    }

    @Test
    fun `other messages delegate to the cloud voice verbatim`() = runBlocking {
        synthesizer().speak("Opened terminal three.")

        assertEquals(listOf("Opened terminal three."), delegate.spoken)
        assertTrue(clipPlayer.played.isEmpty())
    }

    @Test
    fun `a message merely containing the notice is not treated as prebaked`() = runBlocking {
        val similar = "${VoiceMessages.NO_SPEECH_MESSAGE} I closed terminal two instead."
        synthesizer().speak(similar)

        assertEquals(listOf(similar), delegate.spoken)
        assertTrue(clipPlayer.played.isEmpty())
    }

    @Test
    fun `clip failure falls back to the cloud voice rather than going silent`() = runBlocking {
        clipPlayer.failure = IllegalStateException("decode failed")

        synthesizer().speak(VoiceMessages.NO_SPEECH_MESSAGE)

        assertEquals(listOf(clipRes), clipPlayer.played)
        assertEquals(listOf(VoiceMessages.NO_SPEECH_MESSAGE), delegate.spoken)
    }

    @Test
    fun `cancelling playback propagates instead of falling back`() = runBlocking {
        clipPlayer.block = CompletableDeferred()
        val synthesizer = synthesizer()

        val job = CoroutineScope(Dispatchers.Unconfined).launch {
            synthesizer.speak(VoiceMessages.NO_SPEECH_MESSAGE)
        }
        assertEquals(listOf(clipRes), clipPlayer.played)

        job.cancel()

        // A superseded utterance must not "recover" by speaking the same line over the new one.
        assertTrue(delegate.spoken.isEmpty())
    }

    @Test
    fun `stop and release reach both the clip player and the delegate`() {
        val synthesizer = synthesizer()

        synthesizer.stop()
        synthesizer.release()

        assertEquals(1, clipPlayer.stopCalls)
        assertEquals(1, delegate.stopCalls)
        assertEquals(1, clipPlayer.releaseCalls)
        assertEquals(1, delegate.releaseCalls)
    }

    @Test
    fun `default clip map covers the canonical no-speech constant`() {
        assertEquals(
            setOf(VoiceMessages.NO_SPEECH_MESSAGE),
            PrebakedPetSpeechSynthesizer.DEFAULT_CLIPS.keys,
        )
    }

    @Test
    fun `an unmapped clip lookup never throws`() = runBlocking {
        val synthesizer = PrebakedPetSpeechSynthesizer(
            delegate = delegate,
            clipPlayer = clipPlayer,
            clips = emptyMap(),
        )

        synthesizer.speak(VoiceMessages.NO_SPEECH_MESSAGE)

        assertEquals(listOf(VoiceMessages.NO_SPEECH_MESSAGE), delegate.spoken)
    }

    @Test
    fun `delegate failures still surface to the coordinator`() {
        val exploding = object : PetSpeechSynthesizer {
            override suspend fun speak(text: String) = throw IllegalStateException("tts down")
            override fun stop() = Unit
            override fun release() = Unit
        }
        val synthesizer = PrebakedPetSpeechSynthesizer(
            delegate = exploding,
            clipPlayer = clipPlayer,
            clips = emptyMap(),
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { synthesizer.speak("anything") }
        }
    }

    @Test
    fun `awaiting playback keeps the utterance in flight for supersede`() = runBlocking {
        clipPlayer.block = CompletableDeferred()
        val synthesizer = synthesizer()
        var finished = false

        val job = CoroutineScope(Dispatchers.Unconfined).launch {
            synthesizer.speak(VoiceMessages.NO_SPEECH_MESSAGE)
            finished = true
        }

        assertTrue("speak must suspend until the clip ends", !finished)
        clipPlayer.block!!.complete(Unit)
        job.join()
        assertTrue(finished)
    }

    @Test
    fun `blank text with no mapping delegates unchanged`() = runBlocking {
        synthesizer().speak("")

        assertEquals(listOf(""), delegate.spoken)
        assertTrue(clipPlayer.played.isEmpty())
    }

    @Test
    fun `cancellation inside the delegate is not swallowed`() = runBlocking {
        val hanging = object : PetSpeechSynthesizer {
            override suspend fun speak(text: String) = awaitCancellation()
            override fun stop() = Unit
            override fun release() = Unit
        }
        val synthesizer = PrebakedPetSpeechSynthesizer(
            delegate = hanging,
            clipPlayer = clipPlayer,
            clips = emptyMap(),
        )

        val job = CoroutineScope(Dispatchers.Unconfined).launch { synthesizer.speak("hello") }
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
    }
}
