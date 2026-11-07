package com.ghosttyxr.app.terminal.voice.command

import android.util.Log
import com.ghosttyxr.app.terminal.handoff.HandoffResult
import com.ghosttyxr.app.terminal.session.AgentLaunch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val TAG = "VoiceCommand"

/**
 * Pure dispatcher + state machine for **voice command** mode. Mirrors the Android-/Firebase-free
 * discipline of [com.ghosttyxr.app.terminal.voice.VoiceInputController]: every cockpit dependency
 * is injected as a lambda, so the whole thing — index validation and the result JSON the model
 * reads back — is unit-tested with fakes and no emulator. The only Firebase/Deepgram contact is in
 * [LiveCommandSession], which streams audio through `gemini-3.8-live` and adapts
 * each `FunctionCallPart` → [handleCall] → `FunctionResponsePart`.
 *
 * Arguments arrive as kotlinx-serialization [JsonElement]s (that's the type Firebase hands us in
 * `FunctionCallPart.args`) and results go back as [JsonObject]s — both plain JVM types, no Firebase
 * classes, so the tests don't need the SDK on the classpath.
 *
 * Terminal numbering is **1-based** (see [CommandFunctions]); the controller converts it to an
 * internal zero-based pane ID. Commands run immediately with no confirmation gate — including
 * [CommandFunctions.CLOSE_TERMINAL], which is blocked only when a single terminal remains.
 */
class VoiceCommandController(
    private val paneCount: () -> Int,
    /**
     * Add a terminal; returns its new internal zero-based pane ID, or null at the terminal cap.
     * [launchCommand] is an optional host-side CLI line (no trailing CR) typed after connect.
     */
    private val addPane: (launchCommand: String?) -> Int?,
    private val focusPane: (paneId: Int) -> Unit,
    /** Remove the terminal at the internal zero-based pane ID. No-op when only one remains. */
    private val removePane: (paneId: Int) -> Unit = {},
    private val sendToPane: (paneId: Int, bytes: ByteArray) -> Unit,
    /** Read the last [lines] visible rows of a pane as text (oldest first); empty if unavailable. */
    private val readPane: (paneId: Int, lines: Int) -> List<String> = { _, _ -> emptyList() },
    /** Whether the device can control passthrough/environment (false on optical glasses). */
    private val immersiveAvailable: () -> Boolean = { false },
    private val immersiveMode: () -> Boolean = { false },
    private val setImmersiveMode: (Boolean) -> Unit = {},
    /**
     * Hand every live terminal off to the Mac companion as local terminal windows
     * (attach handoff — see [com.ghosttyxr.app.terminal.handoff.TerminalHandoffController]).
     * Suspends for the tunnel round-trip; the default stub keeps unwired builds honest.
     */
    private val sendTerminalsToMac: suspend () -> HandoffResult = {
        HandoffResult(0, "Sending terminals to the Mac isn't available right now.")
    },
) {
    private val _state = MutableStateFlow<VoiceCommandState>(VoiceCommandState.Idle)
    val state: StateFlow<VoiceCommandState> = _state.asStateFlow()

    /** True while a turn is in flight; gates [execute] so we never flash a label after teardown. */
    @Volatile
    private var active: Boolean = false

    // --- turn lifecycle hooks (called by LiveCommandSession via LiveCommandEngine) ---

    /** Recording the spoken command. */
    fun onListening() {
        updateState(VoiceCommandState.Listening, isActive = true)
    }

    /** Audio captured; transcription in flight. */
    fun onTranscribing() {
        updateState(VoiceCommandState.Transcribing, isActive = true)
    }

    /** Transcript sent to the model; waiting on the function call. */
    fun onThinking() {
        updateState(VoiceCommandState.Thinking, isActive = true)
    }

    /** The turn finished: show the model's short text reply, then the UI settles back to idle. */
    fun onResult(message: String) {
        updateState(VoiceCommandState.Result(message), isActive = false)
    }

    fun onError(message: String) {
        updateState(VoiceCommandState.Error(message), isActive = false)
    }

    /** Toggle off / lifecycle pause: drop all state back to idle. */
    fun reset() {
        updateState(VoiceCommandState.Idle, isActive = false)
    }

    /**
     * Run the function the model asked for and return the JSON result it will read back.
     * Never throws: a bad index or unknown name becomes a structured error result so the model
     * reports it to the user instead of the turn dying.
     */
    suspend fun handleCall(name: String, args: Map<String, JsonElement>): JsonObject = try {
        when (name) {
            CommandFunctions.NEW_TERMINAL -> handleNewTerminal(args)
            CommandFunctions.FOCUS_TERMINAL -> handleFocusTerminal(args)
            CommandFunctions.CLOSE_TERMINAL -> handleCloseTerminal(args)
            CommandFunctions.SEND_TO_TERMINAL -> handleSendToTerminal(args)
            CommandFunctions.READ_TERMINAL -> handleReadTerminal(args)
            CommandFunctions.TOGGLE_IMMERSIVE -> handleToggleImmersive(args)
            CommandFunctions.SEND_TERMINALS_TO_MAC -> handleSendTerminalsToMac()
            else -> error("Unknown function: $name")
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (err: Throwable) {
        Log.w(TAG, "command '$name' failed: ${err.message}")
        errorResult(err.message ?: "That command failed.")
    }

    private fun handleNewTerminal(args: Map<String, JsonElement>): JsonObject {
        val agentRaw = args[CommandFunctions.ARG_AGENT]?.jsonPrimitive?.contentOrNull?.trim()
        val launchCommand = if (agentRaw.isNullOrEmpty()) {
            null
        } else {
            AgentLaunch.command(agentRaw)
                ?: return errorResult(
                    "Unknown agent \"$agentRaw\". Use one of: ${AgentLaunch.NAMES.joinToString()}.",
                )
        }
        val newPaneId = addPane(launchCommand)
            ?: return errorResult("The cockpit is already at the maximum number of terminals.")
        val terminalNumber = newPaneId + 1
        val label = if (launchCommand != null) {
            "Opened $agentRaw terminal $terminalNumber"
        } else {
            "Opened terminal $terminalNumber"
        }
        execute(label)
        return okResult(
            if (launchCommand != null) {
                "Opened terminal $terminalNumber and launched $agentRaw."
            } else {
                "Opened terminal $terminalNumber."
            },
        )
    }

    private fun handleFocusTerminal(args: Map<String, JsonElement>): JsonObject {
        val terminalNumber = requireTerminalNumber(args)
        rangeError(terminalNumber)?.let { return it }
        focusPane(terminalNumber - 1)
        execute("Focused terminal $terminalNumber")
        return okResult("Focused terminal $terminalNumber.")
    }

    private fun handleCloseTerminal(args: Map<String, JsonElement>): JsonObject {
        val terminalNumber = requireTerminalNumber(args)
        rangeError(terminalNumber)?.let { return it }
        // The grid always keeps at least one terminal (see CockpitLifecycle.removePane).
        if (paneCount() <= 1) {
            return errorResult("Can't close the last terminal. The cockpit always keeps one open.")
        }
        removePane(terminalNumber - 1)
        execute("Closed terminal $terminalNumber")
        return okResult("Closed terminal $terminalNumber.")
    }

    private fun handleSendToTerminal(args: Map<String, JsonElement>): JsonObject {
        val terminalNumber = requireTerminalNumber(args)
        rangeError(terminalNumber)?.let { return it }
        val text = args[CommandFunctions.ARG_TEXT]?.jsonPrimitive?.contentOrNull?.trim()
        if (text.isNullOrEmpty()) {
            return errorResult("No instruction was provided to send.")
        }
        // Auto-submit: append a carriage return (\r, 0x0D) — the byte a real Enter keypress
        // produces (libghostty encodes GhosttyKey.enter as CR). Coding-agent TUIs treat \r as
        // "submit" and a bare \n as "insert newline", so \n typed the text but never ran it.
        sendToPane(terminalNumber - 1, (text + "\r").toByteArray(Charsets.UTF_8))
        execute("Directed agent in terminal $terminalNumber")
        return okResult("I have directed the agent in terminal $terminalNumber to $text.")
    }

    private fun handleReadTerminal(args: Map<String, JsonElement>): JsonObject {
        val terminalNumber = requireTerminalNumber(args)
        rangeError(terminalNumber)?.let { return it }
        val requested = args[CommandFunctions.ARG_LINES]?.jsonPrimitive?.intOrNull
            ?: CommandFunctions.DEFAULT_READ_LINES
        val lineCount = requested.coerceIn(1, CommandFunctions.MAX_READ_LINES)
        val lines = readPane(terminalNumber - 1, lineCount)
        execute("Read terminal $terminalNumber")
        return buildJsonObject {
            put("status", "ok")
            put(
                "message",
                if (lines.isEmpty()) {
                    "Terminal $terminalNumber has no visible output."
                } else {
                    "Read ${lines.size} line(s) from terminal $terminalNumber."
                },
            )
            put("lines", buildJsonArray { lines.forEach { add(it) } })
        }
    }

    private fun handleToggleImmersive(args: Map<String, JsonElement>): JsonObject {
        if (!immersiveAvailable()) {
            return errorResult("Immersive mode isn't available on this device.")
        }
        // Explicit `enabled` wins; omitted means "toggle whatever the current state is".
        val target = args[CommandFunctions.ARG_ENABLED]?.jsonPrimitive?.booleanOrNull
            ?: !immersiveMode()
        setImmersiveMode(target)
        val label = if (target) "on" else "off"
        execute("Immersive $label")
        return okResult("Immersive mode $label.")
    }

    /**
     * Send every live terminal to the Mac. The heavy lifting (tunnel, protocol,
     * repository reset) lives in the injected [sendTerminalsToMac] lambda; this
     * handler only translates its [HandoffResult] into the model's JSON result.
     * On failure the cockpit is unchanged, so the error is safe to just report.
     */
    private suspend fun handleSendTerminalsToMac(): JsonObject {
        execute("Sending terminals to Mac")
        val result = sendTerminalsToMac()
        val error = result.error
        if (error != null) return errorResult(error)
        val noun = if (result.opened == 1) "terminal" else "terminals"
        return okResult(
            "Sent ${result.opened} $noun to your Mac. They're closed here; the agents keep running.",
        )
    }

    // --- helpers ---

    /** Mark a command as just-run; the UI flashes [VoiceCommandState.Executing]. */
    private fun execute(label: String) {
        if (active) _state.value = VoiceCommandState.Executing(label)
    }

    private fun updateState(state: VoiceCommandState, isActive: Boolean) {
        active = isActive
        _state.value = state
    }

    private fun requireTerminalNumber(args: Map<String, JsonElement>): Int =
        args[CommandFunctions.ARG_INDEX]?.jsonPrimitive?.intOrNull
            ?: error("Missing or non-numeric terminal number.")

    /** Returns an error result if the one-based [terminalNumber] is out of range, else null. */
    private fun rangeError(terminalNumber: Int): JsonObject? {
        val count = paneCount()
        if (count <= 0) return errorResult("There are no terminals open.")
        if (terminalNumber !in 1..count) {
            return errorResult(
                "There is no terminal $terminalNumber. There are $count open (1 to $count).",
            )
        }
        return null
    }

    private fun okResult(message: String): JsonObject = result("ok", message)

    private fun errorResult(message: String): JsonObject = result("error", message)

    private fun result(status: String, message: String): JsonObject = buildJsonObject {
        put("status", status)
        put("message", message)
    }
}
