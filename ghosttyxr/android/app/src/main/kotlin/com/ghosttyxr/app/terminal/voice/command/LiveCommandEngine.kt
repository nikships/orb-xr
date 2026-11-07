package com.ghosttyxr.app.terminal.voice.command

import com.ghosttyxr.app.terminal.voice.VoiceMessages
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull

/**
 * Pure dispatch + speech-output core for the **Gemini Live** voice-command path. Owns no mic
 * or network, and uses plain-JVM `FunctionCallPart`/`FunctionResponsePart`
 * value types — every Live-session, audio-playback, and text-speech dependency is injected as a
 * lambda or small interface, so the whole turn state machine is unit-tested with fakes.
 *
 * Responsibilities:
 * - Track Live connection state ([LiveCommandState]) for the pet UI (listening pulse, toast).
 * - Run the function-dispatch loop: model tool calls → [VoiceCommandController.handleCall] →
 *   `FunctionResponsePart` list the session sends back. Async (non-blocking) tool calls on
 *   `gemini-3.8-live` are answered as they arrive; [MAX_TOOL_ROUNDS] still bounds a runaway loop.
 * - Funnel model speech into [LiveSpeechOutput]: raw 24 kHz PCM frames stream into the player's
 *   `AudioTrack` (point-source spatialized onto the pet entity in production); the
 *   output-audio transcription text lands as `Result`/`Error` state for the toast +
 *   [com.ghosttyxr.app.terminal.voice.tts.PetSpeechCoordinator].
 *
 * The direct Gemini adapter ([LiveCommandSession]) owns the connection, mic streaming, PCM
 * playback, and plaintext fallback speech; this core only decides *what* happens per message.
 *
 */
class LiveCommandEngine(
    private val controller: VoiceCommandController,
    /** Where model speech PCM plays; swappable when the pet surface (re)builds. */
    var audioOutput: LiveSpeechOutput,
) {
    private val _state = MutableStateFlow<LiveCommandState>(LiveCommandState.Idle)
    val state: StateFlow<LiveCommandState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<LiveCommandEvent>(extraBufferCapacity = 16)
    val events: Flow<LiveCommandEvent> = _events.asSharedFlow()

    /** True while the Live session is connected (set by the adapter around `connect()`). */
    @Volatile
    var connected: Boolean = false
        private set

    private var rounds = 0
    private var lastResults: List<Pair<String, JsonObject>> = emptyList()
    private val outputTranscript = StringBuilder()
    private var audioStarted = false
    /** Set when any model audio frame played this turn; gates the plaintext fallback. */
    private var audioPlayed = false

    fun onConnected() {
        connected = true
        rounds = 0
        lastResults = emptyList()
        outputTranscript.clear()
        audioStarted = false
        audioPlayed = false
        _state.value = LiveCommandState.Listening
    }

    fun onDisconnected() {
        connected = false
    }

    /**
     * User started speaking (VAD activity or manual demarcation): reset per-turn output
     * accumulation so the previous reply can't bleed into the new turn's toast.
     */
    fun onUserActivity() {
        if (_state.value is LiveCommandState.Result || _state.value is LiveCommandState.Error) {
            outputTranscript.clear()
            audioStarted = false
            audioPlayed = false
            _state.value = LiveCommandState.Listening
        }
    }

    /** Model audio frame (24 kHz mono 16-bit PCM): stream it to the pet's voice output. */
    fun onModelAudio(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        if (!audioStarted) {
            audioStarted = true
            _state.value = LiveCommandState.Speaking
            audioOutput.stop()
            audioOutput.start(LIVE_OUTPUT_SAMPLE_RATE_HZ)
        } else if (_state.value !is LiveCommandState.Speaking) {
            _state.value = LiveCommandState.Speaking
        }
        if (runCatching { audioOutput.write(pcm) }.isSuccess) audioPlayed = true
    }

    /** Model was interrupted (user barge-in): cut pet speech immediately. */
    fun onInterrupted() {
        audioOutput.stop()
        audioStarted = false
        _state.value = LiveCommandState.Listening
    }

    /** Incremental output transcription: accumulate; the full text lands on turn completion. */
    fun onOutputTranscript(text: String) {
        if (text.isNotEmpty()) outputTranscript.append(text)
    }

    /**
     * Handle one batch of model tool calls: run each through the controller (parallel fan-out
     * happens in the caller), return the responses for the session to send back. Returns null
     * when the round cap is hit — the caller should stop answering further calls this turn.
     */
    internal suspend fun onToolCalls(calls: List<FunctionCallPart>): List<FunctionResponsePart>? {
        if (calls.isEmpty()) return emptyList()
        if (rounds >= MAX_TOOL_ROUNDS) return null
        rounds++
        _state.value = LiveCommandState.Thinking
        val results = calls.map { it.name to controller.handleCall(it.name, it.args) }
        lastResults = results
        return calls.zip(results).map { (call, result) -> FunctionResponsePart(result.first, result.second, call.id) }
    }

    /**
     * The model finished a turn: flush in-flight pet audio, then publish the reply text for the
     * toast + coordinator. Falls back to the last function result's `message` when the model
     * produced no transcript (e.g. a pure action turn), mirroring the old batch path.
     */
    suspend fun onTurnComplete() {
        audioOutput.finish()
        val text = outputTranscript.toString().trim().ifEmpty { lastResultMessage() }
        outputTranscript.clear()
        audioStarted = false
        val playedAudio = audioPlayed
        audioPlayed = false
        rounds = 0
        if (text.isNullOrEmpty()) {
            controller.onError(VoiceMessages.NO_SPEECH_MESSAGE)
            _state.value = LiveCommandState.Error(VoiceMessages.NO_SPEECH_MESSAGE)
        } else {
            controller.onResult(text)
            _state.value = LiveCommandState.Result(text)
            // Plaintext fallback only when the model produced transcript but no audible speech
            // (or playback failed): in the common case the user already heard the reply.
            if (!playedAudio) _events.tryEmit(LiveCommandEvent.SpeakText(text))
        }
        lastResults = emptyList()
    }

    /** Fatal session error: surface through the controller and drop to idle. */
    fun onError(message: String) {
        audioOutput.stop()
        controller.onError(message)
        _state.value = LiveCommandState.Error(message)
        rounds = 0
        lastResults = emptyList()
        outputTranscript.clear()
        audioStarted = false
        audioPlayed = false
    }

    /** Toggle off / lifecycle pause: cut audio and drop to idle. */
    fun reset() {
        audioOutput.stop()
        controller.reset()
        _state.value = LiveCommandState.Idle
        rounds = 0
        lastResults = emptyList()
        outputTranscript.clear()
        audioStarted = false
        audioPlayed = false
    }

    private fun lastResultMessage(): String? {
        if (lastResults.isEmpty()) return null
        return lastResults.lastOrNull()?.second
            ?.get("message")?.let { (it as? JsonPrimitive)?.content }
    }

    companion object {
        /** Model speech arrives as 24 kHz mono 16-bit PCM (Live API output format). */
        const val LIVE_OUTPUT_SAMPLE_RATE_HZ = 24_000

        /**
         * Max function-calling rounds per turn. `gemini-3.8-live` runs tools asynchronously
         * (non-blocking) but a misbehaving prompt could still loop; once hit, further tool
         * calls this turn go unanswered and the turn completes with what we have.
         */
        const val MAX_TOOL_ROUNDS = 5
    }
}

/** UI state for the Live voice-command path (pet pulse, toast). Mirrors [VoiceCommandState]. */
sealed interface LiveCommandState {
    data object Idle : LiveCommandState
    data object Listening : LiveCommandState
    data object Thinking : LiveCommandState
    data object Speaking : LiveCommandState
    data class Result(val message: String) : LiveCommandState
    data class Error(val message: String) : LiveCommandState
}

/**
 * Out-of-band events the adapter handles outside the state flow: speak [SpeakText] through the
 * plaintext fallback only when model audio is unavailable.
 */
sealed interface LiveCommandEvent {
    data class SpeakText(val text: String) : LiveCommandEvent
}

/**
 * Fallback speech for turns where the model produced transcript text but no audio frames
 * (or audio playback failed): speaks plain text through the existing pet TTS path. The Live
 * path never touches the `petTts` Cloud Function or Deepgram — this delegates to whatever
 * synthesizer the app wires (prebaked clips in production).
 */
fun interface TranscriptSpeech {
    suspend fun speak(text: String)
}

/**
 * Streaming sink for model speech PCM (24 kHz mono 16-bit). Production wraps
 * [com.ghosttyxr.app.terminal.voice.tts.PetSpeechPlayer] with the spatialized output factory
 * so the pet's Live voice emanates from the pet entity; tests inject a fake.
 */
interface LiveSpeechOutput {
    fun start(sampleRateHz: Int)
    fun write(chunk: ByteArray)
    suspend fun finish()
    fun stop()
}

/**
 * Route one Gemini Live server message into [LiveCommandEngine].
 * Kept as a free function (not a method) so the routing table unit-tests without a session.
 *
 * Returns the function responses to send back when the message carried tool calls, or null
 * when there is nothing to answer (or the round cap was hit).
 */
internal suspend fun routeLiveMessage(
    engine: LiveCommandEngine,
    message: JsonObject,
    onInputTranscript: (String) -> Unit = {},
): List<FunctionResponsePart>? {
    message["toolCall"]?.jsonObject?.get("functionCalls")?.jsonArray?.let { calls ->
        return engine.onToolCalls(calls.map { item ->
            val call = item.jsonObject
            FunctionCallPart(
                call["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                call["args"]?.jsonObject.orEmpty(),
                call["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        })
    }
    if (message.containsKey("toolCallCancellation")) engine.onInterrupted()
    if (message.containsKey("goAway")) engine.onError("Voice session ended — tap to reconnect.")
    message["serverContent"]?.jsonObject?.let { content ->
        content["inputTranscription"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotEmpty() }?.let { engine.onUserActivity(); onInputTranscript(it) }
        if (content["interrupted"]?.jsonPrimitive?.booleanOrNull == true) engine.onInterrupted()
        content["outputTranscription"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
            ?.let(engine::onOutputTranscript)
        content["modelTurn"]?.jsonObject?.get("parts")?.jsonArray?.forEach { part ->
            val data = part.jsonObject["inlineData"]?.jsonObject ?: return@forEach
            val mime = data["mimeType"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val audio = data["data"]?.jsonPrimitive?.contentOrNull
            if (mime.startsWith("audio/pcm", ignoreCase = true) && audio != null) {
                engine.onModelAudio(java.util.Base64.getDecoder().decode(audio))
            }
        }
        if (content["turnComplete"]?.jsonPrimitive?.booleanOrNull == true) engine.onTurnComplete()
    }
    return null
}
