package com.ghosttyxr.app.terminal.windowmirror

import android.view.KeyCharacterMap
import android.view.KeyEvent
import com.ghosttyxr.app.terminal.input.GhosttyKeyMods
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import java.nio.ByteOrder

const val WINDOW_MIRROR_RELAY_PORT = 45_874

/**
 * Direct LAN TCP port carrying video. Separate from the control channel so a
 * large frame can never delay a keyframe request or pointer event.
 */
const val WINDOW_MIRROR_MEDIA_PORT = 45_876

/**
 * On-wire frame header version. Feature compatibility is negotiated via the
 * `version` field in the hello JSON payload.
 */
internal const val WINDOW_MIRROR_WIRE_VERSION = 1

/**
 * Feature version 5 carries control over the SSH tunnel and video over a
 * dedicated direct TCP media socket. There is no jitter buffer, pacer, or
 * congestion controller: latency stays bounded because little is ever in
 * flight, not because a buffer absorbs jitter.
 */
internal const val WINDOW_MIRROR_FEATURE_VERSION = 5

internal const val WINDOW_MIRROR_MAX_FRAME_BYTES = 8 * 1024 * 1024

class WindowMirrorProtocolException(message: String) : IllegalArgumentException(message)

enum class WindowMirrorFrameType(val id: Int) {
    Hello(1),
    Ready(2),
    Ping(3),
    Pong(4),
    Metadata(5),
    Input(7),
    StreamState(8),
    Close(9),
    Reset(10),

    // Symbiosis (full-screen share) pointer frames. Mac → headset except PointerReturn.
    MouseDelta(11),
    MouseButton(12),
    MouseScroll(13),
    PointerHandoff(14),
    PointerReturn(15),

    // Direct-TCP media path (feature version 5).
    VideoSlice(20),
    MediaHello(21),
    MediaSession(22),
    CodecSupport(23),
    KeyFrameRequest(24),
    LatencyReport(25);

    companion object {
        private val byId = entries.associateBy { it.id }

        fun fromId(id: Int): WindowMirrorFrameType =
            byId[id] ?: throw WindowMirrorProtocolException("unsupported frame type $id")
    }
}

sealed interface WindowMirrorMessage {
    data class Hello(
        val role: String?,
        val version: Int,
    ) : WindowMirrorMessage
    data object Ready : WindowMirrorMessage
    data object Ping : WindowMirrorMessage
    data object Pong : WindowMirrorMessage
    data class Metadata(val metadata: WindowMirrorMetadata) : WindowMirrorMessage
    data class StreamState(
        val status: String,
        val detail: String?,
        val interactive: Boolean,
        val transport: String?,
    ) : WindowMirrorMessage

    data class Close(val reason: String?) : WindowMirrorMessage
    data object Reset : WindowMirrorMessage

    /**
     * Mac → headset: where and how to open the direct media socket. [token]
     * authenticates that socket and, when [encrypted], keys its AES-GCM seal.
     */
    data class MediaSession(
        val token: String,
        val host: String,
        val port: Int,
        val codec: String,
        val encrypted: Boolean,
    ) : WindowMirrorMessage

    /** Relative mouse movement while XR owns the pointer (Symbiosis). */
    data class MouseDelta(val seq: Long, val dx: Float, val dy: Float) : WindowMirrorMessage

    /** Mouse button transition while XR owns the pointer. */
    data class MouseButton(val seq: Long, val button: String, val action: String) : WindowMirrorMessage

    /** Scroll wheel while XR owns the pointer. */
    data class MouseScroll(val seq: Long, val dx: Float, val dy: Float) : WindowMirrorMessage

    /** Mac handed the pointer to XR (keyboard toggle or legacy edge handoff). */
    data class PointerHandoff(val seq: Long, val edge: String, val y: Float) : WindowMirrorMessage

    /** Mac reclaimed the pointer (emergency chord / disconnect); XR cursor goes away. */
    data object PointerReturned : WindowMirrorMessage
}

object WindowMirrorProtocol {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /** True when a peer hello feature version meets the negotiated minimum. */
    fun isFeatureCompatible(version: Int): Boolean =
        version >= WINDOW_MIRROR_FEATURE_VERSION

    fun parse(frame: WindowMirrorFrame): WindowMirrorMessage =
        when (frame.type) {
            WindowMirrorFrameType.Hello -> {
                val obj = frame.jsonPayload()
                WindowMirrorMessage.Hello(
                    role = obj.string("role"),
                    version = obj.int("version") ?: 1,
                )
            }
            WindowMirrorFrameType.Ready -> WindowMirrorMessage.Ready
            WindowMirrorFrameType.Ping -> WindowMirrorMessage.Ping
            WindowMirrorFrameType.Pong -> WindowMirrorMessage.Pong
            WindowMirrorFrameType.Metadata -> {
                val obj = frame.jsonPayload()
                WindowMirrorMessage.Metadata(
                    WindowMirrorMetadata(
                        id = obj.long("id") ?: 0L,
                        title = obj.string("title").orEmpty(),
                        app = obj.string("app").orEmpty(),
                        width = obj.int("width") ?: 1,
                        height = obj.int("height") ?: 1,
                        scale = obj.double("scale") ?: 1.0,
                        interactive = obj.boolean("interactive") ?: false,
                        kind = obj.string("kind") ?: "window",
                        codec = obj.string("codec") ?: "h264",
                    ),
                )
            }
            WindowMirrorFrameType.StreamState -> {
                val obj = frame.jsonPayload()
                WindowMirrorMessage.StreamState(
                    status = obj.string("status").orEmpty(),
                    detail = obj.string("detail"),
                    interactive = obj.boolean("interactive") ?: false,
                    transport = obj.string("transport"),
                )
            }
            WindowMirrorFrameType.Close -> {
                val reason = runCatching { frame.jsonPayload().string("reason") }.getOrNull()
                WindowMirrorMessage.Close(reason)
            }
            WindowMirrorFrameType.Reset -> WindowMirrorMessage.Reset
            WindowMirrorFrameType.MediaSession -> {
                val obj = frame.jsonPayload()
                // No silent fallbacks: an absent token or host is a real error,
                // never a guessed value.
                val token = obj.string("token")?.takeIf { it.isNotBlank() }
                    ?: throw WindowMirrorProtocolException("media session omitted token")
                val host = obj.string("host")?.takeIf { it.isNotBlank() }
                    ?: throw WindowMirrorProtocolException("media session omitted host")
                WindowMirrorMessage.MediaSession(
                    token = token,
                    host = host,
                    port = obj.int("port") ?: WINDOW_MIRROR_MEDIA_PORT,
                    codec = obj.string("codec") ?: "h264",
                    encrypted = obj.boolean("encrypted") ?: true,
                )
            }
            WindowMirrorFrameType.MouseDelta -> {
                val obj = frame.jsonPayload()
                WindowMirrorMessage.MouseDelta(
                    seq = obj.long("seq") ?: 0L,
                    dx = obj.double("dx")?.toFloat() ?: 0f,
                    dy = obj.double("dy")?.toFloat() ?: 0f,
                )
            }
            WindowMirrorFrameType.MouseButton -> {
                val obj = frame.jsonPayload()
                WindowMirrorMessage.MouseButton(
                    seq = obj.long("seq") ?: 0L,
                    button = obj.string("button") ?: "left",
                    action = obj.string("action").orEmpty(),
                )
            }
            WindowMirrorFrameType.MouseScroll -> {
                val obj = frame.jsonPayload()
                WindowMirrorMessage.MouseScroll(
                    seq = obj.long("seq") ?: 0L,
                    dx = obj.double("dx")?.toFloat() ?: 0f,
                    dy = obj.double("dy")?.toFloat() ?: 0f,
                )
            }
            WindowMirrorFrameType.PointerHandoff -> {
                val obj = frame.jsonPayload()
                WindowMirrorMessage.PointerHandoff(
                    seq = obj.long("seq") ?: 0L,
                    edge = obj.string("edge") ?: "right",
                    y = obj.double("y")?.toFloat() ?: 0.5f,
                )
            }
            // Inbound PointerReturn = the Mac force-reclaimed the pointer (emergency
            // chord); payload coordinates are ignored, the XR cursor just goes away.
            WindowMirrorFrameType.PointerReturn -> WindowMirrorMessage.PointerReturned
            WindowMirrorFrameType.Input -> throw WindowMirrorProtocolException("input frames are outbound only")
            WindowMirrorFrameType.VideoSlice ->
                throw WindowMirrorProtocolException("video slices decode via WindowMirrorVideoSlice, not parse()")
            WindowMirrorFrameType.MediaHello,
            WindowMirrorFrameType.CodecSupport,
            WindowMirrorFrameType.KeyFrameRequest,
            WindowMirrorFrameType.LatencyReport,
            -> throw WindowMirrorProtocolException("${frame.type.name} frames are outbound only")
        }

    fun helloFrame(): ByteArray = jsonFrame(
        WindowMirrorFrameType.Hello,
        buildJsonObject {
            put("role", "headset")
            put("app", "ghosttyxr")
            put("version", WINDOW_MIRROR_FEATURE_VERSION)
        },
    )

    fun readyFrame(): ByteArray = frame(WindowMirrorFrameType.Ready)

    fun pongFrame(): ByteArray = frame(WindowMirrorFrameType.Pong)

    fun pingFrame(): ByteArray = frame(WindowMirrorFrameType.Ping)

    fun closeFrame(reason: String): ByteArray = jsonFrame(
        WindowMirrorFrameType.Close,
        buildJsonObject { put("reason", reason) },
    )

    /** First frame on the media socket; proves this client owns the session. */
    fun mediaHelloFrame(token: String): ByteArray = jsonFrame(
        WindowMirrorFrameType.MediaHello,
        buildJsonObject { put("token", token) },
    )

    /** Advertises the codecs this headset actually has a hardware decoder for. */
    fun codecSupportFrame(codecs: List<String>, lowLatency: Boolean): ByteArray = jsonFrame(
        WindowMirrorFrameType.CodecSupport,
        buildJsonObject {
            put("codecs", buildJsonArray { codecs.forEach { add(it) } })
            put("lowLatency", lowLatency)
        },
    )

    /**
     * Receiver-driven IDR request. Replaces a periodic GOP, so there is no
     * recurring keyframe bitrate spike to hitch on.
     */
    fun keyFrameRequestFrame(reason: String): ByteArray = jsonFrame(
        WindowMirrorFrameType.KeyFrameRequest,
        buildJsonObject { put("reason", reason) },
    )

    /** Measured end-to-end latency; the Mac adapts bitrate from this. */
    fun latencyReportFrame(
        endToEndMs: Double,
        decodedFps: Double,
        decodeQueueDepth: Int,
    ): ByteArray = jsonFrame(
        WindowMirrorFrameType.LatencyReport,
        buildJsonObject {
            put("endToEndMs", endToEndMs)
            put("decodedFps", decodedFps)
            put("decodeQueueDepth", decodeQueueDepth)
        },
    )

    fun pointerFrame(action: String, x: Float, y: Float): ByteArray = inputFrame(
        buildJsonObject {
            put("kind", "pointer")
            put("action", action)
            put("button", "left")
            put("x", x.coerceIn(0f, 1f))
            put("y", y.coerceIn(0f, 1f))
        },
    )

    fun pointerReturnFrame(x: Float, y: Float): ByteArray = jsonFrame(
        WindowMirrorFrameType.PointerReturn,
        buildJsonObject {
            put("x", x.coerceIn(0f, 1f))
            put("y", y.coerceIn(0f, 1f))
        },
    )

    fun scrollFrame(x: Float, y: Float, dx: Float, dy: Float): ByteArray = inputFrame(
        buildJsonObject {
            put("kind", "scroll")
            put("x", x.coerceIn(0f, 1f))
            put("y", y.coerceIn(0f, 1f))
            put("dx", dx)
            put("dy", dy)
        },
    )

    fun keyFrame(event: KeyEvent): ByteArray? {
        val key = WindowMirrorKeyMapper.map(event) ?: return null
        return inputFrame(
            buildJsonObject {
                put("kind", "key")
                put("action", key.action)
                put("code", key.code)
                put("mods", key.mods)
                key.text?.let { put("text", it) }
            },
        )
    }

    private fun inputFrame(obj: JsonObject): ByteArray = jsonFrame(WindowMirrorFrameType.Input, obj)

    private fun jsonFrame(type: WindowMirrorFrameType, obj: JsonObject): ByteArray =
        frame(type, json.encodeToString(JsonObject.serializer(), obj).toByteArray(Charsets.UTF_8))

    private fun frame(type: WindowMirrorFrameType, payload: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteBuffer.allocate(6 + payload.size).order(ByteOrder.BIG_ENDIAN)
        out.put(WINDOW_MIRROR_WIRE_VERSION.toByte())
        out.put(type.id.toByte())
        out.putInt(payload.size)
        out.put(payload)
        return out.array()
    }

    private fun WindowMirrorFrame.jsonPayload(): JsonObject =
        json.parseToJsonElement(payload.toString(Charsets.UTF_8)).jsonObject

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.long(key: String): Long? =
        this[key]?.jsonPrimitive?.longOrNull

    private fun JsonObject.double(key: String): Double? =
        this[key]?.jsonPrimitive?.doubleOrNull

    private fun JsonObject.boolean(key: String): Boolean? =
        this[key]?.jsonPrimitive?.booleanOrNull
}

data class WindowMirrorFrame(
    val type: WindowMirrorFrameType,
    val payload: ByteArray,
)

/** Fixed binary sub-header ahead of every video slice's bitstream. */
internal const val WINDOW_MIRROR_SLICE_HEADER_BYTES = 26

private const val SLICE_FLAG_KEY_FRAME = 0x01
private const val SLICE_FLAG_FRAME_END = 0x02

/**
 * One encoded slice off the media socket. The Mac flushes each slice as
 * VideoToolbox emits it, so encode, transmit, and decode overlap rather than
 * running back to back.
 */
data class WindowMirrorVideoSlice(
    val sequence: Long,
    val ptsUs: Long,
    val width: Int,
    val height: Int,
    val sliceIndex: Int,
    val sliceCount: Int,
    val isKeyFrame: Boolean,
    val isFrameEnd: Boolean,
    val bitstream: ByteArray,
) {
    // Value semantics on the payload would mean hashing the whole bitstream on
    // every comparison, which is pure waste on a 90fps path.
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = sequence.hashCode()
}

/** Decodes the binary payload of a [WindowMirrorFrameType.VideoSlice] frame. */
fun decodeWindowMirrorVideoSlice(payload: ByteArray): WindowMirrorVideoSlice {
    if (payload.size < WINDOW_MIRROR_SLICE_HEADER_BYTES) {
        throw WindowMirrorProtocolException("video slice shorter than its header")
    }
    val header = ByteBuffer.wrap(payload, 0, WINDOW_MIRROR_SLICE_HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
    val sequence = header.long
    val ptsUs = header.long
    val width = header.short.toInt() and 0xFFFF
    val height = header.short.toInt() and 0xFFFF
    val sliceIndex = header.short.toInt() and 0xFFFF
    val sliceCount = header.short.toInt() and 0xFFFF
    val flags = header.get().toInt() and 0xFF
    return WindowMirrorVideoSlice(
        sequence = sequence,
        ptsUs = ptsUs,
        width = width,
        height = height,
        sliceIndex = sliceIndex,
        sliceCount = sliceCount,
        isKeyFrame = flags and SLICE_FLAG_KEY_FRAME != 0,
        isFrameEnd = flags and SLICE_FLAG_FRAME_END != 0,
        bitstream = payload.copyOfRange(WINDOW_MIRROR_SLICE_HEADER_BYTES, payload.size),
    )
}

/**
 * Incremental byte-stream decoder for the 6-byte window-mirror frame header.
 * Named to match the Mac companion decoder domain term (WindowMirrorFrameDecoder).
 */
class WindowMirrorFrameDecoder(
    private val maxFrameBytes: Int = WINDOW_MIRROR_MAX_FRAME_BYTES,
) {
    // Growable ring-less buffer with a consumed-bytes offset: chunks append at
    // [end], frames parse from [start], and the tail compacts only when needed.
    // Avoids re-copying the whole pending buffer per chunk while a large
    // keyframe accumulates.
    private var buffer = ByteArray(INITIAL_CAPACITY)
    private var start = 0
    private var end = 0

    fun accept(chunk: ByteArray): List<WindowMirrorFrame> = accept(chunk, chunk.size)

    /**
     * Accepts the first [length] bytes of [chunk].
     *
     * The media path reads into a reused 64 KiB buffer, so copying the whole
     * array per read would be pure waste at 90fps.
     */
    fun accept(chunk: ByteArray, length: Int): List<WindowMirrorFrame> {
        if (length <= 0) return emptyList()
        ensureCapacity(length)
        System.arraycopy(chunk, 0, buffer, end, length)
        end += length
        val frames = mutableListOf<WindowMirrorFrame>()
        while (end - start >= 6) {
            val version = buffer[start].toInt() and 0xFF
            if (version != WINDOW_MIRROR_WIRE_VERSION) {
                reset()
                throw WindowMirrorProtocolException("unsupported protocol version $version")
            }
            val typeId = buffer[start + 1].toInt() and 0xFF
            val type = try {
                WindowMirrorFrameType.fromId(typeId)
            } catch (error: WindowMirrorProtocolException) {
                // Match Mac decoder: unknown type IDs reset pending bytes.
                reset()
                throw error
            }
            val length = ByteBuffer.wrap(buffer, start + 2, 4).order(ByteOrder.BIG_ENDIAN).int
            if (length < 0 || length > maxFrameBytes) {
                reset()
                throw WindowMirrorProtocolException("window mirror frame exceeded $maxFrameBytes bytes")
            }
            val frameLength = 6 + length
            if (end - start < frameLength) break
            frames += WindowMirrorFrame(type, buffer.copyOfRange(start + 6, start + frameLength))
            start += frameLength
        }
        if (start == end) {
            start = 0
            end = 0
        }
        return frames
    }

    fun reset() {
        start = 0
        end = 0
        if (buffer.size > INITIAL_CAPACITY) {
            buffer = ByteArray(INITIAL_CAPACITY)
        }
    }

    private fun ensureCapacity(incoming: Int) {
        if (end + incoming <= buffer.size) return
        val pending = end - start
        if (pending + incoming <= buffer.size) {
            System.arraycopy(buffer, start, buffer, 0, pending)
        } else {
            var newSize = buffer.size
            while (newSize < pending + incoming) {
                newSize *= 2
            }
            val grown = ByteArray(newSize)
            System.arraycopy(buffer, start, grown, 0, pending)
            buffer = grown
        }
        start = 0
        end = pending
    }

    private companion object {
        const val INITIAL_CAPACITY = 64 * 1024
    }
}

private data class MirrorKey(
    val code: String,
    val text: String?,
    val mods: Int,
    val action: String,
)

private object WindowMirrorKeyMapper {
    fun map(event: KeyEvent): MirrorKey? {
        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount > 0) "repeat" else "press"
            KeyEvent.ACTION_UP -> "release"
            else -> return null
        }
        val code = keyCodeToW3C[event.keyCode] ?: return null
        val text = if (event.action == KeyEvent.ACTION_DOWN) {
            val codepoint = event.unicodeChar and KeyCharacterMap.COMBINING_ACCENT.inv()
            if (codepoint > 0 && Character.isValidCodePoint(codepoint)) {
                String(Character.toChars(codepoint))
            } else {
                null
            }
        } else {
            null
        }
        return MirrorKey(code = code, text = text, mods = mods(event.metaState), action = action)
    }

    private fun mods(metaState: Int): Int {
        var mods = 0
        if (metaState and KeyEvent.META_SHIFT_ON != 0) mods = mods or GhosttyKeyMods.SHIFT
        if (metaState and KeyEvent.META_CTRL_ON != 0) mods = mods or GhosttyKeyMods.CONTROL
        if (metaState and KeyEvent.META_ALT_ON != 0) mods = mods or GhosttyKeyMods.ALT
        if (metaState and KeyEvent.META_META_ON != 0) mods = mods or GhosttyKeyMods.SUPER
        if (metaState and KeyEvent.META_CAPS_LOCK_ON != 0) mods = mods or GhosttyKeyMods.CAPS_LOCK
        return mods
    }

    private val keyCodeToW3C: Map<Int, String> = buildMap {
        for (keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
            put(keyCode, "Key${'A' + (keyCode - KeyEvent.KEYCODE_A)}")
        }
        for (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            put(keyCode, "Digit${keyCode - KeyEvent.KEYCODE_0}")
        }
        for (keyCode in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12) {
            put(keyCode, "F${keyCode - KeyEvent.KEYCODE_F1 + 1}")
        }
        putAll(
            mapOf(
                KeyEvent.KEYCODE_SPACE to "Space",
                KeyEvent.KEYCODE_ENTER to "Enter",
                KeyEvent.KEYCODE_TAB to "Tab",
                KeyEvent.KEYCODE_ESCAPE to "Escape",
                KeyEvent.KEYCODE_DEL to "Backspace",
                KeyEvent.KEYCODE_FORWARD_DEL to "Delete",
                KeyEvent.KEYCODE_DPAD_LEFT to "ArrowLeft",
                KeyEvent.KEYCODE_DPAD_RIGHT to "ArrowRight",
                KeyEvent.KEYCODE_DPAD_UP to "ArrowUp",
                KeyEvent.KEYCODE_DPAD_DOWN to "ArrowDown",
                KeyEvent.KEYCODE_PAGE_UP to "PageUp",
                KeyEvent.KEYCODE_PAGE_DOWN to "PageDown",
                KeyEvent.KEYCODE_MOVE_HOME to "Home",
                KeyEvent.KEYCODE_MOVE_END to "End",
                KeyEvent.KEYCODE_INSERT to "Insert",
                KeyEvent.KEYCODE_GRAVE to "Backquote",
                KeyEvent.KEYCODE_MINUS to "Minus",
                KeyEvent.KEYCODE_EQUALS to "Equal",
                KeyEvent.KEYCODE_LEFT_BRACKET to "BracketLeft",
                KeyEvent.KEYCODE_RIGHT_BRACKET to "BracketRight",
                KeyEvent.KEYCODE_BACKSLASH to "Backslash",
                KeyEvent.KEYCODE_SEMICOLON to "Semicolon",
                KeyEvent.KEYCODE_APOSTROPHE to "Quote",
                KeyEvent.KEYCODE_COMMA to "Comma",
                KeyEvent.KEYCODE_PERIOD to "Period",
                KeyEvent.KEYCODE_SLASH to "Slash",
                KeyEvent.KEYCODE_META_LEFT to "MetaLeft",
                KeyEvent.KEYCODE_META_RIGHT to "MetaRight",
                KeyEvent.KEYCODE_SHIFT_LEFT to "ShiftLeft",
                KeyEvent.KEYCODE_SHIFT_RIGHT to "ShiftRight",
                KeyEvent.KEYCODE_ALT_LEFT to "AltLeft",
                KeyEvent.KEYCODE_ALT_RIGHT to "AltRight",
                KeyEvent.KEYCODE_CTRL_LEFT to "ControlLeft",
                KeyEvent.KEYCODE_CTRL_RIGHT to "ControlRight",
                KeyEvent.KEYCODE_CAPS_LOCK to "CapsLock",
            ),
        )
    }
}
