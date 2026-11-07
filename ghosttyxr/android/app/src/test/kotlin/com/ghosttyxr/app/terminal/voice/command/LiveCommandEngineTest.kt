package com.ghosttyxr.app.terminal.voice.command

import com.ghosttyxr.app.terminal.voice.VoiceMessages
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.LiveServerContent
import com.google.firebase.ai.type.LiveServerToolCall
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.TextPart
import com.google.firebase.ai.type.Transcription
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(PublicPreviewAPI::class)
class LiveCommandEngineTest {

    private class FakeOutput : LiveSpeechOutput {
        var startedRate = -1
        val written = mutableListOf<ByteArray>()
        var finishCalls = 0
        var stopCalls = 0

        override fun start(sampleRateHz: Int) {
            startedRate = sampleRateHz
        }

        override fun write(chunk: ByteArray) {
            written += chunk
        }

        override suspend fun finish() {
            finishCalls++
        }

        override fun stop() {
            stopCalls++
        }
    }

    private fun controller() = VoiceCommandController(
        paneCount = { 1 },
        addPane = { _ -> null },
        focusPane = {},
        sendToPane = { _, _ -> },
    )

    private fun engine(
        controller: VoiceCommandController = controller(),
        output: FakeOutput = FakeOutput(),
    ) = LiveCommandEngine(
        controller = controller,
        audioOutput = output,
    )

    private fun toolCall(name: String, args: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap()) =
        FunctionCallPart(name, args)

    private fun transcription(text: String): Transcription {
        val constructor = Transcription::class.java.declaredConstructors
            .single { it.parameterCount == 1 }
            .apply { isAccessible = true }
        return constructor.newInstance(text) as Transcription
    }

    @Test
    fun `model audio starts speech output once and streams frames`() {
        val output = FakeOutput()
        val engine = engine(output = output)
        engine.onConnected()

        engine.onModelAudio(byteArrayOf(1, 2))
        engine.onModelAudio(byteArrayOf(3, 4))

        assertEquals(LiveCommandEngine.LIVE_OUTPUT_SAMPLE_RATE_HZ, output.startedRate)
        assertEquals(2, output.written.size)
        assertEquals(LiveCommandState.Speaking, engine.state.value)
    }

    @Test
    fun `interruption cuts speech and returns to listening`() {
        val output = FakeOutput()
        val engine = engine(output = output)
        engine.onConnected()

        engine.onModelAudio(byteArrayOf(1, 2))
        // onModelAudio stops any previous utterance before starting: 1 stop from start + 1
        // from the interruption itself.
        engine.onInterrupted()

        assertEquals(2, output.stopCalls)
        assertEquals(LiveCommandState.Listening, engine.state.value)
    }

    @Test
    fun `turn with transcript only completes to result without audio`() = runTest {
        val output = FakeOutput()
        val engine = engine(output = output)
        engine.onConnected()

        // No audio frames: transcript-only turn.
        engine.onOutputTranscript("Focused terminal 1.")
        engine.onTurnComplete()

        assertEquals(LiveCommandState.Result("Focused terminal 1."), engine.state.value)
        assertTrue(output.written.isEmpty())
        assertEquals(1, output.finishCalls)
    }

    @Test
    fun `empty turn uses shared no-speech message`() = runTest {
        val engine = engine()
        engine.onConnected()

        engine.onTurnComplete()

        assertEquals(
            LiveCommandState.Error(VoiceMessages.NO_SPEECH_MESSAGE),
            engine.state.value,
        )
    }

    @Test
    fun `tool calls dispatch through controller and cap at max rounds`() = runTest {
        var focusCalls = 0
        val focusedController = VoiceCommandController(
            paneCount = { 2 },
            addPane = { _ -> null },
            focusPane = { focusCalls++ },
            sendToPane = { _, _ -> },
        )
        val engine = engine(controller = focusedController)
        engine.onConnected()

        val responses = engine.onToolCalls(
            listOf(toolCall("focus_terminal", mapOf("terminal_number" to JsonPrimitive(2)))),
        )
        assertEquals(1, responses!!.size)
        assertEquals(1, focusCalls)
        assertEquals(LiveCommandState.Thinking, engine.state.value)

        // Exhaust the round cap: further calls go unanswered (null).
        repeat(LiveCommandEngine.MAX_TOOL_ROUNDS) {
            engine.onToolCalls(listOf(toolCall("focus_terminal")))
        }
        assertNull(engine.onToolCalls(listOf(toolCall("focus_terminal"))))
    }

    @Test
    fun `routeLiveMessage answers tool calls and completes audio turns`() = runTest {
        val output = FakeOutput()
        var focusCalls = 0
        val focusedController = VoiceCommandController(
            paneCount = { 2 },
            addPane = { _ -> null },
            focusPane = { focusCalls++ },
            sendToPane = { _, _ -> },
        )
        val engine = engine(controller = focusedController, output = output)
        engine.onConnected()

        val toolMsg = LiveServerToolCall(
            listOf(toolCall("focus_terminal", mapOf("terminal_number" to JsonPrimitive(1)))),
        )
        val responses: List<FunctionResponsePart>? = routeLiveMessage(engine, toolMsg)
        assertEquals(1, responses!!.size)
        assertEquals(1, focusCalls)

        // PCM part inside server content routes to the output; turnComplete finishes the turn.
        val pcmContent = LiveServerContent(
            content = com.google.firebase.ai.type.Content(
                "model",
                listOf(InlineDataPart(byteArrayOf(9, 9), "audio/pcm;rate=24000")),
            ),
            interrupted = false,
            turnComplete = true,
            generationComplete = true,
            inputTranscription = null,
            outputTranscription = null,
        )
        routeLiveMessage(engine, pcmContent)
        assertEquals(1, output.written.size)
        // Audio played but no transcript and no tool results: the last tool result's message
        // ("Focused terminal 1.") is the fallback reply → Result, not Error.
        assertEquals(
            LiveCommandState.Result("Focused terminal 1."),
            engine.state.value,
        )
    }

    @Test
    fun `routeLiveMessage accumulates output text into result`() = runTest {
        val engine = engine()
        engine.onConnected()

        val textContent = LiveServerContent(
            content = com.google.firebase.ai.type.Content(
                "model",
                listOf(TextPart("Focused terminal 1.")),
            ),
            interrupted = false,
            turnComplete = true,
            generationComplete = true,
            inputTranscription = null,
            outputTranscription = null,
        )
        // Output transcription normally arrives via the transcript handler; simulate it here.
        engine.onOutputTranscript("Focused terminal 1.")
        routeLiveMessage(engine, textContent)
        assertEquals(LiveCommandState.Result("Focused terminal 1."), engine.state.value)
    }

    @Test
    fun `routeLiveMessage forwards input transcription and rearms listening`() = runTest {
        val engine = engine()
        val transcripts = mutableListOf<String>()
        engine.onConnected()
        engine.onOutputTranscript("Previous turn")
        engine.onTurnComplete()

        routeLiveMessage(
            engine,
            LiveServerContent(
                content = null,
                interrupted = false,
                turnComplete = false,
                generationComplete = false,
                inputTranscription = transcription("git status"),
                outputTranscription = null,
            ),
            onInputTranscript = transcripts::add,
        )

        assertEquals(listOf("git status"), transcripts)
        assertEquals(LiveCommandState.Listening, engine.state.value)
    }

    @Test
    fun `error drops to error state and reset clears`() {
        val engine = engine()
        engine.onConnected()
        engine.onError("Session failed.")
        assertEquals(LiveCommandState.Error("Session failed."), engine.state.value)
        engine.reset()
        assertEquals(LiveCommandState.Idle, engine.state.value)
    }

    @Test
    fun `user activity rearms listening after a result`() = runTest {
        val engine = engine()
        engine.onConnected()
        engine.onOutputTranscript("Done.")
        engine.onTurnComplete()
        assertTrue(engine.state.value is LiveCommandState.Result)
        engine.onUserActivity()
        assertEquals(LiveCommandState.Listening, engine.state.value)
    }
}
