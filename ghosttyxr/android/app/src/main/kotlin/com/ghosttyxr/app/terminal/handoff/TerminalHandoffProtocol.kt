package com.ghosttyxr.app.terminal.handoff

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream

/**
 * Loopback port the Mac companion's terminal-handoff listener binds on
 * `127.0.0.1`. The headset reaches it through an authenticated SSH
 * `direct-tcpip` tunnel — the same pattern as the host keyboard relay
 * (45873) and window-mirror signaling. Keep in lockstep with
 * `TerminalHandoffProtocol.port` in the Mac companion.
 */
const val TERMINAL_HANDOFF_RELAY_PORT = 45_875
internal const val TERMINAL_HANDOFF_PROTOCOL_VERSION = 1
internal const val TERMINAL_HANDOFF_MAX_FRAME_BYTES = 64 * 1024

class TerminalHandoffProtocolException(message: String) : IllegalArgumentException(message)

/**
 * A live host tmux session eligible for handoff: [name] is the
 * `gxr-<cockpitId>-<paneKey>` session name minted by
 * [com.ghosttyxr.app.terminal.session.HostSessionBootstrap.sessionName];
 * [title] is the pane's last known OSC title (cosmetic only — the Mac may
 * surface it, the attach command never uses it).
 */
data class HostSessionRef(
    val name: String,
    val title: String? = null,
)

/** One session the companion failed to verify or open, with its reason. */
data class HandoffFailure(
    val name: String,
    val error: String,
)

/**
 * Wire messages for the terminal handoff channel. Line-delimited UTF-8
 * JSON, versioned with `"v":1` — same framing discipline as
 * [com.ghosttyxr.app.terminal.hostkeyboard.HostKeyboardProtocol].
 *
 * headset → companion: [Hello], [OpenSessions]
 * companion → headset: [Ready], [OpenResult], [Error]
 */
sealed interface TerminalHandoffMessage {
    data class Hello(val role: String?) : TerminalHandoffMessage
    data object Ready : TerminalHandoffMessage
    data class OpenSessions(
        val reqId: String,
        val sessions: List<HostSessionRef>,
    ) : TerminalHandoffMessage

    data class OpenResult(
        val reqId: String?,
        val ok: Boolean,
        val opened: Int,
        val failed: List<HandoffFailure>,
    ) : TerminalHandoffMessage

    data class Error(val message: String?) : TerminalHandoffMessage
}

object TerminalHandoffProtocol {
    /** Same alphabet/length rule as HostSessionBootstrap's session names. */
    private val SESSION_NAME = Regex("[A-Za-z0-9_-]{1,64}")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun isValidSessionName(name: String): Boolean = name.matches(SESSION_NAME)

    fun parse(raw: String): TerminalHandoffMessage {
        val obj = json.parseToJsonElement(raw).jsonObject
        val version = obj.int("v") ?: throw TerminalHandoffProtocolException("missing protocol version")
        if (version != TERMINAL_HANDOFF_PROTOCOL_VERSION) {
            throw TerminalHandoffProtocolException("unsupported protocol version $version")
        }
        return when (val type = obj.string("type")) {
            "hello" -> TerminalHandoffMessage.Hello(role = obj.string("role"))
            "ready" -> TerminalHandoffMessage.Ready
            "open_sessions" -> TerminalHandoffMessage.OpenSessions(
                reqId = obj.string("req_id")
                    ?: throw TerminalHandoffProtocolException("missing open_sessions req_id"),
                sessions = parseSessions(obj),
            )
            "open_result" -> TerminalHandoffMessage.OpenResult(
                reqId = obj.string("req_id"),
                ok = obj.boolean("ok")
                    ?: throw TerminalHandoffProtocolException("missing open_result ok"),
                opened = obj.int("opened") ?: 0,
                failed = parseFailures(obj),
            )
            "error" -> TerminalHandoffMessage.Error(message = obj.string("message"))
            else -> throw TerminalHandoffProtocolException("unsupported message type $type")
        }
    }

    fun helloLine(): ByteArray = line("hello") {
        put("role", "headset")
        put("app", "ghosttyxr")
    }

    /**
     * The one request of a handoff turn: ask the companion to open a local
     * terminal window per session, attached via `tmux -L gxr`. Session names
     * are validated here so a malformed name can never reach the host shell.
     */
    fun openSessionsLine(reqId: String, sessions: List<HostSessionRef>): ByteArray {
        if (sessions.isEmpty()) throw TerminalHandoffProtocolException("no sessions to hand off")
        sessions.forEach { session ->
            if (!isValidSessionName(session.name)) {
                throw TerminalHandoffProtocolException("invalid host session name ${session.name}")
            }
        }
        return line("open_sessions") {
            put("req_id", reqId)
            put(
                "sessions",
                buildJsonArray {
                    sessions.forEach { session ->
                        add(
                            buildJsonObject {
                                put("name", session.name)
                                session.title?.let { put("title", it) }
                            },
                        )
                    }
                },
            )
        }
    }

    private fun parseSessions(obj: JsonObject): List<HostSessionRef> {
        val sessions = obj["sessions"]?.jsonArray
            ?: throw TerminalHandoffProtocolException("missing open_sessions sessions")
        if (sessions.isEmpty()) throw TerminalHandoffProtocolException("empty open_sessions sessions")
        return sessions.map { element ->
            val session = element.jsonObject
            val name = session.string("name")
                ?: throw TerminalHandoffProtocolException("missing session name")
            if (!isValidSessionName(name)) {
                throw TerminalHandoffProtocolException("invalid host session name $name")
            }
            HostSessionRef(name = name, title = session.string("title"))
        }
    }

    private fun parseFailures(obj: JsonObject): List<HandoffFailure> =
        obj["failed"]?.jsonArray.orEmpty().map { element ->
            val failure = element.jsonObject
            HandoffFailure(
                name = failure.string("name") ?: "unknown",
                error = failure.string("error") ?: "unknown error",
            )
        }

    private fun line(type: String, extra: JsonObjectBuilder.() -> Unit = {}): ByteArray {
        val obj = buildJsonObject {
            put("v", TERMINAL_HANDOFF_PROTOCOL_VERSION)
            put("type", type)
            extra()
        }
        return (json.encodeToString(JsonObject.serializer(), obj) + "\n").toByteArray(Charsets.UTF_8)
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.boolean(key: String): Boolean? =
        this[key]?.jsonPrimitive?.booleanOrNull
}

/**
 * Splits the tunnel byte stream into newline-delimited JSON lines. Same
 * shape as HostKeyboardLineFramer but with the handoff channel's larger
 * frame budget (session lists are bigger than key events).
 */
class TerminalHandoffLineFramer(
    private val maxFrameBytes: Int = TERMINAL_HANDOFF_MAX_FRAME_BYTES,
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
                        throw TerminalHandoffProtocolException(
                            "terminal-handoff frame exceeded $maxFrameBytes bytes",
                        )
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
