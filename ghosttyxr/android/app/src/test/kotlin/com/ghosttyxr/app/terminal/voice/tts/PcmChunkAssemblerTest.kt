package com.ghosttyxr.app.terminal.voice.tts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PcmChunkAssemblerTest {

    @Test
    fun `odd trailing byte is prepended to the next chunk`() {
        val assembler = PcmChunkAssembler()

        assertArrayEquals(byteArrayOf(1, 2), assembler.append(byteArrayOf(1, 2, 3)))
        assertArrayEquals(byteArrayOf(3, 4), assembler.append(byteArrayOf(4)))
        assembler.finish()
    }

    @Test
    fun `finish rejects an incomplete final frame`() {
        val assembler = PcmChunkAssembler()
        assertArrayEquals(ByteArray(0), assembler.append(byteArrayOf(1)))
        assertThrows(PetTtsException::class.java) { assembler.finish() }
    }

    @Test
    fun `reset discards a pending byte`() {
        val assembler = PcmChunkAssembler()
        assembler.append(byteArrayOf(9))
        assembler.reset()

        assertArrayEquals(byteArrayOf(1, 2), assembler.append(byteArrayOf(1, 2)))
        assembler.finish()
    }
}
