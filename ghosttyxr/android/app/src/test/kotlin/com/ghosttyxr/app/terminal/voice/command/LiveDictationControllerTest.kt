package com.ghosttyxr.app.terminal.voice.command

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDictationControllerTest {

    private val sent = mutableListOf<Pair<Int, String>>()

    private fun controller(
        paneCount: Int = 2,
        autoSubmit: Boolean = false,
        hasKey: Boolean = true,
    ) = LiveDictationController(
        scope = CoroutineScope(Dispatchers.Unconfined),
        paneCount = { paneCount },
        sendToPane = { paneId, bytes -> sent += paneId to bytes.toString(Charsets.UTF_8) },
        focusedPaneIdProvider = { 5 },
        autoSubmitProvider = { autoSubmit },
        hasKeyProvider = { hasKey },
    )

    private class FakeInputLifecycle : LiveInputLifecycle {
        var starts = 0
        var ends = 0

        override fun startInput() {
            starts++
        }

        override fun endInput() {
            ends++
        }
    }

    @Test
    fun `toggle accumulates then inserts normalized text into clamped pane`() {
        val controller = controller()
        controller.toggle()
        assertTrue(controller.state.value is LiveDictationState.Accumulating)

        controller.onTranscriptChunk("git  status\n")
        controller.onTranscriptChunk("  --short")
        controller.toggle()

        val state = controller.state.value
        assertTrue(state is LiveDictationState.Inserted)
        assertEquals("git status --short", (state as LiveDictationState.Inserted).text)
        // Focused id 5 clamps to pane 1 (last of 2).
        assertEquals(listOf(1 to "git status --short"), sent)
    }

    @Test
    fun `auto-submit appends carriage return`() {
        val controller = controller(autoSubmit = true)
        controller.toggle()
        controller.onTranscriptChunk("git status")
        controller.toggle()
        assertEquals(listOf(1 to "git status\r"), sent)
    }

    @Test
    fun `start and stop own shared input lifecycle exactly once`() {
        val lifecycle = FakeInputLifecycle()
        val controller = controller().apply { inputLifecycle = lifecycle }

        controller.start()
        controller.start()
        controller.onTranscriptChunk("echo ready")
        controller.finishAndInsert()
        controller.finishAndInsert()

        assertEquals(1, lifecycle.starts)
        assertEquals(1, lifecycle.ends)
        assertEquals(listOf(1 to "echo ready"), sent)
    }

    @Test
    fun `empty transcript is a no-speech error`() {
        val controller = controller()
        controller.toggle()
        controller.toggle()
        val state = controller.state.value
        assertTrue(state is LiveDictationState.Error)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `missing key cannot start`() {
        val controller = controller(hasKey = false)
        controller.toggle()
        val state = controller.state.value
        assertTrue(state is LiveDictationState.Error)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `cancel discards without inserting`() {
        val lifecycle = FakeInputLifecycle()
        val controller = controller().apply { inputLifecycle = lifecycle }
        controller.toggle()
        controller.onTranscriptChunk("hello")
        controller.cancel()
        controller.cancel()
        assertEquals(LiveDictationState.Idle, controller.state.value)
        assertEquals(1, lifecycle.ends)
        controller.toggle()
        controller.toggle()
        // Fresh accumulation is empty → no-speech error, nothing sent.
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `acknowledge returns to idle`() {
        val controller = controller()
        controller.toggle()
        controller.onTranscriptChunk("hi")
        controller.toggle()
        controller.acknowledge()
        assertEquals(LiveDictationState.Idle, controller.state.value)
    }
}
