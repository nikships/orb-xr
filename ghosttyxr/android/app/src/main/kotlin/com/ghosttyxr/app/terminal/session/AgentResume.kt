package com.ghosttyxr.app.terminal.session

data class AgentResume(
    val kind: Kind,
    val sessionId: String,
) {
    init {
        require(SESSION_ID.matches(sessionId)) { "invalid agent session id" }
    }

    enum class Kind { Claude, Codex, Droid, Grok }

    fun command(): String = when (kind) {
        Kind.Claude -> "claude --resume $sessionId"
        Kind.Codex -> "codex resume $sessionId"
        Kind.Droid -> "droid --resume $sessionId"
        Kind.Grok -> "grok --resume $sessionId"
    }

    companion object {
        private val SESSION_ID = Regex("[A-Za-z0-9._-]{1,128}")
    }
}
