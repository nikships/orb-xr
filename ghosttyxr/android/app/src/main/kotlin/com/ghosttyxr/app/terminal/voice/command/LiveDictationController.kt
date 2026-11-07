package com.ghosttyxr.app.terminal.voice.command

import com.ghosttyxr.app.terminal.voice.VoiceMessages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val TAG = "LiveDictation"

/**
 * Dictation over the Gemini Live session: spoken words become text inserted into the focused pane.
 *
 * The old path (record utterance → `stt` Cloud Function → Deepgram transcript → insert) is deleted.
 * This controller instead consumes the Live session's **input-audio transcription** stream: as the
 * user speaks, transcribed text accumulates; when the turn completes, the full text is normalized
 * (same rules as [VoiceMessages.normalizeTranscript]) and routed through the focused pane's
 * `submitCommand` — the existing path for already-UTF-8 text — rather than synthesizing keystrokes.
 *
 * Injection keeps pane routing, auto-submit and missing-key handling testable without an emulator.
 */
class LiveDictationController(
    private val scope: CoroutineScope,
    private val paneCount: () -> Int,
    private val sendToPane: (paneId: Int, bytes: ByteArray) -> Unit,
    private val focusedPaneIdProvider: () -> Int,
    private val autoSubmitProvider: () -> Boolean,
    private val hasKeyProvider: () -> Boolean,
) {
    /**
     * Shared Live microphone lifecycle. MainActivity binds this after constructing the session;
     * the no-op default keeps previews and JVM tests independent from Firebase and Android audio.
     */
    var inputLifecycle: LiveInputLifecycle = LiveInputLifecycle.None

    private val _state = MutableStateFlow<LiveDictationState>(LiveDictationState.Idle)
    val state: StateFlow<LiveDictationState> = _state.asStateFlow()

    private val transcript = StringBuilder()

    @Volatile
    private var active = false

    /**
     * Mic-button toggle: start accumulating when idle, finish + insert when accumulating.
     * Cancellation returns straight to idle without inserting.
     */
    fun toggle() {
        when (_state.value) {
            is LiveDictationState.Accumulating -> finishAndInsert()
            is LiveDictationState.Inserting -> Unit
            else -> start()
        }
    }

    fun start() {
        if (_state.value is LiveDictationState.Accumulating) return
        if (!hasKeyProvider()) {
            _state.value = LiveDictationState.Error(VoiceMessages.KEY_REQUIRED_MESSAGE)
            return
        }
        transcript.clear()
        active = true
        _state.value = LiveDictationState.Accumulating(0f)
        inputLifecycle.startInput()
    }

    /** Live input transcription chunk: accumulate; the meter follows chunk cadence. */
    fun onTranscriptChunk(text: String) {
        if (!active || text.isEmpty()) return
        if (_state.value !is LiveDictationState.Accumulating) return
        transcript.append(text)
        _state.value = LiveDictationState.Accumulating(
            level = ((_state.value as LiveDictationState.Accumulating).level + 0.2f) % 1f,
        )
    }

    /** Live input for this turn: accumulate raw PCM length for the meter (bytes → level hint). */
    fun onAudioLevel(level: Float) {
        if (_state.value is LiveDictationState.Accumulating) {
            _state.value = LiveDictationState.Accumulating(level.coerceIn(0f, 1f))
        }
    }

    /** Turn complete (VAD silence / palm flip / stop tap): normalize + insert, or no-speech error. */
    fun finishAndInsert() {
        if (_state.value !is LiveDictationState.Accumulating) return
        active = false
        inputLifecycle.endInput()
        if (!hasKeyProvider()) {
            _state.value = LiveDictationState.Error(VoiceMessages.KEY_REQUIRED_MESSAGE)
            return
        }
        val text = VoiceMessages.normalizeTranscript(transcript.toString())
        transcript.clear()
        if (text.isEmpty()) {
            _state.value = LiveDictationState.Error(VoiceMessages.NO_SPEECH_MESSAGE)
            return
        }
        _state.value = LiveDictationState.Inserting
        insertIntoFocusedPane(text)
        _state.value = LiveDictationState.Inserted(text)
    }

    /** Cancel mid-utterance: discard accumulated text and return to idle. */
    fun cancel() {
        if (active) inputLifecycle.endInput()
        active = false
        transcript.clear()
        _state.value = LiveDictationState.Idle
    }

    /** Dismiss a terminal [LiveDictationState.Inserted]/[LiveDictationState.Error] back to idle. */
    fun acknowledge() {
        if (_state.value is LiveDictationState.Inserted || _state.value is LiveDictationState.Error) {
            _state.value = LiveDictationState.Idle
        }
    }

    fun release() = cancel()

    private fun insertIntoFocusedPane(text: String) {
        val count = paneCount()
        if (count <= 0) return
        val target = focusedPaneIdProvider().coerceIn(0, count - 1)
        // Auto-submit appends a carriage return (\r, 0x0D) — the byte a real Enter keypress
        // produces (libghostty encodes GhosttyKey.enter as CR). Coding-agent TUIs treat \r as
        // "submit" and a bare \n as "insert newline", so \n typed the text but never ran it.
        val payload = if (autoSubmitProvider()) text + "\r" else text
        sendToPane(target, payload.toByteArray(Charsets.UTF_8))
    }

    companion object {
        /**
         * Handle a dictation-targeted tool call. The Live model has one set of tools; when the
         * user's speech is clearly dictation (not a cockpit command), the model calls
         * [DICTATE_TEXT] and this formats the ack result. Reserved for future model-side routing;
         * today the mic button routes directly (no tool call needed).
         */
        const val DICTATE_TEXT = "dictate_text"

        fun dictateResult(text: String): JsonObject = buildJsonObject {
            put("status", "ok")
            put("message", "Dictated: $text")
        }

        fun lastResultMessage(result: JsonObject): String? =
            result["message"]?.jsonPrimitive?.contentOrNull
                ?: (result["status"] as? JsonPrimitive)?.contentOrNull
    }
}

/** Minimal lifecycle seam between the pane mic button and the shared Gemini Live session. */
interface LiveInputLifecycle {
    fun startInput()
    fun endInput()

    data object None : LiveInputLifecycle {
        override fun startInput() = Unit
        override fun endInput() = Unit
    }
}

/** UI state for Live dictation, mirroring [com.ghosttyxr.app.terminal.voice.VoiceInputState]. */
sealed interface LiveDictationState {
    data object Idle : LiveDictationState
    data class Accumulating(val level: Float = 0f) : LiveDictationState
    data object Inserting : LiveDictationState
    data class Inserted(val text: String) : LiveDictationState
    data class Error(val message: String) : LiveDictationState
}
