package com.ghosttyxr.app.terminal.voice.command

import com.ghosttyxr.app.terminal.voice.VoiceMessages
import com.ghosttyxr.app.util.GxrLog
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.ActivityDetectionConfig
import com.google.firebase.ai.type.AudioTranscriptionConfig
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.InlineData
import com.google.firebase.ai.type.LiveServerToolCall
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.RealtimeInputConfig
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.liveGenerationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val TAG = "LiveCommand"

/**
 * The single Firebase-/audio-touching layer for **voice command** mode over the Gemini Live API.
 * Replaces the old three-hop pipeline (record utterance → `stt` Cloud Function → `gemini-3.5-flash`
 * chat → `petTts` Cloud Function): one persistent bidirectional session carries mic audio in and
 * model speech + function calls back, with transcriptions of both sides streamed alongside.
 *
 * - Model: [LIVE_MODEL] (`gemini-3.8-live`; stable, Sept 2026). Audio response modality, Aoede
 *   voice (matches the old `petTts` voice), input + output transcriptions enabled, VAD with
 *   [RealtimeInputConfig.ActivityHandling.INTERRUPT] so user barge-in cuts pet speech.
 * - Mic: [LiveAudioStreamer] ships 100 ms 16 kHz PCM chunks via `sendAudioRealtime`; emission
 *   pauses while model audio plays (echo suppression), resuming after.
 * - Model speech: 24 kHz PCM frames stream into [LiveSpeechOutput] (production wraps
 *   `PetSpeechPlayer` with the pet-entity spatialized factory); output-audio transcription text
 *   becomes the `Result` toast + coordinator speech fallback when audio is unavailable.
 * - Tools: [CommandFunctions.declarations] run through the (Firebase-free) [VoiceCommandController]
 *   via [LiveCommandEngine]; the manual `receive()` loop is the single owner of audio,
 *   transcription, and tool messages. The first ambient-context snapshot is sent as text when
 *   the session opens so the model knows the live pane layout.
 *
 * Connection policy: Pro-gated (same as before — Live audio bills our Gemini key). The session
 * connects lazily on first use, stays open across turns while command mode is enabled, and
 * [reset] closes it. Server go-away / fatal errors drop to idle; the next turn reconnects.
 * `gemini-3.8-live` takes no `thinking_config` (unsupported — omit, don't default).
 */
@OptIn(PublicPreviewAPI::class)
class LiveCommandSession(
    private val controller: VoiceCommandController,
    private val engine: LiveCommandEngine,
    private val streamer: LiveAudioStreamer,
    private val scope: CoroutineScope,
    private val ambientContext: () -> String = { "" },
    /** Pro gate: Live audio bills our Gemini key, so free-tier turns never start. */
    private val isProProvider: () -> Boolean = { true },
    /**
     * Optional dictation sink: when present, input-audio transcriptions also feed dictation
     * accumulation. Wired by MainActivity so the pane mic button dictates through the shared
     * Live session.
     */
    var dictation: LiveDictationController? = null,
) {
    /**
     * Where model speech PCM plays. Swappable: MainActivity starts with the session's default
     * (plain player) and swaps in the pet surface's spatialized output once the Full Space
     * surface exists, so the voice emanates from the pet entity. Safe mid-turn — the engine
     * stops the old output before the new one starts.
     */
    @Volatile
    var speechOutput: LiveSpeechOutput = engine.audioOutput
        set(value) {
            engine.audioOutput.stop()
            field = value
            engine.audioOutput = value
        }

    private val fallbackSpeechOutput = engine.audioOutput

    /** Restore the non-spatial player before a pet surface and its player are disposed. */
    fun resetSpeechOutput() {
        speechOutput = fallbackSpeechOutput
    }

    @Volatile
    private var session: LiveSession? = null

    @Volatile
    private var connectJob: Job? = null

    @Volatile
    private var receiveJob: Job? = null

    @Volatile
    private var contextSent = false

    /** Independent of model output state: false means no microphone chunks leave the app. */
    @Volatile
    private var inputEnabled = false

    private val model by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).liveModel(
            modelName = LIVE_MODEL,
            generationConfig = liveGenerationConfig {
                responseModality = ResponseModality.AUDIO
                speechConfig = SpeechConfig(Voice(LIVE_VOICE))
                inputAudioTranscription = AudioTranscriptionConfig()
                outputAudioTranscription = AudioTranscriptionConfig()
                realtimeInputConfig = RealtimeInputConfig.Builder()
                    .setActivityHandling(RealtimeInputConfig.ActivityHandling.INTERRUPT)
                    .setTurnCoverage(RealtimeInputConfig.TurnCoverage.ONLY_ACTIVITY)
                    .setAutomaticActivityDetection(
                        ActivityDetectionConfig.Builder()
                            .setStartSensitivity(ActivityDetectionConfig.Sensitivity.HIGH)
                            .setEndSensitivity(ActivityDetectionConfig.Sensitivity.HIGH)
                            .setPrefixPaddingMs(VAD_PREFIX_PADDING_MS)
                            .setSilenceDurationMs(VAD_SILENCE_DURATION_MS)
                            .build(),
                    )
                    .build()
            },
            tools = listOf(Tool.functionDeclarations(CommandFunctions.declarations)),
            systemInstruction = content { text(CommandFunctions.systemInstruction) },
        )
    }

    /** UI state for the pet (listening pulse, toast). */
    val state: StateFlow<LiveCommandState> get() = engine.state

    /** Smoothed 0..1 mic level while streaming; drives the pet's listening pulse. */
    val amplitude: StateFlow<Float> get() = streamer.amplitude

    /**
     * Reveal/hotkey entry point: connect (Pro-gated), or re-enable microphone emission on the
     * existing connection for a new turn.
     */
    fun startIfIdle() {
        if (!isProProvider()) {
            controller.onError(VoiceMessages.PRO_REQUIRED_MESSAGE)
            return
        }
        inputEnabled = true
        val live = session
        if (live != null) {
            engine.onUserActivity()
            pauseMicDuringSpeech(live)
            return
        }
        if (connectJob != null) return
        connect()
    }

    /**
     * End the user's input turn without closing the Live connection. Receiving remains active so
     * VAD can finish the turn and model audio/tool responses can arrive after the palm or mic
     * button is released.
     */
    fun endInput() {
        inputEnabled = false
        streamer.setPaused(true)
    }

    /** Pinch-select / toggle off: cut audio, stop the mic, close the session, drop to idle. */
    fun cancel() {
        teardown()
        engine.reset()
    }

    /** Toggle off / lifecycle pause: cancel and forget the session so the next turn reconnects. */
    fun reset() {
        teardown()
        engine.reset()
    }

    private fun connect() {
        connectJob?.cancel()
        connectJob = scope.launch {
            try {
                val live = model.connect()
                session = live
                contextSent = false
                engine.onConnected()
                startStreaming(live)
                startReceiving(live)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (err: Throwable) {
                GxrLog.w(TAG, "live connect failed: ${err.message}")
                session = null
                engine.onError(err.message ?: "Voice command failed.")
            } finally {
                connectJob = null
            }
        }
    }

    private fun startStreaming(live: LiveSession) {
        runCatching {
            streamer.start { chunk ->
                scope.launch {
                    runCatching {
                        live.sendAudioRealtime(
                            InlineData(chunk, "audio/pcm;rate=${LiveAudioStreamer.SAMPLE_RATE_HZ}"),
                        )
                    }.onFailure {
                        if (it !is CancellationException) {
                            GxrLog.w(TAG, "audio send failed: ${it.message}")
                        }
                    }
                }
            }
            pauseMicDuringSpeech(live)
        }.getOrElse {
            GxrLog.w(TAG, "mic start failed: ${it.message}")
            engine.onError(it.message ?: "Couldn't start the microphone.")
            teardown()
            return
        }
    }

    private fun startReceiving(live: LiveSession) {
        receiveJob?.cancel()
        receiveJob = scope.launch {
            try {
                // First ambient-context snapshot: the model needs the live pane layout before
                // the first turn. Sent as text (not audio) so it can't be misheard.
                sendAmbientContext(live)
                live.receive().collect { message ->
                    if (message is LiveServerToolCall) {
                        // The manual receive loop is the sole tool owner: dispatch through the
                        // engine (round-capped), then answer the model on this same Live session.
                        val responses = routeLiveMessage(
                            engine,
                            message,
                            onInputTranscript = ::onInputTranscript,
                        )
                        if (!responses.isNullOrEmpty()) {
                            runCatching { live.sendFunctionResponse(responses) }
                                .onFailure {
                                    GxrLog.w(TAG, "tool response send failed: ${it.message}")
                                }
                        }
                    } else {
                        routeLiveMessage(
                            engine,
                            message,
                            onInputTranscript = ::onInputTranscript,
                        )
                    }
                    pauseMicDuringSpeech(live)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (err: Throwable) {
                GxrLog.w(TAG, "live receive failed: ${err.message}")
                session = null
                inputEnabled = false
                streamer.stop()
                engine.onDisconnected()
                engine.onError(err.message ?: "Voice command failed.")
            }
        }
    }

    private fun onInputTranscript(text: String) {
        if (text.isEmpty()) return
        dictation?.onTranscriptChunk(text)
    }

    private suspend fun sendAmbientContext(live: LiveSession) {
        if (contextSent) return
        val context = ambientContext().trim()
        if (context.isEmpty()) {
            contextSent = true
            return
        }
        runCatching { live.sendTextRealtime(context) }
            .onFailure { GxrLog.w(TAG, "ambient context send failed: ${it.message}") }
        contextSent = true
    }

    /**
     * Echo suppression: pause mic emission while model audio plays so the pet doesn't hear
     * itself. The engine flips to Speaking on the first audio frame and back on
     * interruption/turn completion; mirror that here.
     */
    private fun pauseMicDuringSpeech(live: LiveSession) {
        if (live.isClosed()) return
        streamer.setPaused(
            !inputEnabled || engine.state.value is LiveCommandState.Speaking,
        )
    }

    private fun teardown() {
        inputEnabled = false
        connectJob?.cancel()
        connectJob = null
        receiveJob?.cancel()
        receiveJob = null
        runCatching { streamer.stop() }
        val live = session
        session = null
        engine.onDisconnected()
        if (live != null) {
            scope.launch {
                runCatching { live.close() }
            }
        }
    }

    companion object {
        /** Stable default Live model for low-latency voice agents (Sept 2026). */
        const val LIVE_MODEL = "gemini-3.8-live"

        /** Matches the old `petTts` voice so the pet sounds like itself. */
        const val LIVE_VOICE = "Aoede"

        /** VAD tuning: quick start, patient end (CLI args/paths have mid-utterance pauses). */
        const val VAD_PREFIX_PADDING_MS = 300
        const val VAD_SILENCE_DURATION_MS = 800
    }
}
