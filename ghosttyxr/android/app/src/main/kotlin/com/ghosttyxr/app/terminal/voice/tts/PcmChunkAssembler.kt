package com.ghosttyxr.app.terminal.voice.tts

internal class PcmChunkAssembler {
    private var pendingByte: Byte? = null

    fun append(chunk: ByteArray): ByteArray {
        if (chunk.isEmpty()) return ByteArray(0)
        val combinedSize = chunk.size + if (pendingByte == null) 0 else 1
        val completeSize = combinedSize - (combinedSize % BYTES_PER_FRAME)
        if (completeSize == 0) {
            pendingByte = chunk[0]
            return ByteArray(0)
        }

        val complete = ByteArray(completeSize)
        var outputOffset = 0
        pendingByte?.let {
            complete[outputOffset++] = it
            pendingByte = null
        }
        val copied = completeSize - outputOffset
        chunk.copyInto(complete, destinationOffset = outputOffset, endIndex = copied)
        if (copied < chunk.size) pendingByte = chunk.last()
        return complete
    }

    fun finish() {
        if (pendingByte != null) throw PetTtsException("TTS stream ended with incomplete PCM.")
    }

    fun reset() {
        pendingByte = null
    }

    private companion object {
        const val BYTES_PER_FRAME = 2
    }
}
