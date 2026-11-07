package com.ghosttyxr.app.terminal.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaneCwdProbeTest {

    @Test
    fun buildsAuthoritativeTmuxCwdQueryForSourceSession() {
        val command = PaneCwdProbe.buildCommand("gxr-cockpit-7")

        assertTrue(command.contains("tmux -L ${HostSessionBootstrap.TMUX_SOCKET}"))
        assertTrue(command.contains("display-message -p -t gxr-cockpit-7"))
        assertTrue(command.contains("'#{pane_current_path}'"))
        assertTrue(command.startsWith("stty -echo"))
        assertTrue(command.endsWith("\r"))
    }

    @Test
    fun commandTextNeverContainsContiguousMarkers() {
        // The pty echoes the typed command back before stty -echo can land; if
        // the command text contained a contiguous marker, the reader would stop
        // at (or the parser match on) the echo instead of the real output.
        val command = PaneCwdProbe.buildCommand("gxr-cockpit-7")

        assertFalse(command.contains(PaneCwdProbe.RESULT_MARKER))
        assertFalse(command.contains(PaneCwdProbe.DONE_MARKER))
    }

    @Test
    fun rejectsUnsafeSessionNames() {
        val result = runCatching { PaneCwdProbe.buildCommand("bad;name") }
        assertTrue(result.isFailure)
    }

    @Test
    fun parsesCwdFromMarkerLineAmongShellNoise() {
        val output = "user@mac ~ % printf '__GXR_%s\\t%s\\n__GXR_%s\\n' 'CWD__' ...\r\n" +
            "${PaneCwdProbe.RESULT_MARKER}\t/Users/me/ghostty-vibe-xr\r\n" +
            "${PaneCwdProbe.DONE_MARKER}\r\n" +
            "user@mac ~ % "

        assertEquals("/Users/me/ghostty-vibe-xr", PaneCwdProbe.parseOutput(output))
    }

    @Test
    fun parsesCwdWhenPromptTitleEscapesPrefixTheMarkerLine() {
        // Exact shape captured from the headset: zsh's preexec title escapes
        // end with BEL, not newline, so they prefix the result line.
        val output = "\u001b]2;stty -echo 2> /dev/null; printf '__GXR_%s\\t%s\\n__GXR_%s\\n' 'CWD__'  ; stty  2\u0007" +
            "\u001b]1;stty\u0007" +
            "${PaneCwdProbe.RESULT_MARKER}\t/Users/nikhilanand/ghostty-vibe-xr\r\n" +
            "${PaneCwdProbe.DONE_MARKER}\r\n"

        assertEquals("/Users/nikhilanand/ghostty-vibe-xr", PaneCwdProbe.parseOutput(output))
    }

    @Test
    fun parsesCwdWhenPtyExpandedTheTabToSpaces() {
        val output = "${PaneCwdProbe.RESULT_MARKER}     /Users/me/proj\r\n${PaneCwdProbe.DONE_MARKER}\r\n"
        assertEquals("/Users/me/proj", PaneCwdProbe.parseOutput(output))
    }

    @Test
    fun returnsNullWhenSessionHadNoCwd() {
        val output = "${PaneCwdProbe.RESULT_MARKER}\t\r\n${PaneCwdProbe.DONE_MARKER}\r\n"
        assertNull(PaneCwdProbe.parseOutput(output))
    }

    @Test
    fun returnsNullForRelativeOrMissingPaths() {
        assertNull(PaneCwdProbe.parseOutput("${PaneCwdProbe.RESULT_MARKER}\trelative/path\n"))
        assertNull(PaneCwdProbe.parseOutput("no markers at all"))
    }
}
