package com.ghosttyxr.app.terminal.voice.command

import com.ghosttyxr.app.terminal.voice.GeminiKeyStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.Base64

/** A direct Gemini Live session authenticated with the user's key. */
class LiveCommandSession(
    private val controller: VoiceCommandController,
    private val engine: LiveCommandEngine,
    private val streamer: LiveAudioStreamer,
    private val scope: CoroutineScope,
    private val ambientContext: () -> String = { "" },
    var dictation: LiveDictationController? = null,
) {
    @Volatile var speechOutput: LiveSpeechOutput = engine.audioOutput
        set(value) {
            engine.audioOutput.stop()
            field = value
            engine.audioOutput = value
        }
    private val fallbackSpeechOutput = engine.audioOutput
    fun resetSpeechOutput() { speechOutput = fallbackSpeechOutput }
    val state: StateFlow<LiveCommandState> get() = engine.state
    val amplitude: StateFlow<Float> get() = streamer.amplitude

    private val client = OkHttpClient()
    private var socket: WebSocket? = null
    private var job: Job? = null
    private var generation = 0
    @Volatile private var inputEnabled = false

    init {
        scope.launch { GeminiKeyStore.revision.drop(1).collect { reset() } }
    }

    fun startIfIdle() {
        if (socket != null) {
            inputEnabled = true
            engine.onUserActivity()
            updatePause()
            return
        }
        if (job != null) return
        val key = GeminiKeyStore.get()
        if (key == null) {
            engine.onError("Add your Gemini API key in Settings → Voice.")
            return
        }
        inputEnabled = true
        val attempt = ++generation
        job = scope.launch {
            val ready = CompletableDeferred<Unit>()
            val messages = Channel<String>(Channel.UNLIMITED)
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(setupMessage().toString())
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val parsed = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
                    if (parsed?.containsKey("setupComplete") == true) ready.complete(Unit)
                    else messages.trySend(text)
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) =
                    onMessage(webSocket, bytes.utf8())
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    ready.completeExceptionally(IllegalStateException("Gemini connection failed. Check your key and network."))
                    messages.close(IllegalStateException("Gemini connection failed. Check your key and network."))
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, null)
                    ready.completeExceptionally(IllegalStateException("Gemini session closed."))
                    messages.close()
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    ready.completeExceptionally(IllegalStateException("Gemini session closed."))
                    messages.close()
                }
            }
            val request = liveRequest(key)
            val live = client.newWebSocket(request, listener)
            try {
                withTimeout(20_000) { ready.await() }
                socket = live
                engine.onConnected()
                val context = ambientContext().trim()
                if (context.isNotEmpty()) live.send(buildJsonObject {
                    putJsonObject("realtimeInput") { put("text", context) }
                }.toString())
                streamer.start { chunk ->
                    if (!inputEnabled || engine.state.value is LiveCommandState.Speaking) return@start
                    live.send(buildJsonObject {
                        putJsonObject("realtimeInput") {
                            putJsonObject("audio") {
                                put("mimeType", "audio/pcm;rate=16000")
                                put("data", Base64.getEncoder().encodeToString(chunk))
                            }
                        }
                    }.toString())
                }
                updatePause()
                for (text in messages) {
                    val message = Json.parseToJsonElement(text).jsonObject
                    val responses = routeLiveMessage(engine, message) { dictation?.onTranscriptChunk(it) }
                    if (!responses.isNullOrEmpty()) live.send(buildJsonObject {
                        putJsonObject("toolResponse") {
                            put("functionResponses", buildJsonArray {
                                responses.forEach { result ->
                                    add(buildJsonObject {
                                        put("name", result.name)
                                        put("id", result.id)
                                        put("response", result.response)
                                    })
                                }
                            })
                        }
                    }.toString())
                    updatePause()
                }
                engine.onError("Gemini voice session ended. Tap to reconnect.")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                engine.onError("Gemini voice connection failed. Check your key and network.")
            } finally {
                if (generation == attempt) {
                    streamer.stop()
                    socket = null
                    inputEnabled = false
                    engine.onDisconnected()
                    job = null
                }
                live.close(1000, "Session ended")
                messages.close()
            }
        }
    }

    fun endInput() {
        inputEnabled = false
        streamer.setPaused(true)
        socket?.send("""{"realtimeInput":{"audioStreamEnd":true}}""")
    }

    fun cancel() { reset() }
    fun reset() {
        generation++
        inputEnabled = false
        streamer.stop()
        socket?.close(1000, "Session ended")
        socket = null
        job?.cancel()
        job = null
        engine.reset()
    }

    private fun updatePause() {
        streamer.setPaused(!inputEnabled || engine.state.value is LiveCommandState.Speaking)
    }

    internal fun setupMessage(): JsonObject = buildJsonObject {
        putJsonObject("setup") {
            put("model", "models/$LIVE_MODEL")
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add(kotlinx.serialization.json.JsonPrimitive("AUDIO")) }
                putJsonObject("speechConfig") {
                    putJsonObject("voiceConfig") {
                        putJsonObject("prebuiltVoiceConfig") { put("voiceName", "Aoede") }
                    }
                }
            }
            putJsonObject("inputAudioTranscription") {}
            putJsonObject("outputAudioTranscription") {}
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { add(buildJsonObject { put("text", CommandFunctions.systemInstruction) }) }
            }
            putJsonArray("tools") {
                add(buildJsonObject {
                    put("functionDeclarations", buildJsonArray {
                        CommandFunctions.declarations.forEach { add(it.toJson()) }
                    })
                })
            }
        }
    }

    companion object {
        private const val LIVE_URL = "https://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        const val LIVE_MODEL = "gemini-3.8-live"
        internal fun liveRequest(key: String): Request =
            Request.Builder().url(LIVE_URL).header("x-goog-api-key", key).build()
    }
}
