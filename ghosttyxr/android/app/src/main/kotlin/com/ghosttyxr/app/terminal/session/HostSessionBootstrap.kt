package com.ghosttyxr.app.terminal.session

/**
 * Builds the command submitted immediately after a pane opens. A named tmux
 * session keeps the host-side process and its scrollback alive when the headset
 * closes its SSH connection.
 *
 * gxr sessions live on a dedicated tmux server (`-L gxr`) started with an empty
 * config (`-f /dev/null`) so we can set server options safely without rewriting
 * the user's default tmux server or inheriting their plugins/hooks.
 *
 * Server options and the first pane must be created in a **single** tmux client
 * invocation: bare `start-server` exits immediately when exit-empty is on, so
 * any follow-up `set-option` would hit "no server running".
 *
 * Scroll is intentionally local (libghostty), not host copy-mode: mouse stays
 * off, smcup/rmcup are disabled for the outer xterm*, and we do not install
 * wheel→copy-mode bindings that paint yellow mode-style chrome into the pane.
 */
object HostSessionBootstrap {
    /** Shared with [AgentSessionProbe] — every host-side tmux call must use this. */
    const val TMUX_SOCKET = "gxr"

    fun command(sessionName: String, workingDirectory: String? = null): String {
        require(sessionName.matches(SESSION_NAME)) { "invalid host session name" }
        val t = tmux()
        val cwd = WorkingDirectory.normalize(workingDirectory)
        return quietShellPrefix() +
            "if [ -n \"\$TMUX\" ]; then :; elif command -v tmux >/dev/null 2>&1; then\r" +
            "export COLORTERM=truecolor; ${ensureServerAndSession(sessionName, cwd)}\r" +
            "${configureSession(sessionName)}\r" +
            "${hydrateLocalHistory(sessionName)}\r" +
            "exec $t attach-session -t $sessionName\r" +
            "else printf '\\n[Orb XR] tmux is unavailable; this pane cannot survive headset disconnects.\\n'; fi\r"
    }

    fun restoreCommand(sessionName: String, agentResume: AgentResume?): String {
        require(sessionName.matches(SESSION_NAME)) { "invalid host session name" }
        val t = tmux()
        val fallback = agentResume?.let { "exec ${it.command()}" }
            ?: "printf '\\n[Orb XR] Saved host session exited while you were away.\\n'"
        return quietShellPrefix() +
            "if command -v tmux >/dev/null 2>&1 && $t has-session -t $sessionName 2>/dev/null; then\r" +
            "export COLORTERM=truecolor\r" +
            // Re-apply server opts (session already exists; create step is a no-op fail).
            "${ensureServerAndSession(sessionName)}\r" +
            "${configureSession(sessionName)}\r" +
            "${hydrateLocalHistory(sessionName)}\r" +
            "exec $t attach-session -t $sessionName\r" +
            "else $fallback; fi\r"
    }

    fun sessionName(cockpitId: String, paneKey: Long): String {
        val normalizedId = cockpitId.filter { it.isLetterOrDigit() }.take(24)
        return "gxr-${normalizedId.ifEmpty { "session" }}-$paneKey"
    }

    /** Prefix for every client invocation against the dedicated gxr server. */
    internal fun tmux(): String = "tmux -L $TMUX_SOCKET"

    /**
     * Hide bootstrap typing and clear the outer PTY so setup lines
     * (`mouse off`, bind-key noise, etc.) never linger in local scrollback.
     */
    internal fun quietShellPrefix(): String =
        "stty -echo 2>/dev/null || true; printf '\\033[2J\\033[H'\r"

    /**
     * One tmux client: start server (empty conf) → set globals → create session
     * if missing. Chained with `\;` so the server stays alive for the whole
     * sequence (exit-empty would kill a bare start-server).
     *
     * History, chrome suppression, and terminal capabilities are set *before*
     * new-session so the initial pane keeps durable host history without
     * forcing the headset into tmux's alternate screen or yellow status/mode
     * chrome.
     *
     * @param workingDirectory optional absolute host path; when set, a *new* session
     * is created with `tmux new-session -c`. Ignored when the session already exists
     * (`new-session` fails and is swallowed by `|| true`).
     */
    internal fun ensureServerAndSession(
        sessionName: String,
        workingDirectory: String? = null,
    ): String {
        val t = tmux()
        val cwdFlag = WorkingDirectory.normalize(workingDirectory)
            ?.let { " -c ${WorkingDirectory.shellQuote(it)}" }
            .orEmpty()
        // Shell: tmux ... start-server \; set ... \; new-session ... || true
        // allow-passthrough is outside the chain (soft-fail: needs tmux >= 3.3)
        // so a missing option cannot abort session creation.
        return "tmux -L $TMUX_SOCKET -f /dev/null start-server" +
            " \\; set-option -g history-limit ${ScrollbackPolicy.HOST_HISTORY_LINES}" +
            " \\; set-option -g mouse off" +
            " \\; set-option -g status off" +
            " \\; set-option -g pane-border-status off" +
            " \\; set-option -g visual-activity off" +
            " \\; set-option -g visual-bell off" +
            " \\; set-option -g visual-silence off" +
            " \\; set-option -g display-time 1" +
            " \\; set-option -g display-panes-time 1" +
            // Neutralize default yellow mode/message styles if copy-mode ever opens.
            " \\; set-option -g mode-style 'fg=default,bg=default'" +
            " \\; set-option -g message-style 'fg=default,bg=default'" +
            " \\; set-option -g message-command-style 'fg=default,bg=default'" +
            " \\; set-option -s escape-time 10" +
            " \\; set-option -s focus-events on" +
            " \\; set-option -s extended-keys on" +
            " \\; set-option -s extended-keys-format csi-u" +
            // Replace (not -a append) so reattach does not grow the array.
            " \\; set-option -g terminal-features 'xterm*:RGB:extkeys'" +
            " \\; set-option -g terminal-overrides 'xterm*:smcup@:rmcup@'" +
            " \\; new-session -d -s $sessionName$cwdFlag" +
            " || true; " +
            "$t set-option -g allow-passthrough on 2>/dev/null || true"
    }

    /**
     * Per-session options applied on create and reattach.
     *
     * - status off: hide tmux's status bar (headset PaneStatusBar is the chrome)
     * - set-titles: forward #T so libghostty's OSC title poll can label panes
     */
    internal fun configureSession(sessionName: String): String {
        val t = tmux()
        // Drop wheel→copy-mode bindings left by older bootstraps so reconnect
        // cannot re-enter yellow host copy-mode chrome.
        return "$t set-option -t $sessionName status off 2>/dev/null || true; " +
            "$t set-option -t $sessionName set-titles on 2>/dev/null || true; " +
            "$t set-option -t $sessionName set-titles-string '#T' 2>/dev/null || true; " +
            "$t unbind-key -T root WheelUpPane 2>/dev/null || true; " +
            "$t unbind-key -T root WheelDownPane 2>/dev/null || true; " +
            "$t unbind-key -T copy-mode WheelUpPane 2>/dev/null || true; " +
            "$t unbind-key -T copy-mode WheelDownPane 2>/dev/null || true; " +
            "$t unbind-key -T copy-mode-vi WheelUpPane 2>/dev/null || true; " +
            "$t unbind-key -T copy-mode-vi WheelDownPane 2>/dev/null || true"
    }

    /**
     * Seed libghostty's local scrollback from host history, then clear the
     * outer viewport again so attach starts from a clean live surface.
     */
    internal fun hydrateLocalHistory(sessionName: String): String {
        require(sessionName.matches(SESSION_NAME)) { "invalid host session name" }
        val t = tmux()
        return "printf '\\033[2J\\033[H'; " +
            "$t capture-pane -e -p -S -${ScrollbackPolicy.LOCAL_HISTORY_LINES} " +
            "-E -1 -t $sessionName 2>/dev/null || true; " +
            "printf '\\033[2J\\033[H'"
    }

    private val SESSION_NAME = Regex("[A-Za-z0-9_-]{1,64}")
}
