package com.ghosttyxr.app.terminal.voice.tts

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PetSpeechPlayerTest {

    @Test
    fun `player holds odd PCM bytes and primes before playback`() = runBlocking {
        val output = FakeOutput(sampleRateHz = 24_000)
        val player = PetSpeechPlayer { output }
        player.start(24_000)

        player.write(ByteArray(3_599) { it.toByte() })
        assertFalse(output.played)
        player.write(byteArrayOf(1))
        assertTrue(output.played)
        assertEquals(3_600, output.bytes.size)

        player.finish()
        assertEquals(1, output.stopCalls)
        assertEquals(1, output.releaseCalls)
    }

    @Test
    fun `short stream starts at completion and writes partial output results`() = runBlocking {
        val output = FakeOutput(sampleRateHz = 24_000, maxWriteBytes = 1)
        val player = PetSpeechPlayer { output }
        player.start(24_000)
        player.write(byteArrayOf(1, 2, 3))
        player.write(byteArrayOf(4))

        assertFalse(output.played)
        player.finish()

        assertTrue(output.played)
        assertEquals(byteArrayOf(1, 2, 3, 4).toList(), output.bytes)
    }

    @Test
    fun `player starts before a chunk exceeds the priming buffer`() {
        val output = PrimingOutput()
        val player = PetSpeechPlayer { output }
        player.start(24_000)

        player.write(ByteArray(3_602))

        assertEquals(listOf(3_600, 2), output.writeSizes)
        assertTrue(output.played)
    }

    @Test
    fun `stop is idempotent`() {
        val output = FakeOutput(sampleRateHz = 24_000)
        val player = PetSpeechPlayer { output }
        player.start(24_000)

        player.stop()
        player.stop()

        assertEquals(1, output.stopCalls)
        assertEquals(1, output.releaseCalls)
    }

    @Test
    fun `incomplete final frame still releases output`() {
        val output = FakeOutput(sampleRateHz = 24_000)
        val player = PetSpeechPlayer { output }
        player.start(24_000)
        player.write(byteArrayOf(1))

        assertThrows(PetTtsException::class.java) {
            runBlocking { player.finish() }
        }
        assertEquals(1, output.releaseCalls)
    }

    @Test
    fun `stop waits for an in-flight write before release`() {
        val output = BlockingOutput()
        val player = PetSpeechPlayer { output }
        player.start(24_000)
        val writer = Thread { player.write(byteArrayOf(1, 2)) }
        val stopper = Thread { player.stop() }

        writer.start()
        assertTrue(output.writeStarted.await(2, TimeUnit.SECONDS))
        stopper.start()
        Thread.sleep(50)
        assertEquals(0, output.releaseCalls)

        output.allowWrite.countDown()
        writer.join(2_000)
        stopper.join(2_000)
        assertFalse(writer.isAlive)
        assertFalse(stopper.isAlive)
        assertEquals(1, output.releaseCalls)
    }

    private class FakeOutput(
        override val sampleRateHz: Int,
        private val maxWriteBytes: Int = Int.MAX_VALUE,
    ) : PetSpeechOutput {
        val bytes = mutableListOf<Byte>()
        var played = false
        var stopCalls = 0
        var releaseCalls = 0

        override val isPlaying: Boolean
            get() = false

        override val playbackHeadPosition: Int
            get() = bytes.size / 2

        override fun write(bytes: ByteArray, offset: Int, size: Int): Int {
            val written = minOf(size, maxWriteBytes)
            repeat(written) { this.bytes += bytes[offset + it] }
            return written
        }

        override fun play() {
            played = true
        }

        override fun stop() {
            stopCalls++
        }

        override fun release() {
            releaseCalls++
        }
    }

    private class BlockingOutput : PetSpeechOutput {
        val writeStarted = CountDownLatch(1)
        val allowWrite = CountDownLatch(1)
        var releaseCalls = 0

        override val sampleRateHz = 24_000
        override val isPlaying = false
        override val playbackHeadPosition = 0

        override fun write(bytes: ByteArray, offset: Int, size: Int): Int {
            writeStarted.countDown()
            allowWrite.await(2, TimeUnit.SECONDS)
            return size
        }

        override fun play() = Unit
        override fun stop() = Unit

        override fun release() {
            releaseCalls++
        }
    }

    private class PrimingOutput : PetSpeechOutput {
        val writeSizes = mutableListOf<Int>()
        var played = false

        override val sampleRateHz = 24_000
        override val isPlaying = played
        override val playbackHeadPosition = 0

        override fun write(bytes: ByteArray, offset: Int, size: Int): Int {
            check(played || size <= 3_600) { "Write exceeded pre-play priming capacity." }
            writeSizes += size
            return size
        }

        override fun play() {
            played = true
        }

        override fun stop() = Unit
        override fun release() = Unit
    }
}
