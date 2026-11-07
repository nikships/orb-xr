package com.ghosttyxr.app.terminal.voice.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decorator's contract is "never make things worse": it spatializes when it can, and otherwise
 * hands back the delegate's output with its streaming behavior byte-for-byte unchanged. A throwing
 * spatializer is the realistic device case (no spatializer on the emulator / optical glasses), and it
 * must not take the pet's voice down with it.
 */
class SpatialPetSpeechOutputFactoryTest {

    private class FakeOutput(override val sampleRateHz: Int) : PetSpeechOutput {
        var playCount = 0
        var writtenBytes = 0
        var stopped = false
        var released = false
        override val isPlaying: Boolean get() = playCount > 0 && !stopped
        override val playbackHeadPosition: Int get() = writtenBytes / 2
        override fun write(bytes: ByteArray, offset: Int, size: Int): Int {
            writtenBytes += size
            return size
        }
        override fun play() { playCount++ }
        override fun stop() { stopped = true }
        override fun release() { released = true }
    }

    @Test
    fun `hands back a delegate output that is not AudioTrack-backed`() {
        val fake = FakeOutput(24_000)
        var spatializeCalls = 0
        val factory = SpatialPetSpeechOutputFactory(
            delegate = PetSpeechOutputFactory { fake },
            spatializer = { spatializeCalls++ },
        )

        assertSame(fake, factory.create(24_000))
        // Nothing to bind: the spatializer must not be invoked with a track it can't have.
        assertEquals(0, spatializeCalls)
    }

    @Test
    fun `preserves the delegate's streaming behavior through the wrapper`() {
        val fake = FakeOutput(16_000)
        val output = SpatialPetSpeechOutputFactory(
            delegate = PetSpeechOutputFactory { fake },
            spatializer = { },
        ).create(16_000)

        assertEquals(16_000, output.sampleRateHz)
        assertEquals(4, output.write(ByteArray(4), 0, 4))
        assertEquals(2, output.playbackHeadPosition)
        output.play()
        assertTrue(output.isPlaying)
        output.stop()
        output.release()
        assertTrue(fake.stopped)
        assertTrue(fake.released)
    }

    @Test
    fun `forwards the requested sample rate to the delegate`() {
        var requested = -1
        SpatialPetSpeechOutputFactory(
            delegate = PetSpeechOutputFactory { rate -> requested = rate; FakeOutput(rate) },
            spatializer = { },
        ).create(48_000)

        assertEquals(48_000, requested)
    }
}
