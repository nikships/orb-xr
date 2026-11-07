package com.ghosttyxr.app.terminal.hostkeyboard

import com.ghosttyxr.app.terminal.input.GhosttyKeyAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream

const val HOST_KEYBOARD_RELAY_PORT = 45_873
internal const val HOST_KEYBOARD_PROTOCOL_VERSION = 1
internal const val HOST_KEYBOARD_MAX_FRAME_BYTES = 16 * 1024

class HostKeyboardProtocolException(message: String) : IllegalArgumentException(message)

sealed interface HostKeyboardMessage {
    data class Hello(val role: String?) : HostKeyboardMessage
    data object Ready : HostKeyboardMessage
    data object Ping : HostKeyboardMessage
    data object Pong : HostKeyboardMessage
    data class Reset(val reason: String?) : HostKeyboardMessage
    data class CaptureState(
        val armed: Boolean,
        val capturing: Boolean,
        val permission: String?,
        val reason: String?,
    ) : HostKeyboardMessage

    data class Key(
        val seq: Long,
        val code: String,
        val text: String?,
        val mods: Int,
        val action: Int,
    ) : HostKeyboardMessage
}

object HostKeyboardProtocol {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun parse(raw: String): HostKeyboardMessage {
        val obj = json.parseToJsonElement(raw).jsonObject
        val version = obj.int("v") ?: throw HostKeyboardProtocolException("missing protocol version")
        if (version != HOST_KEYBOARD_PROTOCOL_VERSION) {
            throw HostKeyboardProtocolException("unsupported protocol version $version")
        }
        return when (val type = obj.string("type")) {
            "hello" -> HostKeyboardMessage.Hello(role = obj.string("role"))
            "ready" -> HostKeyboardMessage.Ready
            "ping" -> HostKeyboardMessage.Ping
            "pong" -> HostKeyboardMessage.Pong
            "reset" -> HostKeyboardMessage.Reset(reason = obj.string("reason"))
            "capture_state" -> HostKeyboardMessage.CaptureState(
                armed = obj.boolean("armed") ?: false,
                capturing = obj.boolean("capturing") ?: false,
                permission = obj.string("permission"),
                reason = obj.string("reason"),
            )
            "key" -> HostKeyboardMessage.Key(
                seq = obj.long("seq") ?: throw HostKeyboardProtocolException("missing key seq"),
                code = obj.string("code") ?: throw HostKeyboardProtocolException("missing key code"),
                text = obj.string("text")?.takeIf { it.isNotEmpty() },
                mods = obj.int("mods") ?: 0,
                action = parseKeyAction(obj.string("action")),
            )
            else -> throw HostKeyboardProtocolException("unsupported message type $type")
        }
    }

    fun helloLine(): ByteArray = line("hello") {
        put("role", "headset")
        put("app", "ghosttyxr")
    }

    fun readyLine(): ByteArray = line("ready")

    fun pongLine(): ByteArray = line("pong")

    fun pingLine(): ByteArray = line("ping")

    fun resetLine(reason: String): ByteArray = line("reset") {
        put("reason", reason)
    }

    private fun parseKeyAction(action: String?): Int = when (action) {
        "press" -> GhosttyKeyAction.Press
        "repeat" -> GhosttyKeyAction.Repeat
        "release" -> GhosttyKeyAction.Release
        else -> throw HostKeyboardProtocolException("unsupported key action $action")
    }

    private fun line(type: String, extra: JsonObjectBuilder.() -> Unit = {}): ByteArray {
        val obj = buildJsonObject {
            put("v", HOST_KEYBOARD_PROTOCOL_VERSION)
            put("type", type)
            extra()
        }
        return (json.encodeToString(JsonObject.serializer(), obj) + "\n").toByteArray(Charsets.UTF_8)
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.long(key: String): Long? =
        this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()

    private fun JsonObject.boolean(key: String): Boolean? =
        this[key]?.jsonPrimitive?.booleanOrNull
}

class HostKeyboardLineFramer(
    private val maxFrameBytes: Int = HOST_KEYBOARD_MAX_FRAME_BYTES,
) {
    private val buffer = ByteArrayOutputStream()

    fun accept(chunk: ByteArray): List<String> {
        if (chunk.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        for (byte in chunk) {
            when (byte) {
                '\n'.code.toByte() -> {
                    val raw = buffer.toByteArray()
                    buffer.reset()
                    val end = if (raw.lastOrNull() == '\r'.code.toByte()) raw.size - 1 else raw.size
                    if (end > 0) {
                        lines += raw.copyOf(end).toString(Charsets.UTF_8)
                    }
                }
                else -> {
                    if (buffer.size() >= maxFrameBytes) {
                        buffer.reset()
                        throw HostKeyboardProtocolException("host-keyboard frame exceeded $maxFrameBytes bytes")
                    }
                    buffer.write(byte.toInt())
                }
            }
        }
        return lines
    }

    fun reset() {
        buffer.reset()
    }
}
