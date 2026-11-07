package com.ghosttyxr.app.terminal.session

/**
 * Maps spoken / function-call agent names to the host-side CLI invocation typed into a
 * freshly opened pane. Full-auto flags match the cockpit's "spawn and go" posture:
 * Claude and Codex skip permission prompts; Droid and Grok take no extra flags.
 */
object AgentLaunch {
    private val commands = linkedMapOf(
        "claude" to "claude --dangerously-skip-permissions",
        "codex" to "codex --yolo",
        "droid" to "droid",
        "grok" to "grok",
    )

    val NAMES: List<String> = commands.keys.toList()

    /**
     * Returns the shell command for [agent] (no trailing CR), or null when the name is
     * unknown. Matching is case-insensitive and ignores surrounding whitespace.
     */
    fun command(agent: String): String? = commands[agent.trim().lowercase()]
}
