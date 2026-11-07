package com.ghosttyxr.app.terminal.voice.command

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class GeminiLiveProtocolTest {
    private fun session(): LiveCommandSession {
        val controller = VoiceCommandController(
            paneCount = { 1 }, addPane = { null }, focusPane = {}, sendToPane = { _, _ -> },
        )
        val output = object : LiveSpeechOutput {
            override fun start(sampleRateHz: Int) = Unit
            override fun write(chunk: ByteArray) = Unit
            override suspend fun finish() = Unit
            override fun stop() = Unit
        }
        return LiveCommandSession(controller, LiveCommandEngine(controller, output), LiveAudioStreamer(), TestScope())
    }

    @Test fun `setup includes voice transcriptions and tools without a bundled key`() {
        val setup = session().setupMessage()["setup"]!!.jsonObject
        assertEquals("models/gemini-3.8-live", setup["model"]!!.jsonPrimitive.content)
        assertTrue(setup.containsKey("inputAudioTranscription"))
        assertTrue(setup.containsKey("outputAudioTranscription"))
        val functions = setup["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray
        assertEquals(7, functions.size)
        assertTrue(functions.any { it.jsonObject["name"]!!.jsonPrimitive.content == "send_to_terminal" })
        assertTrue(!setup.toString().contains("api_key", ignoreCase = true))
    }

    @Test fun `production setup is accepted by the direct Gemini endpoint`() {
        assumeTrue(System.getenv("RUN_GEMINI_LIVE_TEST") == "1")
        val key = System.getenv("GEMINI_API_KEY")
        assumeTrue(!key.isNullOrBlank())
        val ready = CountDownLatch(1)
        val audioReceived = CountDownLatch(1)
        var accepted = false
        var failureReason = "No response"
        val client = OkHttpClient()
        val socket = client.newWebSocket(LiveCommandSession.liveRequest(key!!), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(session().setupMessage().toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                if (message.containsKey("setupComplete")) {
                    accepted = true
                    ready.countDown()
                    webSocket.send("""{"realtimeInput":{"text":"Say hello briefly."}}""")
                }
                val parts = message["serverContent"]?.jsonObject?.get("modelTurn")
                    ?.jsonObject?.get("parts")?.jsonArray
                if (parts?.any { it.jsonObject.containsKey("inlineData") } == true) {
                    audioReceived.countDown()
                }
            }
            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) =
                onMessage(webSocket, bytes.utf8())
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failureReason = "WebSocket failure: ${t.javaClass.simpleName}"
                ready.countDown()
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                failureReason = "Server closed ($code): ${reason.replace(key!!, "[redacted]")}"
                ready.countDown()
            }
        })
        try {
            assertTrue("Gemini setup timed out", ready.await(30, TimeUnit.SECONDS))
            assertTrue("Gemini rejected the production setup: $failureReason", accepted)
            assertTrue("Gemini did not return audio", audioReceived.await(45, TimeUnit.SECONDS))
        } finally {
            socket.cancel()
            client.dispatcher.executorService.shutdown()
        }
    }
}
