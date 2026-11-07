package com.ghosttyxr.app.terminal.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostSessionBootstrapTest {

    @Test
    fun sessionNameIsStableAndShellSafe() {
        assertEquals("gxr-cockpit123-4", HostSessionBootstrap.sessionName("cockpit-123", 4L))
    }

    @Test
    fun freshCommandCreatesOrAttachesTmuxSession() {
        val command = HostSessionBootstrap.command("gxr-cockpit-1")
        val t = HostSessionBootstrap.tmux()

        assertTrue(command.startsWith(HostSessionBootstrap.quietShellPrefix()))
        assertTrue(command.contains("command -v tmux"))
        assertTrue(command.contains("export COLORTERM=truecolor"))
        assertTrue(command.contains(HostSessionBootstrap.ensureServerAndSession("gxr-cockpit-1")))
        assertFalse(command.contains("configureScrollRouting"))
        // Unbind legacy wheel keys is fine; do not reinstall copy-mode scroll routing.
        assertTrue(command.contains("unbind-key -T root WheelUpPane"))
        assertFalse(command.contains("copy-mode -e"))
        assertFalse(command.contains("send-keys -N 5 -X scroll-up"))
        assertFalse(command.contains("new-session -Ad"))
        assertSessionConfigured(command, "gxr-cockpit-1")
        assertHistoryHydratedBeforeAttach(command, "gxr-cockpit-1")
        assertTrue(command.contains("$t attach-session -t gxr-cockpit-1"))
        assertTrue(command.endsWith("\r"))
        assertPhysicalLinesArePtySafe(command)
    }

    @Test
    fun restoreCommandDoesNotCreateMissingSession() {
        val command = HostSessionBootstrap.restoreCommand("gxr-cockpit-1", null)
        val t = HostSessionBootstrap.tmux()

        assertTrue(command.startsWith(HostSessionBootstrap.quietShellPrefix()))
        assertTrue(command.contains("$t has-session -t gxr-cockpit-1"))
        assertTrue(command.contains(HostSessionBootstrap.ensureServerAndSession("gxr-cockpit-1")))
        assertTrue(command.contains("unbind-key -T root WheelUpPane"))
        assertFalse(command.contains("copy-mode -e"))
        assertSessionConfigured(command, "gxr-cockpit-1")
        assertHistoryHydratedBeforeAttach(command, "gxr-cockpit-1")
        assertTrue(command.contains("$t attach-session -t gxr-cockpit-1"))
        assertTrue(command.contains("exited while you were away"))
        assertPhysicalLinesArePtySafe(command)
    }

    @Test
    fun restoreCommandResumesKnownAgentWhenHostSessionExited() {
        val command = HostSessionBootstrap.restoreCommand(
            "gxr-cockpit-1",
            AgentResume(AgentResume.Kind.Droid, "session-1"),
        )

        assertTrue(command.contains("exec droid --resume session-1"))
    }

    @Test
    fun dedicatedSocketChainsServerOptionsBeforeSessionCreate() {
        val ensure = HostSessionBootstrap.ensureServerAndSession("gxr-cockpit-1")

        assertTrue(ensure.startsWith("tmux -L gxr -f /dev/null start-server"))
        assertTrue(ensure.contains("set-option -g history-limit ${ScrollbackPolicy.HOST_HISTORY_LINES}"))
        assertTrue(ensure.contains("set-option -g mouse off"))
        assertFalse(ensure.contains("set-option -g mouse on"))
        assertTrue(ensure.contains("set-option -g status off"))
        assertTrue(ensure.contains("set-option -g pane-border-status off"))
        assertTrue(ensure.contains("set-option -g visual-activity off"))
        assertTrue(ensure.contains("set-option -g mode-style 'fg=default,bg=default'"))
        assertTrue(ensure.contains("set-option -g message-style 'fg=default,bg=default'"))
        assertTrue(ensure.contains("set-option -s escape-time 10"))
        assertTrue(ensure.contains("set-option -s focus-events on"))
        assertTrue(ensure.contains("set-option -s extended-keys on"))
        assertTrue(ensure.contains("set-option -s extended-keys-format csi-u"))
        assertTrue(ensure.contains("set-option -g terminal-features 'xterm*:RGB:extkeys'"))
        assertTrue(ensure.contains("set-option -g terminal-overrides 'xterm*:smcup@:rmcup@'"))
        assertTrue(ensure.contains("new-session -d -s gxr-cockpit-1"))
        assertTrue(ensure.contains("set-option -g allow-passthrough on 2>/dev/null || true"))
        // Options must appear before new-session in the chain.
        assertTrue(
            ensure.indexOf("history-limit") < ensure.indexOf("new-session -d -s gxr-cockpit-1"),
        )
        assertTrue(
            ensure.indexOf("mouse off") < ensure.indexOf("new-session -d -s gxr-cockpit-1"),
        )
        assertTrue(
            ensure.indexOf("status off") < ensure.indexOf("new-session -d -s gxr-cockpit-1"),
        )
    }

    @Test
    fun ensureServerAndSessionPassesWorkingDirectoryToNewSession() {
        val ensure = HostSessionBootstrap.ensureServerAndSession(
            "gxr-cockpit-1",
            "/Users/me/project",
        )
        assertTrue(ensure.contains("new-session -d -s gxr-cockpit-1 -c '/Users/me/project'"))
    }

    @Test
    fun freshCommandForwardsWorkingDirectoryIntoEnsure() {
        val command = HostSessionBootstrap.command("gxr-cockpit-1", "/Users/me/proj")
        assertTrue(
            command.contains(
                HostSessionBootstrap.ensureServerAndSession("gxr-cockpit-1", "/Users/me/proj"),
            ),
        )
    }

    @Test
    fun configureSessionIsPerSessionOnly() {
        val configure = HostSessionBootstrap.configureSession("gxr-cockpit-1")
        val t = HostSessionBootstrap.tmux()

        assertTrue(configure.contains("$t set-option -t gxr-cockpit-1 status off"))
        assertTrue(configure.contains("$t set-option -t gxr-cockpit-1 set-titles on"))
        assertTrue(configure.contains("$t set-option -t gxr-cockpit-1 set-titles-string '#T'"))
        assertTrue(configure.contains("unbind-key -T root WheelUpPane"))
        assertTrue(configure.contains("unbind-key -T copy-mode-vi WheelDownPane"))
        assertFalse(configure.contains("history-limit"))
        assertFalse(configure.contains("allow-passthrough"))
        assertFalse(configure.contains("copy-mode -e"))
    }

    @Test
    fun localHistoryHydrationIsBoundedAndAnsiPreserving() {
        val hydrate = HostSessionBootstrap.hydrateLocalHistory("gxr-cockpit-1")

        assertTrue(hydrate.startsWith("printf '\\033[2J\\033[H'"))
        assertTrue(hydrate.contains("tmux -L gxr capture-pane"))
        assertTrue(hydrate.contains("-e -p"))
        assertTrue(hydrate.contains("-S -${ScrollbackPolicy.LOCAL_HISTORY_LINES}"))
        assertTrue(hydrate.contains("-E -1"))
        assertTrue(hydrate.contains("-t gxr-cockpit-1"))
        assertTrue(hydrate.contains("2>/dev/null || true"))
        // Clear again after dump so bootstrap/hydrate text does not stick in the live view.
        assertTrue(hydrate.endsWith("printf '\\033[2J\\033[H'"))
    }

    @Test
    fun quietPrefixDisablesEchoAndClearsViewport() {
        val prefix = HostSessionBootstrap.quietShellPrefix()
        assertTrue(prefix.contains("stty -echo"))
        assertTrue(prefix.contains("printf '\\033[2J\\033[H'"))
        assertTrue(prefix.endsWith("\r"))
    }

    private fun assertSessionConfigured(command: String, sessionName: String) {
        val t = HostSessionBootstrap.tmux()
        assertTrue(command.contains("$t set-option -t $sessionName status off"))
        assertTrue(command.contains("$t set-option -t $sessionName set-titles on"))
        assertTrue(command.contains("$t set-option -t $sessionName set-titles-string '#T'"))
    }

    private fun assertHistoryHydratedBeforeAttach(command: String, sessionName: String) {
        val hydrate = HostSessionBootstrap.hydrateLocalHistory(sessionName)
        val attach = "${HostSessionBootstrap.tmux()} attach-session -t $sessionName"
        assertTrue(command.contains(hydrate))
        assertTrue(command.indexOf(hydrate) < command.indexOf(attach))
    }

    private fun assertPhysicalLinesArePtySafe(command: String) {
        val longest = command.split('\r').maxOf(String::length)
        assertTrue("bootstrap physical line is $longest characters", longest < 1_000)
    }
}
