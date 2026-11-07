package com.ghosttyxr.app.terminal.windowmirror

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WindowMirrorProtocolTest {
    @Test
    fun parsesMetadataFrame() {
        val payload = """{"id":42,"title":"App","app":"Safari","width":1280,"height":720,"scale":2,"interactive":true}"""
            .toByteArray()
        val message = WindowMirrorProtocol.parse(
            WindowMirrorFrame(WindowMirrorFrameType.Metadata, payload),
        )

        val metadata = (message as WindowMirrorMessage.Metadata).metadata
        assertEquals(42L, metadata.id)
        assertEquals("Safari · App", metadata.displayTitle)
        assertEquals(1280, metadata.width)
        assertTrue(metadata.interactive)
    }

    @Test
    fun framesSplitAcrossChunks() {
        val frame = WindowMirrorProtocol.pingFrame()
        val decoder = WindowMirrorFrameDecoder()

        assertTrue(decoder.accept(frame.copyOfRange(0, 3)).isEmpty())
        val frames = decoder.accept(frame.copyOfRange(3, frame.size))

        assertEquals(WindowMirrorFrameType.Ping, frames.single().type)
    }

    @Test
    fun encodesPointerInputFrame() {
        val encoded = WindowMirrorProtocol.pointerFrame("down", 0.25f, 0.75f)
        val frames = WindowMirrorFrameDecoder().accept(encoded)

        assertEquals(WindowMirrorFrameType.Input, frames.single().type)
        assertTrue(frames.single().payload.toString(Charsets.UTF_8).contains("\"kind\":\"pointer\""))
    }

    @Test
    fun metadataDefaultsToWindowKindAndH264() {
        val payload = """{"id":1,"title":"T","app":"A","width":10,"height":10,"scale":1,"interactive":false}"""
            .toByteArray()
        val metadata = (WindowMirrorProtocol.parse(
            WindowMirrorFrame(WindowMirrorFrameType.Metadata, payload),
        ) as WindowMirrorMessage.Metadata).metadata

        assertEquals("window", metadata.kind)
        assertEquals("h264", metadata.codec)
        assertTrue(!metadata.isDisplayShare)
    }

    @Test
    fun parsesDisplayShareMetadata() {
        val payload = """{"id":1,"title":"Mac Screen","app":"","width":1728,"height":1117,"scale":2,""" +
            """"interactive":true,"kind":"display","codec":"h264"}"""
        val metadata = (WindowMirrorProtocol.parse(
            WindowMirrorFrame(WindowMirrorFrameType.Metadata, payload.toByteArray()),
        ) as WindowMirrorMessage.Metadata).metadata

        assertEquals("display", metadata.kind)
        assertEquals("h264", metadata.codec)
        assertTrue(metadata.isDisplayShare)
        assertEquals("Mac Screen", metadata.displayTitle)
    }

    @Test
    fun parsesPointerHandoffFrame() {
        val payload = """{"seq":1,"edge":"right","y":0.42}""".toByteArray()
        val message = WindowMirrorProtocol.parse(
            WindowMirrorFrame(WindowMirrorFrameType.PointerHandoff, payload),
        ) as WindowMirrorMessage.PointerHandoff

        assertEquals(1L, message.seq)
        assertEquals("right", message.edge)
        assertEquals(0.42f, message.y, 1e-6f)
    }

    @Test
    fun parsesMouseFrames() {
        val delta = WindowMirrorProtocol.parse(
            WindowMirrorFrame(WindowMirrorFrameType.MouseDelta, """{"seq":2,"dx":3.5,"dy":-2}""".toByteArray()),
        ) as WindowMirrorMessage.MouseDelta
        assertEquals(2L, delta.seq)
        assertEquals(3.5f, delta.dx, 1e-6f)
        assertEquals(-2f, delta.dy, 1e-6f)

        val button = WindowMirrorProtocol.parse(
            WindowMirrorFrame(WindowMirrorFrameType.MouseButton, """{"seq":3,"button":"left","action":"down"}""".toByteArray()),
        ) as WindowMirrorMessage.MouseButton
        assertEquals(3L, button.seq)
        assertEquals("left", button.button)
        assertEquals("down", button.action)

        val scroll = WindowMirrorProtocol.parse(
            WindowMirrorFrame(WindowMirrorFrameType.MouseScroll, """{"seq":4,"dx":0,"dy":12}""".toByteArray()),
        ) as WindowMirrorMessage.MouseScroll
        assertEquals(4L, scroll.seq)
        assertEquals(12f, scroll.dy, 1e-6f)
    }

    @Test
    fun mouseFramesWithoutSequenceParseAsUntracked() {
        val delta = WindowMirrorProtocol.parse(
            WindowMirrorFrame(WindowMirrorFrameType.MouseDelta, """{"dx":1,"dy":1}""".toByteArray()),
        ) as WindowMirrorMessage.MouseDelta

        assertEquals(0L, delta.seq)
    }

    @Test
    fun helloFrameAdvertisesFeatureVersionOnWireVersionOne() {
        val encoded = WindowMirrorProtocol.helloFrame()

        // The compact header stays stable while the hello payload carries feature v4.
        assertEquals(1, encoded[0].toInt())
        val frame = WindowMirrorFrameDecoder().accept(encoded).single()
        assertEquals(WindowMirrorFrameType.Hello, frame.type)
        assertTrue(frame.payload.toString(Charsets.UTF_8).contains("\"version\":$WINDOW_MIRROR_FEATURE_VERSION"))
    }

    @Test
    fun parsesCompanionHelloFeatureVersion() {
        val versioned = WindowMirrorProtocol.parse(
            WindowMirrorFrame(
                WindowMirrorFrameType.Hello,
                """{"role":"mac","app":"ghosttyxr-companion","version":2}""".toByteArray(),
            ),
        ) as WindowMirrorMessage.Hello
        assertEquals(2, versioned.version)

        val legacy = WindowMirrorProtocol.parse(
            WindowMirrorFrame(
                WindowMirrorFrameType.Hello,
                """{"role":"mac","app":"ghosttyxr-companion"}""".toByteArray(),
            ),
        ) as WindowMirrorMessage.Hello
        assertEquals(1, legacy.version)
    }

    @Test
    fun rejectsMacHelloFeatureVersionBelowMinimum() {
        val hello = WindowMirrorProtocol.parse(
            WindowMirrorFrame(
                WindowMirrorFrameType.Hello,
                """{"role":"mac","app":"ghosttyxr-companion","version":3}""".toByteArray(),
            ),
        ) as WindowMirrorMessage.Hello
        assertTrue(hello.version < WINDOW_MIRROR_FEATURE_VERSION)
        assertTrue(!WindowMirrorProtocol.isFeatureCompatible(hello.version))
        assertTrue(WindowMirrorProtocol.isFeatureCompatible(WINDOW_MIRROR_FEATURE_VERSION))
    }

    @Test
    fun parsesMediaSessionFrame() {
        val message = WindowMirrorProtocol.parse(
            WindowMirrorFrame(
                WindowMirrorFrameType.MediaSession,
                """{"token":"tok-abc","host":"192.168.1.20","port":45876,"codec":"hevc","encrypted":true}"""
                    .toByteArray(),
            ),
        ) as WindowMirrorMessage.MediaSession

        assertEquals("tok-abc", message.token)
        assertEquals("192.168.1.20", message.host)
        assertEquals(WINDOW_MIRROR_MEDIA_PORT, message.port)
        assertEquals("hevc", message.codec)
        assertTrue(message.encrypted)
    }

    @Test
    fun mediaSessionDefaultsToMediaPortEncryptedH264() {
        val message = WindowMirrorProtocol.parse(
            WindowMirrorFrame(
                WindowMirrorFrameType.MediaSession,
                """{"token":"t","host":"10.0.0.2"}""".toByteArray(),
            ),
        ) as WindowMirrorMessage.MediaSession

        assertEquals(WINDOW_MIRROR_MEDIA_PORT, message.port)
        assertEquals("h264", message.codec)
        assertTrue(message.encrypted)
    }

    @Test
    fun rejectsMediaSessionWithoutTokenOrHost() {
        assertThrowsProtocol {
            WindowMirrorProtocol.parse(
                WindowMirrorFrame(
                    WindowMirrorFrameType.MediaSession,
                    """{"host":"10.0.0.2"}""".toByteArray(),
                ),
            )
        }
        assertThrowsProtocol {
            WindowMirrorProtocol.parse(
                WindowMirrorFrame(
                    WindowMirrorFrameType.MediaSession,
                    """{"token":"   ","host":"10.0.0.2"}""".toByteArray(),
                ),
            )
        }
        assertThrowsProtocol {
            WindowMirrorProtocol.parse(
                WindowMirrorFrame(WindowMirrorFrameType.MediaSession, """{"token":"t"}""".toByteArray()),
            )
        }
        assertThrowsProtocol {
            WindowMirrorProtocol.parse(
                WindowMirrorFrame(
                    WindowMirrorFrameType.MediaSession,
                    """{"token":"t","host":""}""".toByteArray(),
                ),
            )
        }
    }

    @Test
    fun rejectsInboundInputAsOutboundOnly() {
        assertThrowsProtocol {
            WindowMirrorProtocol.parse(
                WindowMirrorFrame(
                    WindowMirrorFrameType.Input,
                    """{"kind":"pointer","action":"down","x":0.1,"y":0.2}""".toByteArray(),
                ),
            )
        }
    }

    @Test
    fun rejectsInboundMediaPathFramesAsOutboundOnly() {
        for (type in listOf(
            WindowMirrorFrameType.MediaHello,
            WindowMirrorFrameType.CodecSupport,
            WindowMirrorFrameType.KeyFrameRequest,
            WindowMirrorFrameType.LatencyReport,
            WindowMirrorFrameType.VideoSlice,
        )) {
            assertThrowsProtocol {
                WindowMirrorProtocol.parse(WindowMirrorFrame(type, """{}""".toByteArray()))
            }
        }
    }

    @Test
    fun encodesMediaPathFrames() {
        val hello = WindowMirrorFrameDecoder().accept(WindowMirrorProtocol.mediaHelloFrame("tok-abc")).single()
        assertEquals(WindowMirrorFrameType.MediaHello, hello.type)
        assertTrue(hello.payload.toString(Charsets.UTF_8).contains("\"token\":\"tok-abc\""))

        val codecs = WindowMirrorFrameDecoder().accept(
            WindowMirrorProtocol.codecSupportFrame(listOf("h264", "hevc"), lowLatency = true),
        ).single()
        assertEquals(WindowMirrorFrameType.CodecSupport, codecs.type)
        val codecText = codecs.payload.toString(Charsets.UTF_8)
        assertTrue(codecText.contains("\"codecs\":[\"h264\",\"hevc\"]"))
        assertTrue(codecText.contains("\"lowLatency\":true"))

        val keyFrame = WindowMirrorFrameDecoder().accept(WindowMirrorProtocol.keyFrameRequestFrame("gap")).single()
        assertEquals(WindowMirrorFrameType.KeyFrameRequest, keyFrame.type)
        assertTrue(keyFrame.payload.toString(Charsets.UTF_8).contains("\"reason\":\"gap\""))

        val latency = WindowMirrorFrameDecoder().accept(
            WindowMirrorProtocol.latencyReportFrame(endToEndMs = 42.5, decodedFps = 90.0, decodeQueueDepth = 1),
        ).single()
        assertEquals(WindowMirrorFrameType.LatencyReport, latency.type)
        val latencyText = latency.payload.toString(Charsets.UTF_8)
        assertTrue(latencyText.contains("\"endToEndMs\":42.5"))
        assertTrue(latencyText.contains("\"decodeQueueDepth\":1"))
    }

    @Test
    fun decodesVideoSliceSubHeaderAndBitstream() {
        val bitstream = byteArrayOf(0, 0, 0, 1, 0x65, 0xAA.toByte(), 0xBB.toByte())
        val payload = ByteBuffer.allocate(WINDOW_MIRROR_SLICE_HEADER_BYTES + bitstream.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(1L)
            .putLong(1000L)
            .putShort(1920.toShort())
            .putShort(1080.toShort())
            .putShort(0)
            .putShort(2)
            .put(0x01.toByte())
            .put(0.toByte())
            .put(bitstream)
            .array()

        val slice = decodeWindowMirrorVideoSlice(payload)

        assertEquals(1L, slice.sequence)
        assertEquals(1000L, slice.ptsUs)
        assertEquals(1920, slice.width)
        assertEquals(1080, slice.height)
        assertEquals(0, slice.sliceIndex)
        assertEquals(2, slice.sliceCount)
        assertTrue(slice.isKeyFrame)
        assertTrue(!slice.isFrameEnd)
        assertArrayEquals(bitstream, slice.bitstream)
    }

    @Test
    fun rejectsVideoSliceShorterThanSubHeader() {
        assertThrowsProtocol {
            decodeWindowMirrorVideoSlice(ByteArray(WINDOW_MIRROR_SLICE_HEADER_BYTES - 1))
        }
    }

    @Test
    fun decoderAcceptsLegacyWireVersionOneFrames() {
        // A ping exactly as a legacy (pre-Symbiosis) peer would encode it.
        val legacyPing = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
            .put(1.toByte())
            .put(WindowMirrorFrameType.Ping.id.toByte())
            .putInt(0)
            .array()

        val frame = WindowMirrorFrameDecoder().accept(legacyPing).single()
        assertEquals(WindowMirrorFrameType.Ping, frame.type)
    }

    @Test
    fun parsesInboundPointerReturnAsReclaim() {
        val message = WindowMirrorProtocol.parse(
            WindowMirrorFrame(WindowMirrorFrameType.PointerReturn, """{"x":0,"y":0}""".toByteArray()),
        )
        assertEquals(WindowMirrorMessage.PointerReturned, message)
    }

    @Test
    fun pointerReturnFrameRoundTripsThroughDecoder() {
        val encoded = WindowMirrorProtocol.pointerReturnFrame(0.9f, 0.1f)
        val frame = WindowMirrorFrameDecoder().accept(encoded).single()

        assertEquals(WindowMirrorFrameType.PointerReturn, frame.type)
        val text = frame.payload.toString(Charsets.UTF_8)
        assertTrue(text.contains("\"x\":0.9"))
        assertTrue(text.contains("\"y\":0.1"))
    }

    @Test
    fun decoderHandlesHeaderSplitByteByByte() {
        val encoded = WindowMirrorProtocol.pointerFrame("move", 0.5f, 0.5f)
        val decoder = WindowMirrorFrameDecoder()

        for (i in 0 until encoded.size - 1) {
            assertTrue(decoder.accept(byteArrayOf(encoded[i])).isEmpty())
        }
        val frames = decoder.accept(byteArrayOf(encoded.last()))

        assertEquals(WindowMirrorFrameType.Input, frames.single().type)
    }

    @Test
    fun decoderReassemblesLargePayloadAcrossManyChunks() {
        // Larger than the decoder's initial capacity so growth + compaction run.
        val payload = ByteArray(300 * 1024) { (it % 251).toByte() }
        val encoded = encodeRawFrame(WindowMirrorFrameType.VideoSlice, payload)
        val decoder = WindowMirrorFrameDecoder()

        val collected = mutableListOf<WindowMirrorFrame>()
        var offset = 0
        val chunkSize = 4 * 1024
        while (offset < encoded.size) {
            val next = minOf(offset + chunkSize, encoded.size)
            collected += decoder.accept(encoded.copyOfRange(offset, next))
            offset = next
        }

        assertArrayEquals(payload, collected.single().payload)
    }

    @Test
    fun decoderParsesMultipleFramesFromOneChunkWithTrailingPartial() {
        val ping = WindowMirrorProtocol.pingFrame()
        val pointer = WindowMirrorProtocol.pointerFrame("down", 0.1f, 0.2f)
        val combined = ping + pointer + pointer.copyOfRange(0, 4)
        val decoder = WindowMirrorFrameDecoder()

        val frames = decoder.accept(combined)
        assertEquals(
            listOf(WindowMirrorFrameType.Ping, WindowMirrorFrameType.Input),
            frames.map { it.type },
        )

        val remainder = decoder.accept(pointer.copyOfRange(4, pointer.size))
        assertEquals(WindowMirrorFrameType.Input, remainder.single().type)
    }

    @Test
    fun decoderKeepsWorkingAfterLargeFrame() {
        val large = encodeRawFrame(WindowMirrorFrameType.VideoSlice, ByteArray(200 * 1024))
        val decoder = WindowMirrorFrameDecoder()
        decoder.accept(large)

        val frame = decoder.accept(WindowMirrorProtocol.pingFrame()).single()
        assertEquals(WindowMirrorFrameType.Ping, frame.type)
    }

    @Test
    fun decoderRejectsOversizedFrame() {
        val decoder = WindowMirrorFrameDecoder(maxFrameBytes = 16)
        val encoded = encodeRawFrame(WindowMirrorFrameType.VideoSlice, ByteArray(17))

        var threw = false
        try {
            decoder.accept(encoded)
        } catch (expected: WindowMirrorProtocolException) {
            threw = true
        }
        assertTrue(threw)

        // The decoder must have reset and accept fresh frames afterwards.
        val frame = decoder.accept(WindowMirrorProtocol.pingFrame()).single()
        assertEquals(WindowMirrorFrameType.Ping, frame.type)
    }

    @Test
    fun decoderRejectsWrongWireVersionAndUnknownTypeThenRecovers() {
        val decoder = WindowMirrorFrameDecoder()
        val wrongVersion = byteArrayOf(2, 3, 0, 0, 0, 0)
        try {
            decoder.accept(wrongVersion)
            fail("expected protocol exception")
        } catch (_: WindowMirrorProtocolException) {
        }
        assertEquals(WindowMirrorFrameType.Ping, decoder.accept(WindowMirrorProtocol.pingFrame()).single().type)

        val unknownType = byteArrayOf(1, 99, 0, 0, 0, 0)
        try {
            decoder.accept(unknownType)
            fail("expected protocol exception")
        } catch (_: WindowMirrorProtocolException) {
        }
        assertEquals(WindowMirrorFrameType.Ping, decoder.accept(WindowMirrorProtocol.pingFrame()).single().type)
    }

    @Test
    fun decoderWaitsOnTruncatedHeaderAndPayload() {
        val decoder = WindowMirrorFrameDecoder()
        assertTrue(decoder.accept(byteArrayOf(1, 3, 0)).isEmpty())
        // Complete the ping header (length 0) with remaining bytes.
        assertEquals(WindowMirrorFrameType.Ping, decoder.accept(byteArrayOf(0, 0, 0)).single().type)

        val partial = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
            .put(1.toByte())
            .put(WindowMirrorFrameType.Ping.id.toByte())
            .putInt(10)
            .put(byteArrayOf(1, 2, 3, 4))
            .array()
        assertTrue(decoder.accept(partial).isEmpty())
        assertEquals(
            WindowMirrorFrameType.Ping,
            decoder.accept(ByteArray(6) { 0 }).single().type,
        )
    }

    @Test
    fun parsesStreamStateAndClose() {
        val state = WindowMirrorProtocol.parse(
            WindowMirrorFrame(
                WindowMirrorFrameType.StreamState,
                """{"status":"streaming","detail":"ok","interactive":true,"transport":"direct-tcp"}""".toByteArray(),
            ),
        ) as WindowMirrorMessage.StreamState
        assertEquals("streaming", state.status)
        assertEquals("ok", state.detail)
        assertTrue(state.interactive)
        assertEquals("direct-tcp", state.transport)

        val close = WindowMirrorProtocol.parse(
            WindowMirrorFrame(WindowMirrorFrameType.Close, """{"reason":"bye"}""".toByteArray()),
        ) as WindowMirrorMessage.Close
        assertEquals("bye", close.reason)

        val emptyClose = WindowMirrorProtocol.parse(
            WindowMirrorFrame(WindowMirrorFrameType.Close, """{}""".toByteArray()),
        ) as WindowMirrorMessage.Close
        assertNull(emptyClose.reason)
    }

    private fun assertThrowsProtocol(block: () -> Unit) {
        try {
            block()
            fail("expected WindowMirrorProtocolException")
        } catch (_: WindowMirrorProtocolException) {
        }
    }

    private fun encodeRawFrame(type: WindowMirrorFrameType, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(6 + payload.size).order(ByteOrder.BIG_ENDIAN)
            .put(1.toByte())
            .put(type.id.toByte())
            .putInt(payload.size)
            .put(payload)
            .array()
}
