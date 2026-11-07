package com.ghosttyxr.app.terminal.voice.command

import com.ghosttyxr.app.terminal.handoff.HandoffResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-dispatcher coverage for the voice command controller, with fakes (no emulator, no Firebase).
 * Args are kotlinx-serialization [JsonElement]s — the same type Firebase hands the controller in
 * `FunctionCallPart.args` — and results are parsed straight off the returned
 * [kotlinx.serialization.json.JsonObject]. Asserts the behaviour the plan specifies: index
 * validation, auto-submit newline, unknown-function handling, and the turn-lifecycle state hooks.
 *
 * close_terminal is covered directly (including the last-terminal guard); there is still no
 * confirmation gate — destructive close runs immediately when allowed.
 */
class VoiceCommandControllerTest {

    private class Sent(val paneId: Int, val bytes: ByteArray)

    private class Fakes(var paneCount: Int = 3) {
        val addedLaunchCommands = mutableListOf<String?>()
        val focused = mutableListOf<Int>()
        val removed = mutableListOf<Int>()
        val sent = mutableListOf<Sent>()
        var nextAddIndex: Int? = 3
        var immersiveAvailable = true
        var immersiveMode = false
        val immersiveSet = mutableListOf<Boolean>()
        val readRequests = mutableListOf<Pair<Int, Int>>()
        var paneLines: List<String> = listOf("last line")
        var handoffCalls = 0
        var handoffResult = HandoffResult(opened = 0, error = "not wired")
    }

    private fun controller(f: Fakes): VoiceCommandController =
        VoiceCommandController(
            paneCount = { f.paneCount },
            addPane = { launch -> f.addedLaunchCommands.add(launch); f.nextAddIndex },
            focusPane = { f.focused.add(it) },
            removePane = { f.removed.add(it); f.paneCount = (f.paneCount - 1).coerceAtLeast(0) },
            sendToPane = { id, bytes -> f.sent.add(Sent(id, bytes)) },
            readPane = { id, lines -> f.readRequests.add(id to lines); f.paneLines },
            immersiveAvailable = { f.immersiveAvailable },
            immersiveMode = { f.immersiveMode },
            setImmersiveMode = { f.immersiveMode = it; f.immersiveSet.add(it) },
            sendTerminalsToMac = { f.handoffCalls++; f.handoffResult },
        )

    /** Synchronous shim over the suspend dispatcher so each test stays a plain assertion body. */
    private fun VoiceCommandController.call(
        name: String,
        args: Map<String, JsonElement> = emptyMap(),
    ): JsonObject = runBlocking { handleCall(name, args) }

    private fun args(vararg pairs: Pair<String, JsonElement>): Map<String, JsonElement> = mapOf(*pairs)

    private fun status(result: kotlinx.serialization.json.JsonObject): String =
        (result["status"] as JsonPrimitive).content

    @Test
    fun `new_terminal adds a terminal and reports its one-based number`() {
        val f = Fakes()
        val c = controller(f)
        val result = c.call(CommandFunctions.NEW_TERMINAL, emptyMap())
        assertEquals(listOf(null), f.addedLaunchCommands)
        assertEquals("ok", status(result))
        assertEquals("Opened terminal 4.", (result["message"] as JsonPrimitive).content)
    }

    @Test
    fun `new_terminal with agent launches the mapped CLI command`() {
        val f = Fakes()
        val c = controller(f)
        val result = c.call(
            CommandFunctions.NEW_TERMINAL,
            args(CommandFunctions.ARG_AGENT to JsonPrimitive("claude")),
        )
        assertEquals("ok", status(result))
        assertEquals(
            listOf("claude --dangerously-skip-permissions"),
            f.addedLaunchCommands,
        )
        assertTrue((result["message"] as JsonPrimitive).content.contains("claude"))
    }

    @Test
    fun `new_terminal with codex launches yolo mode`() {
        val f = Fakes()
        val c = controller(f)
        c.call(
            CommandFunctions.NEW_TERMINAL,
            args(CommandFunctions.ARG_AGENT to JsonPrimitive("codex")),
        )
        assertEquals(listOf("codex --yolo"), f.addedLaunchCommands)
    }

    @Test
    fun `new_terminal with droid or grok launches bare commands`() {
        val f = Fakes()
        val c = controller(f)
        c.call(
            CommandFunctions.NEW_TERMINAL,
            args(CommandFunctions.ARG_AGENT to JsonPrimitive("droid")),
        )
        c.call(
            CommandFunctions.NEW_TERMINAL,
            args(CommandFunctions.ARG_AGENT to JsonPrimitive("Grok")),
        )
        assertEquals(listOf("droid", "grok"), f.addedLaunchCommands)
    }

    @Test
    fun `new_terminal rejects unknown agent names`() {
        val f = Fakes()
        val c = controller(f)
        val result = c.call(
            CommandFunctions.NEW_TERMINAL,
            args(CommandFunctions.ARG_AGENT to JsonPrimitive("aider")),
        )
        assertEquals("error", status(result))
        assertTrue(f.addedLaunchCommands.isEmpty())
    }

    @Test
    fun `new_terminal at max reports an error and does not add`() {
        val f = Fakes().apply { nextAddIndex = null }
        val c = controller(f)
        val result = c.call(CommandFunctions.NEW_TERMINAL, emptyMap())
        assertEquals("error", status(result))
        // addPane was still invoked (it's the source of truth for "at max"), but no index returned.
        assertEquals(1, f.addedLaunchCommands.size)
    }

    @Test
    fun `focus_terminal translates the requested terminal number to its internal pane id`() {
        val f = Fakes(paneCount = 4)
        val c = controller(f)
        val result = c.call(CommandFunctions.FOCUS_TERMINAL, args(CommandFunctions.ARG_INDEX to JsonPrimitive(2)))
        assertEquals("ok", status(result))
        assertEquals(listOf(1), f.focused)
    }

    @Test
    fun `focus_terminal out of range errors and does not focus`() {
        val f = Fakes(paneCount = 3)
        val c = controller(f)
        val result = c.call(CommandFunctions.FOCUS_TERMINAL, args(CommandFunctions.ARG_INDEX to JsonPrimitive(7)))
        assertEquals("error", status(result))
        assertTrue(f.focused.isEmpty())
    }

    @Test
    fun `focus_terminal rejects terminal zero`() {
        val f = Fakes(paneCount = 3)
        val c = controller(f)
        val result = c.call(CommandFunctions.FOCUS_TERMINAL, args(CommandFunctions.ARG_INDEX to JsonPrimitive(0)))
        assertEquals("error", status(result))
        assertEquals("There is no terminal 0. There are 3 open (1 to 3).", (result["message"] as JsonPrimitive).content)
        assertTrue(f.focused.isEmpty())
    }

    @Test
    fun `close_terminal removes the requested terminal by zero-based pane id`() {
        val f = Fakes(paneCount = 3)
        val c = controller(f)
        val result = c.call(
            CommandFunctions.CLOSE_TERMINAL,
            args(CommandFunctions.ARG_INDEX to JsonPrimitive(2)),
        )
        assertEquals("ok", status(result))
        assertEquals(listOf(1), f.removed)
        assertEquals("Closed terminal 2.", (result["message"] as JsonPrimitive).content)
    }

    @Test
    fun `close_terminal out of range errors and does not remove`() {
        val f = Fakes(paneCount = 3)
        val c = controller(f)
        val result = c.call(
            CommandFunctions.CLOSE_TERMINAL,
            args(CommandFunctions.ARG_INDEX to JsonPrimitive(9)),
        )
        assertEquals("error", status(result))
        assertTrue(f.removed.isEmpty())
    }

    @Test
    fun `close_terminal refuses to close the last remaining terminal`() {
        val f = Fakes(paneCount = 1)
        val c = controller(f)
        val result = c.call(
            CommandFunctions.CLOSE_TERMINAL,
            args(CommandFunctions.ARG_INDEX to JsonPrimitive(1)),
        )
        assertEquals("error", status(result))
        assertTrue(f.removed.isEmpty())
        assertTrue((result["message"] as JsonPrimitive).content.contains("last terminal"))
    }

    @Test
    fun `send_to_terminal types the instruction and auto-submits with a newline`() {
        val f = Fakes(paneCount = 4)
        val c = controller(f)
        val result = c.call(
            CommandFunctions.SEND_TO_TERMINAL,
            args(
                CommandFunctions.ARG_INDEX to JsonPrimitive(3),
                CommandFunctions.ARG_TEXT to JsonPrimitive("refactor auth"),
            ),
        )
        assertEquals("ok", status(result))
        assertEquals(1, f.sent.size)
        assertEquals(2, f.sent[0].paneId)
        assertEquals("refactor auth\r", String(f.sent[0].bytes, Charsets.UTF_8))
        assertEquals(
            "I have directed the agent in terminal 3 to refactor auth.",
            (result["message"] as JsonPrimitive).content,
        )
    }

    @Test
    fun `send_to_terminal with blank text errors and sends nothing`() {
        val f = Fakes(paneCount = 4)
        val c = controller(f)
        val result = c.call(
            CommandFunctions.SEND_TO_TERMINAL,
            args(
                CommandFunctions.ARG_INDEX to JsonPrimitive(1),
                CommandFunctions.ARG_TEXT to JsonPrimitive("   "),
            ),
        )
        assertEquals("error", status(result))
        assertTrue(f.sent.isEmpty())
    }

    @Test
    fun `send_to_terminal out of range errors and sends nothing`() {
        val f = Fakes(paneCount = 2)
        val c = controller(f)
        val result = c.call(
            CommandFunctions.SEND_TO_TERMINAL,
            args(
                CommandFunctions.ARG_INDEX to JsonPrimitive(5),
                CommandFunctions.ARG_TEXT to JsonPrimitive("ls"),
            ),
        )
        assertEquals("error", status(result))
        assertTrue(f.sent.isEmpty())
    }

    @Test
    fun `read_terminal returns the pane lines and defaults the line count`() {
        val f = Fakes(paneCount = 3).apply { paneLines = listOf("build ok", "$ ") }
        val c = controller(f)
        val result = c.call(
            CommandFunctions.READ_TERMINAL,
            args(CommandFunctions.ARG_INDEX to JsonPrimitive(1)),
        )
        assertEquals("ok", status(result))
        assertEquals(listOf(0 to CommandFunctions.DEFAULT_READ_LINES), f.readRequests)
        val lines = (result["lines"] as kotlinx.serialization.json.JsonArray)
            .map { (it as JsonPrimitive).content }
        assertEquals(listOf("build ok", "$ "), lines)
    }

    @Test
    fun `read_terminal clamps the requested line count to the max`() {
        val f = Fakes(paneCount = 3)
        val c = controller(f)
        c.call(
            CommandFunctions.READ_TERMINAL,
            args(
                CommandFunctions.ARG_INDEX to JsonPrimitive(1),
                CommandFunctions.ARG_LINES to JsonPrimitive(10_000),
            ),
        )
        assertEquals(listOf(0 to CommandFunctions.MAX_READ_LINES), f.readRequests)
    }

    @Test
    fun `read_terminal out of range errors and does not read`() {
        val f = Fakes(paneCount = 2)
        val c = controller(f)
        val result = c.call(
            CommandFunctions.READ_TERMINAL,
            args(CommandFunctions.ARG_INDEX to JsonPrimitive(9)),
        )
        assertEquals("error", status(result))
        assertTrue(f.readRequests.isEmpty())
    }

    @Test
    fun `toggle_immersive with no argument flips the current state`() {
        val f = Fakes().apply { immersiveMode = false }
        val c = controller(f)
        val on = c.call(CommandFunctions.TOGGLE_IMMERSIVE, emptyMap())
        assertEquals("ok", status(on))
        assertEquals(listOf(true), f.immersiveSet)
        // Flips back on a second toggle.
        val off = c.call(CommandFunctions.TOGGLE_IMMERSIVE, emptyMap())
        assertEquals("ok", status(off))
        assertEquals(listOf(true, false), f.immersiveSet)
    }

    @Test
    fun `toggle_immersive honors an explicit enabled argument`() {
        val f = Fakes().apply { immersiveMode = true }
        val c = controller(f)
        // Already on; explicit enabled=true keeps it on rather than toggling off.
        val result = c.call(
            CommandFunctions.TOGGLE_IMMERSIVE,
            args(CommandFunctions.ARG_ENABLED to JsonPrimitive(true)),
        )
        assertEquals("ok", status(result))
        assertEquals(listOf(true), f.immersiveSet)
    }

    @Test
    fun `toggle_immersive errors and changes nothing when unavailable`() {
        val f = Fakes().apply { immersiveAvailable = false }
        val c = controller(f)
        val result = c.call(CommandFunctions.TOGGLE_IMMERSIVE, emptyMap())
        assertEquals("error", status(result))
        assertTrue(f.immersiveSet.isEmpty())
    }

    @Test
    fun `unknown function returns a structured error and does not crash`() {
        val f = Fakes()
        val c = controller(f)
        val result = c.call("teleport", emptyMap())
        assertEquals("error", status(result))
    }

    @Test
    fun `missing index argument errors`() {
        val f = Fakes(paneCount = 3)
        val c = controller(f)
        val result = c.call(CommandFunctions.FOCUS_TERMINAL, emptyMap())
        assertEquals("error", status(result))
        assertTrue(f.focused.isEmpty())
    }

    @Test
    fun `message field is always present for surfaced results`() {
        val f = Fakes(paneCount = 3)
        val c = controller(f)
        val ok = c.call(CommandFunctions.FOCUS_TERMINAL, args(CommandFunctions.ARG_INDEX to JsonPrimitive(1)))
        assertTrue(ok.containsKey("message"))
        val err = c.call(CommandFunctions.FOCUS_TERMINAL, args(CommandFunctions.ARG_INDEX to JsonPrimitive(99)))
        assertTrue(err.containsKey("message"))
    }

    @Test
    fun `executing flash only shows while a turn is active`() {
        val f = Fakes(paneCount = 3)
        val c = controller(f)
        // No active turn yet: a successful command does not flash Executing.
        c.call(CommandFunctions.FOCUS_TERMINAL, args(CommandFunctions.ARG_INDEX to JsonPrimitive(1)))
        assertEquals(VoiceCommandState.Idle, c.state.value)
        // While a turn is active (model thinking), the command flashes Executing.
        c.onThinking()
        c.call(CommandFunctions.FOCUS_TERMINAL, args(CommandFunctions.ARG_INDEX to JsonPrimitive(2)))
        assertTrue(c.state.value is VoiceCommandState.Executing)
    }

    @Test
    fun `turn lifecycle hooks drive the expected states`() {
        val f = Fakes(paneCount = 3)
        val c = controller(f)
        c.onListening()
        assertEquals(VoiceCommandState.Listening, c.state.value)
        c.onTranscribing()
        assertEquals(VoiceCommandState.Transcribing, c.state.value)
        c.onThinking()
        assertEquals(VoiceCommandState.Thinking, c.state.value)
        c.onResult("Opened a terminal.")
        assertEquals(VoiceCommandState.Result("Opened a terminal."), c.state.value)
    }

    @Test
    fun `reset returns to idle`() {
        val f = Fakes(paneCount = 3)
        val c = controller(f)
        c.onListening()
        c.reset()
        assertEquals(VoiceCommandState.Idle, c.state.value)
    }

    @Test
    fun `onError surfaces a user-facing message`() {
        val f = Fakes(paneCount = 3)
        val c = controller(f)
        c.onError("Network error")
        assertEquals(VoiceCommandState.Error("Network error"), c.state.value)
    }

    @Test
    fun `send_terminals_to_mac reports the opened count on success`() {
        val f = Fakes(paneCount = 3).apply { handoffResult = HandoffResult(opened = 3) }
        val c = controller(f)
        val result = c.call(CommandFunctions.SEND_TERMINALS_TO_MAC)
        assertEquals("ok", status(result))
        assertEquals(1, f.handoffCalls)
        assertEquals(
            "Sent 3 terminals to your Mac. They're closed here; the agents keep running.",
            (result["message"] as JsonPrimitive).content,
        )
    }

    @Test
    fun `send_terminals_to_mac uses the singular noun for one terminal`() {
        val f = Fakes(paneCount = 1).apply { handoffResult = HandoffResult(opened = 1) }
        val c = controller(f)
        val result = c.call(CommandFunctions.SEND_TERMINALS_TO_MAC)
        assertEquals("ok", status(result))
        assertTrue((result["message"] as JsonPrimitive).content.startsWith("Sent 1 terminal to your Mac."))
    }

    @Test
    fun `send_terminals_to_mac surfaces the handoff error verbatim`() {
        val f = Fakes(paneCount = 3).apply {
            handoffResult = HandoffResult(opened = 0, error = "Couldn't reach the Mac companion.")
        }
        val c = controller(f)
        val result = c.call(CommandFunctions.SEND_TERMINALS_TO_MAC)
        assertEquals("error", status(result))
        assertEquals(1, f.handoffCalls)
        assertEquals(
            "Couldn't reach the Mac companion.",
            (result["message"] as JsonPrimitive).content,
        )
    }

    @Test
    fun `send_terminals_to_mac turns a thrown handoff failure into an error result`() {
        val f = Fakes(paneCount = 3)
        val c = VoiceCommandController(
            paneCount = { f.paneCount },
            addPane = { null },
            focusPane = {},
            sendToPane = { _, _ -> },
            sendTerminalsToMac = { error("tunnel exploded") },
        )
        val result = c.call(CommandFunctions.SEND_TERMINALS_TO_MAC)
        assertEquals("error", status(result))
        assertEquals("tunnel exploded", (result["message"] as JsonPrimitive).content)
    }
}
