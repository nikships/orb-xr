package com.ghosttyxr.app.terminal.voice.command

import com.ghosttyxr.app.terminal.session.AgentLaunch
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.Schema

/**
 * The cockpit functions exposed to `gemini-3.8-live` for voice control.
 *
 * The name [String]s are `const val` so [VoiceCommandController] (which dispatches on them) and
 * its unit tests can reference them without loading any Firebase types — only [declarations]
 * constructs the Firebase [FunctionDeclaration]/[Schema] objects, and that's only touched by the
 * Firebase-facing [LiveCommandSession].
 *
 * **Terminal numbering is 1-based** everywhere exposed to Gemini ("terminal one" is the first
 * terminal). The controller translates each `index` to the internal zero-based `paneId` after
 * validating it against the live terminal count.
 *
 * [CLOSE_TERMINAL] is the only destructive command; it still runs immediately (no confirmation
 * handshake) because voice turns are short and the last remaining terminal cannot be closed.
 */
object CommandFunctions {
    const val NEW_TERMINAL = "new_terminal"
    const val FOCUS_TERMINAL = "focus_terminal"
    const val CLOSE_TERMINAL = "close_terminal"
    const val SEND_TO_TERMINAL = "send_to_terminal"
    const val READ_TERMINAL = "read_terminal"
    const val TOGGLE_IMMERSIVE = "toggle_immersive"
    const val SEND_TERMINALS_TO_MAC = "send_terminals_to_mac"

    const val ARG_INDEX = "terminal_number"
    const val ARG_TEXT = "text"
    const val ARG_LINES = "lines"
    const val ARG_ENABLED = "enabled"
    /** Optional coding-agent name for [NEW_TERMINAL] — see [com.ghosttyxr.app.terminal.session.AgentLaunch]. */
    const val ARG_AGENT = "agent"

    /** Default number of trailing lines [READ_TERMINAL] returns when the model omits `lines`. */
    const val DEFAULT_READ_LINES = 20

    /** Upper bound on [READ_TERMINAL]'s `lines` so one read can't dump a huge screen into context. */
    const val MAX_READ_LINES = 100

    /** Built lazily so merely referencing the name constants never constructs Firebase objects. */
    val declarations: List<FunctionDeclaration> by lazy {
        listOf(
            FunctionDeclaration(
                name = NEW_TERMINAL,
                description = "Open a new terminal in the cockpit. Use when the user asks to " +
                    "add, open, spawn, or create a terminal. Optionally launch a coding agent " +
                    "in the new terminal when the user names one (e.g. \"spawn a claude " +
                    "terminal\", \"open codex\", \"new droid pane\", \"spawn grok\").",
                parameters = mapOf(
                    ARG_AGENT to Schema.enumeration(
                        AgentLaunch.NAMES,
                        description = "Coding agent to launch after the terminal opens. " +
                            "Omit for a plain shell. One of: claude, codex, droid, grok.",
                    ),
                ),
                optionalParameters = listOf(ARG_AGENT),
            ),
            FunctionDeclaration(
                name = FOCUS_TERMINAL,
                description = "Move focus to an existing terminal so it receives keyboard and " +
                    "dictation input. Use when the user says focus/switch/go to a terminal.",
                parameters = mapOf(
                    ARG_INDEX to Schema.integer(
                        description = "One-based number of the terminal to focus. The first " +
                            "terminal is 1.",
                    ),
                ),
            ),
            FunctionDeclaration(
                name = CLOSE_TERMINAL,
                description = "Close and remove an existing terminal from the cockpit. Use when " +
                    "the user asks to close, delete, remove, or dismiss a terminal. Fails if only " +
                    "one terminal remains — the cockpit always keeps at least one.",
                parameters = mapOf(
                    ARG_INDEX to Schema.integer(
                        description = "One-based number of the terminal to close. The first " +
                            "terminal is 1.",
                    ),
                ),
            ),
            FunctionDeclaration(
                name = SEND_TO_TERMINAL,
                description = "Type an instruction into a terminal's running program (e.g. a coding " +
                    "agent) and submit it by pressing Enter. Use for \"tell terminal three to " +
                            "refactor auth\" — the text is sent verbatim to that terminal and run.",
                parameters = mapOf(
                    ARG_INDEX to Schema.integer(
                        description = "One-based number of the target terminal. The first " +
                            "terminal is 1.",
                    ),
                    ARG_TEXT to Schema.string(
                        description = "The instruction to type into the terminal, exactly as it " +
                            "should be sent (e.g. \"refactor auth\"). Do not add quotes.",
                    ),
                ),
            ),
            FunctionDeclaration(
                name = READ_TERMINAL,
                description = "Read the most recent visible output from a terminal so you can " +
                    "see what a coding agent said or what state a shell is in. Use before deciding " +
                    "what to send next (e.g. read terminal 1, then send a follow-up). " +
                    "Non-destructive: it only observes the terminal. Returns the last N non-empty " +
                    "lines as text.",
                parameters = mapOf(
                    ARG_INDEX to Schema.integer(
                        description = "One-based number of the terminal to read. The first " +
                            "terminal is 1.",
                    ),
                    ARG_LINES to Schema.integer(
                        description = "How many trailing lines to read (default " +
                            "$DEFAULT_READ_LINES, capped at $MAX_READ_LINES). Omit for the default.",
                    ),
                ),
                optionalParameters = listOf(ARG_LINES),
            ),
            FunctionDeclaration(
                name = TOGGLE_IMMERSIVE,
                description = "Turn immersive (focus) mode on or off. Immersive mode replaces the " +
                    "passthrough view of the room with a dark environment so the terminals are " +
                    "all the user sees. Pass `enabled` only when the user explicitly says on/off; " +
                    "omit it to toggle the current state.",
                parameters = mapOf(
                    ARG_ENABLED to Schema.boolean(
                        description = "Desired immersive state. Omit to toggle whatever the " +
                            "current state is.",
                    ),
                ),
                optionalParameters = listOf(ARG_ENABLED),
            ),
            FunctionDeclaration(
                name = SEND_TERMINALS_TO_MAC,
                description = "Send every live terminal to the user's Mac: one local terminal " +
                    "window opens on the Mac per XR pane, attached to the same host session, " +
                    "then the XR terminals close. Nothing is killed — coding agents keep " +
                    "running on the Mac. Use when the user asks to send, move, or hand off " +
                    "the terminals to their Mac or desktop. Takes no arguments and affects " +
                    "all terminals at once.",
                parameters = emptyMap(),
            ),
        )
    }

    /** The natural-language brief the model runs under (cockpit description + rules). */
    val systemInstruction: String =
        """
        You are Orb, the voice controller for Orb XR, an XR cockpit of floating terminal panes,
        each running a CLI coding agent or shell on the user's Mac. You operate the cockpit by
        calling the provided functions — you do not type into terminals yourself except via
        send_to_terminal. The user's spoken request has already been transcribed to text.

        Rules:
        - Terminals are numbered starting at ONE. "The first terminal" / "terminal one" is
          index 1. If the user says "terminal three", that is index 3.
        - After acting, give the user a useful summary of what happened. Keep it under 180
          characters and do not ask follow-up questions. The reply is shown on screen and spoken
          aloud.
        - For new_terminal, pass agent when the user asks to spawn/open a coding agent terminal
          (claude, codex, droid, or grok). Omit agent for a plain shell. Do not invent other
          agent names.
        - For send_to_terminal, pass the user's instruction verbatim; it is typed into that pane's
          agent and submitted.
        - You can call read_terminal to inspect a terminal's recent output before acting — e.g. read
          terminal 1, then decide what to send it. Reading is non-destructive. You may chain calls:
          read a terminal, then send to it in the same turn.
        - Immersive (focus) mode is controlled via toggle_immersive. "Toggle immersive", "focus
          mode on", "turn off immersive" and similar all map to it; pass `enabled` only when the
          user explicitly says on or off.
        - If a function result is an error (e.g. no such terminal), tell the user briefly what
          went wrong instead of guessing.
        - Use close_terminal when the user asks to close, delete, remove, or dismiss a terminal.
          The last remaining terminal cannot be closed; if that fails, say so briefly.
        - Use send_terminals_to_mac when the user asks to send, move, or hand off the terminals
          to their Mac or desktop — e.g. "send terminals to mac", "hand off to mac", "open these
          on my mac", "move terminals to desktop". It takes no arguments, applies to every live
          terminal at once, and the agents keep running on the Mac; nothing is killed.
        """.trimIndent()
}
