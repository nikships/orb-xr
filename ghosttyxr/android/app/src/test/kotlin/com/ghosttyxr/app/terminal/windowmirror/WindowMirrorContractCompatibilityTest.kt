package com.ghosttyxr.app.terminal.windowmirror

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Loads the shared repository contract and frozen vectors and proves the
 * Android adapter matches them. Swift runs the same fixtures.
 */
class WindowMirrorContractCompatibilityTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun contractConstantsMatchAndroidAdapter() {
        val contract = loadJson("contract-v1.json").jsonObject
        assertEquals(WINDOW_MIRROR_RELAY_PORT, contract.int("relayPort"))
        assertEquals(WINDOW_MIRROR_WIRE_VERSION, contract.int("wireVersion"))
        assertEquals(WINDOW_MIRROR_FEATURE_VERSION, contract.int("featureVersion"))
        assertEquals(6, contract.int("headerBytes"))
        assertEquals(WINDOW_MIRROR_MAX_FRAME_BYTES, contract.int("maxFrameBytes"))
    }

    @Test
    fun contractFrameTableMatchesEnumExactly() {
        val frames = loadJson("contract-v1.json").jsonObject["frames"]!!.jsonArray
        val contractIds = frames.map { it.jsonObject.int("id")!! }.sorted()
        val implIds = WindowMirrorFrameType.entries.map { it.id }.sorted()
        assertEquals(implIds, contractIds)

        val contractNames = frames.associate {
            it.jsonObject.int("id")!! to it.jsonObject.string("name")!!
        }
        for (type in WindowMirrorFrameType.entries) {
            val expected = contractNames[type.id]
            assertNotNull("contract missing frame id ${type.id}", expected)
            // Enum names are PascalCase; contract uses camelCase (mediaSession → MediaSession).
            assertEquals(camelToPascal(expected!!), type.name)
            assertNotNull(itDirection(frames, type.id))
            assertNotNull(itTransport(frames, type.id))
        }
    }

    @Test
    fun validVectorsDecodeThroughAndroidAdapter() {
        val cases = loadJson("vectors/valid.json").jsonObject["cases"]!!.jsonArray
        for (caseElement in cases) {
            val case = caseElement.jsonObject
            val name = case.string("name")!!
            val complete = case.string("completeBytes") ?: continue
            val bytes = complete.hexToBytes()
            val chunkSizes = case["chunkSizes"]?.jsonArray?.map { it.jsonPrimitive.intOrNull!! }

            val frames = if (chunkSizes != null) {
                acceptChunked(bytes, chunkSizes)
            } else {
                WindowMirrorFrameDecoder().accept(bytes)
            }

            val expectedSeq = case["expectedFrameSequence"]?.jsonArray?.map {
                it.jsonPrimitive.content
            }
            if (expectedSeq != null) {
                assertEquals(
                    "case $name frame sequence",
                    expectedSeq,
                    frames.map { frameName(it.type) },
                )
            }

            val expected = case["expected"]?.jsonObject
            if (expected != null && frames.size == 1) {
                assertExpectedPayload(name, frames.single(), expected)
            }

            // Structural re-parse of payload object when present.
            case["payload"]?.jsonObject?.let { payloadObj ->
                if (frames.size == 1 && frames.single().payload.isNotEmpty()) {
                    val decoded = json.parseToJsonElement(
                        frames.single().payload.toString(Charsets.UTF_8),
                    ).jsonObject
                    assertJsonSubset(name, payloadObj, decoded)
                }
            }
        }
    }

    @Test
    fun invalidVectorsMatchExpectedRecovery() {
        val cases = loadJson("vectors/invalid.json").jsonObject["cases"]!!.jsonArray
        for (caseElement in cases) {
            val case = caseElement.jsonObject
            val name = case.string("name")!!
            val expected = case.string("expected")!!

            when (expected) {
                "wait_for_more_bytes" -> {
                    val frames = WindowMirrorFrameDecoder().accept(case.string("bytes")!!.hexToBytes())
                    assertTrue("case $name should wait", frames.isEmpty())
                }
                "reject_and_reset_decoder" -> {
                    val decoder = WindowMirrorFrameDecoder()
                    try {
                        decoder.accept(case.string("bytes")!!.hexToBytes())
                        fail("case $name expected reject")
                    } catch (_: WindowMirrorProtocolException) {
                    }
                    assertEquals(
                        WindowMirrorFrameType.Ping,
                        decoder.accept(WindowMirrorProtocol.pingFrame()).single().type,
                    )
                }
                "reject_message_payload" -> {
                    val frame = WindowMirrorFrameDecoder()
                        .accept(case.string("bytes")!!.hexToBytes())
                        .single()
                    try {
                        // Video is binary, so its rejection path is the slice
                        // decoder rather than the typed JSON parser.
                        if (case.string("errorKind") == "truncated_slice_header") {
                            decodeWindowMirrorVideoSlice(frame.payload)
                        } else {
                            WindowMirrorProtocol.parse(frame)
                        }
                        fail("case $name expected payload reject")
                    } catch (_: Exception) {
                        // kotlinx parse errors or protocol exceptions both count.
                    }
                }
                "reject_hello_insufficient_feature_version" -> {
                    val frame = WindowMirrorFrameDecoder()
                        .accept(case.string("bytes")!!.hexToBytes())
                        .single()
                    val hello = WindowMirrorProtocol.parse(frame) as WindowMirrorMessage.Hello
                    assertTrue(!WindowMirrorProtocol.isFeatureCompatible(hello.version))
                    assertEquals(case.int("featureVersion"), hello.version)
                    assertEquals(WINDOW_MIRROR_FEATURE_VERSION, case.int("minimumAccepted"))
                }
                "reject_then_accept_after_reset" -> {
                    val decoder = WindowMirrorFrameDecoder()
                    try {
                        decoder.accept(case.string("resetTriggerHex")!!.hexToBytes())
                        fail("case $name expected reset trigger")
                    } catch (_: WindowMirrorProtocolException) {
                    }
                    val recovered = decoder.accept(case.string("recoveryHex")!!.hexToBytes())
                    assertEquals(WindowMirrorFrameType.Ping, recovered.single().type)
                }
                else -> fail("unknown expected $expected for $name")
            }
        }
    }

    @Test
    fun outboundHelloAndPingMatchFrozenCompleteBytes() {
        val cases = loadJson("vectors/valid.json").jsonObject["cases"]!!.jsonArray
        val byName = cases.associate { it.jsonObject.string("name")!! to it.jsonObject }

        // Header-only frames are fully canonical.
        assertArrayEquals(
            byName["ping"]!!.string("completeBytes")!!.hexToBytes(),
            WindowMirrorProtocol.pingFrame(),
        )
        assertArrayEquals(
            byName["pong"]!!.string("completeBytes")!!.hexToBytes(),
            WindowMirrorProtocol.pongFrame(),
        )
        assertArrayEquals(
            byName["media_hello"]!!.string("completeBytes")!!.hexToBytes(),
            WindowMirrorProtocol.mediaHelloFrame("tok-abc"),
        )
        assertArrayEquals(
            byName["codec_support"]!!.string("completeBytes")!!.hexToBytes(),
            WindowMirrorProtocol.codecSupportFrame(listOf("h264", "hevc"), lowLatency = true),
        )
        assertArrayEquals(
            byName["key_frame_request"]!!.string("completeBytes")!!.hexToBytes(),
            WindowMirrorProtocol.keyFrameRequestFrame("gap"),
        )
        assertArrayEquals(
            byName["latency_report"]!!.string("completeBytes")!!.hexToBytes(),
            WindowMirrorProtocol.latencyReportFrame(
                endToEndMs = 42.5,
                decodedFps = 90.0,
                decodeQueueDepth = 1,
            ),
        )

        // Hello: header exact + structural JSON (key order may differ).
        val helloBytes = WindowMirrorProtocol.helloFrame()
        assertEquals(WINDOW_MIRROR_WIRE_VERSION, helloBytes[0].toInt() and 0xFF)
        assertEquals(WindowMirrorFrameType.Hello.id, helloBytes[1].toInt() and 0xFF)
        val helloFrame = WindowMirrorFrameDecoder().accept(helloBytes).single()
        val helloJson = json.parseToJsonElement(helloFrame.payload.toString(Charsets.UTF_8)).jsonObject
        assertEquals("headset", helloJson.string("role"))
        assertEquals("ghosttyxr", helloJson.string("app"))
        assertEquals(WINDOW_MIRROR_FEATURE_VERSION, helloJson.int("version"))
    }

    private fun assertExpectedPayload(name: String, frame: WindowMirrorFrame, expected: JsonObject) {
        when (frame.type) {
            WindowMirrorFrameType.Hello -> {
                val msg = WindowMirrorProtocol.parse(frame) as WindowMirrorMessage.Hello
                expected.string("role")?.let { assertEquals(name, it, msg.role) }
                expected.int("version")?.let { assertEquals(name, it, msg.version) }
            }
            WindowMirrorFrameType.Ping -> assertEquals(WindowMirrorMessage.Ping, WindowMirrorProtocol.parse(frame))
            WindowMirrorFrameType.Pong -> assertEquals(WindowMirrorMessage.Pong, WindowMirrorProtocol.parse(frame))
            WindowMirrorFrameType.Ready -> assertEquals(WindowMirrorMessage.Ready, WindowMirrorProtocol.parse(frame))
            WindowMirrorFrameType.Reset -> assertEquals(WindowMirrorMessage.Reset, WindowMirrorProtocol.parse(frame))
            WindowMirrorFrameType.Metadata -> {
                val meta = (WindowMirrorProtocol.parse(frame) as WindowMirrorMessage.Metadata).metadata
                expected.long("id")?.let { assertEquals(name, it, meta.id) }
                expected.string("kind")?.let { assertEquals(name, it, meta.kind) }
                expected.string("codec")?.let { assertEquals(name, it, meta.codec) }
                expected.string("title")?.let { assertEquals(name, it, meta.title) }
                expected.int("width")?.let { assertEquals(name, it, meta.width) }
                expected.int("height")?.let { assertEquals(name, it, meta.height) }
                expected.boolean("isDisplayShare")?.let { assertEquals(name, it, meta.isDisplayShare) }
            }
            WindowMirrorFrameType.StreamState -> {
                val msg = WindowMirrorProtocol.parse(frame) as WindowMirrorMessage.StreamState
                expected.string("status")?.let { assertEquals(name, it, msg.status) }
                expected.string("detail")?.let { assertEquals(name, it, msg.detail) }
                expected.boolean("interactive")?.let { assertEquals(name, it, msg.interactive) }
                expected.string("transport")?.let { assertEquals(name, it, msg.transport) }
            }
            WindowMirrorFrameType.Close -> {
                val msg = WindowMirrorProtocol.parse(frame) as WindowMirrorMessage.Close
                if (expected.containsKey("reason") && expected["reason"] is JsonNull) {
                    assertEquals(name, null, msg.reason)
                } else {
                    expected.string("reason")?.let { assertEquals(name, it, msg.reason) }
                }
            }
            WindowMirrorFrameType.Input -> {
                // Headset adapter rejects inbound input.
                try {
                    WindowMirrorProtocol.parse(frame)
                    fail("$name: input should be outbound-only on Android")
                } catch (_: WindowMirrorProtocolException) {
                }
            }
            WindowMirrorFrameType.PointerHandoff -> {
                val msg = WindowMirrorProtocol.parse(frame) as WindowMirrorMessage.PointerHandoff
                expected.long("seq")?.let { assertEquals(name, it, msg.seq) }
                expected.string("edge")?.let { assertEquals(name, it, msg.edge) }
                expected.double("y")?.let { assertEquals(name, it.toFloat(), msg.y, 1e-5f) }
            }
            WindowMirrorFrameType.PointerReturn -> {
                assertEquals(WindowMirrorMessage.PointerReturned, WindowMirrorProtocol.parse(frame))
            }
            WindowMirrorFrameType.MouseDelta -> {
                val msg = WindowMirrorProtocol.parse(frame) as WindowMirrorMessage.MouseDelta
                expected.long("seq")?.let { assertEquals(name, it, msg.seq) }
                expected.double("dx")?.let { assertEquals(name, it.toFloat(), msg.dx, 1e-5f) }
                expected.double("dy")?.let { assertEquals(name, it.toFloat(), msg.dy, 1e-5f) }
            }
            WindowMirrorFrameType.MouseButton -> {
                val msg = WindowMirrorProtocol.parse(frame) as WindowMirrorMessage.MouseButton
                expected.long("seq")?.let { assertEquals(name, it, msg.seq) }
                expected.string("button")?.let { assertEquals(name, it, msg.button) }
                expected.string("action")?.let { assertEquals(name, it, msg.action) }
            }
            WindowMirrorFrameType.MouseScroll -> {
                val msg = WindowMirrorProtocol.parse(frame) as WindowMirrorMessage.MouseScroll
                expected.long("seq")?.let { assertEquals(name, it, msg.seq) }
                expected.double("dx")?.let { assertEquals(name, it.toFloat(), msg.dx, 1e-5f) }
                expected.double("dy")?.let { assertEquals(name, it.toFloat(), msg.dy, 1e-5f) }
            }
            WindowMirrorFrameType.MediaSession -> {
                val msg = WindowMirrorProtocol.parse(frame) as WindowMirrorMessage.MediaSession
                expected.string("token")?.let { assertEquals(name, it, msg.token) }
                expected.string("host")?.let { assertEquals(name, it, msg.host) }
                expected.int("port")?.let { assertEquals(name, it, msg.port) }
                expected.string("codec")?.let { assertEquals(name, it, msg.codec) }
                expected.boolean("encrypted")?.let { assertEquals(name, it, msg.encrypted) }
            }
            WindowMirrorFrameType.VideoSlice -> {
                val slice = decodeWindowMirrorVideoSlice(frame.payload)
                expected.long("sequence")?.let { assertEquals(name, it, slice.sequence) }
                expected.long("ptsUs")?.let { assertEquals(name, it, slice.ptsUs) }
                expected.int("width")?.let { assertEquals(name, it, slice.width) }
                expected.int("height")?.let { assertEquals(name, it, slice.height) }
                expected.int("sliceIndex")?.let { assertEquals(name, it, slice.sliceIndex) }
                expected.int("sliceCount")?.let { assertEquals(name, it, slice.sliceCount) }
                expected.boolean("isKeyFrame")?.let { assertEquals(name, it, slice.isKeyFrame) }
                expected.boolean("isFrameEnd")?.let { assertEquals(name, it, slice.isFrameEnd) }
                expected.string("bitstreamHex")?.let {
                    assertArrayEquals(name, it.hexToBytes(), slice.bitstream)
                }
            }
            // Media-path negotiation frames are built by the headset, never parsed
            // by it; the contract-relevant behaviour is that inbound ones reject.
            WindowMirrorFrameType.MediaHello,
            WindowMirrorFrameType.CodecSupport,
            WindowMirrorFrameType.KeyFrameRequest,
            WindowMirrorFrameType.LatencyReport,
            -> {
                try {
                    WindowMirrorProtocol.parse(frame)
                    fail("$name: ${frame.type.name} should be outbound-only on Android")
                } catch (_: WindowMirrorProtocolException) {
                }
            }
        }
    }

    private fun acceptChunked(bytes: ByteArray, chunkSizes: List<Int>): List<WindowMirrorFrame> {
        val decoder = WindowMirrorFrameDecoder()
        val out = mutableListOf<WindowMirrorFrame>()
        var offset = 0
        for (size in chunkSizes) {
            if (offset >= bytes.size) break
            val next = minOf(offset + size, bytes.size)
            out += decoder.accept(bytes.copyOfRange(offset, next))
            offset = next
        }
        if (offset < bytes.size) {
            out += decoder.accept(bytes.copyOfRange(offset, bytes.size))
        }
        return out
    }

    private fun assertJsonSubset(name: String, expected: JsonObject, actual: JsonObject) {
        for ((key, value) in expected) {
            assertTrue("$name missing key $key in $actual", actual.containsKey(key))
            val got = actual[key]
            when (value) {
                is JsonPrimitive -> {
                    if (value.isString) {
                        assertEquals("$name.$key", value.content, got?.jsonPrimitive?.contentOrNull)
                    } else if (value.booleanOrNull != null) {
                        assertEquals("$name.$key", value.booleanOrNull, got?.jsonPrimitive?.booleanOrNull)
                    } else if (value.intOrNull != null && got?.jsonPrimitive?.doubleOrNull != null) {
                        // Allow 2 vs 2.0
                        assertEquals(
                            "$name.$key",
                            value.doubleOrNull ?: value.intOrNull!!.toDouble(),
                            got.jsonPrimitive.doubleOrNull!!,
                            1e-9,
                        )
                    } else {
                        assertEquals("$name.$key", value.content, got?.jsonPrimitive?.content)
                    }
                }
                JsonNull -> assertTrue("$name.$key", got == null || got is JsonNull)
                else -> Unit
            }
        }
    }

    private fun loadJson(path: String): JsonElement {
        val stream = javaClass.classLoader!!.getResourceAsStream(path)
            ?: error("Missing test resource $path — is ghosttyxr/protocol/window-mirror wired into test resources?")
        return json.parseToJsonElement(stream.bufferedReader().readText())
    }

    private fun frameName(type: WindowMirrorFrameType): String = when (type) {
        WindowMirrorFrameType.Hello -> "hello"
        WindowMirrorFrameType.Ready -> "ready"
        WindowMirrorFrameType.Ping -> "ping"
        WindowMirrorFrameType.Pong -> "pong"
        WindowMirrorFrameType.Metadata -> "metadata"
        WindowMirrorFrameType.Input -> "input"
        WindowMirrorFrameType.StreamState -> "streamState"
        WindowMirrorFrameType.Close -> "close"
        WindowMirrorFrameType.Reset -> "reset"
        WindowMirrorFrameType.MouseDelta -> "mouseDelta"
        WindowMirrorFrameType.MouseButton -> "mouseButton"
        WindowMirrorFrameType.MouseScroll -> "mouseScroll"
        WindowMirrorFrameType.PointerHandoff -> "pointerHandoff"
        WindowMirrorFrameType.PointerReturn -> "pointerReturn"
        WindowMirrorFrameType.VideoSlice -> "videoSlice"
        WindowMirrorFrameType.MediaHello -> "mediaHello"
        WindowMirrorFrameType.MediaSession -> "mediaSession"
        WindowMirrorFrameType.CodecSupport -> "codecSupport"
        WindowMirrorFrameType.KeyFrameRequest -> "keyFrameRequest"
        WindowMirrorFrameType.LatencyReport -> "latencyReport"
    }

    private fun itDirection(frames: JsonArray, id: Int): String? =
        frames.firstOrNull { it.jsonObject.int("id") == id }?.jsonObject?.string("direction")

    private fun itTransport(frames: JsonArray, id: Int): String? =
        frames.firstOrNull { it.jsonObject.int("id") == id }?.jsonObject?.string("transport")

    private fun camelToPascal(name: String): String =
        name.replaceFirstChar { it.uppercaseChar() }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0) { "odd hex length" }
        return ByteArray(length / 2) { i ->
            substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.long(key: String): Long? =
        this[key]?.jsonPrimitive?.longOrNull ?: this[key]?.jsonPrimitive?.intOrNull?.toLong()

    private fun JsonObject.double(key: String): Double? =
        this[key]?.jsonPrimitive?.doubleOrNull

    private fun JsonObject.boolean(key: String): Boolean? =
        this[key]?.jsonPrimitive?.booleanOrNull
}
