package com.ghosttyxr.app.terminal.voice.command

/**
 * UI state for **voice command** mode (cockpit control), distinct from the tap-to-talk
 * dictation state in [com.ghosttyxr.app.terminal.voice.VoiceInputState].
 *
 * A command turn is **tap-to-talk**: record one utterance, transcribe it with cloud STT, then hand
 * the text to `gemini-3.5-flash` with function calling ([CommandFunctions]); the model's chosen
 * function runs against the cockpit and the model's short text reply is shown back. The flow:
 *
 *   [Idle] --tap--> [Listening] --tap--> [Transcribing] --> [Thinking]
 *     ↳ command runs → [Executing] → [Result] → [Idle]
 *     ↳ STT/model failure → [Error] → [Idle]
 *
 * Mis-heard commands (bad pane index, unknown function) do **not** enter [Error]: they return a
 * structured error to the model, which surfaces it as a [Result] message. [Error] is reserved for
 * transcription / model-call faults.
 *
 * There is no confirmation state: every exposed command — including close-terminal — runs
 * immediately. Closing the last remaining terminal is rejected by the controller instead.
 */
sealed interface VoiceCommandState {
    /** Voice command mode is off / waiting for a tap; no turn in flight. */
    data object Idle : VoiceCommandState

    /** Recording the spoken command (mic open). */
    data object Listening : VoiceCommandState

    /** Audio captured; cloud STT transcription is in flight. */
    data object Transcribing : VoiceCommandState

    /** Transcript sent to Gemini; waiting for it to choose and run a function. */
    data object Thinking : VoiceCommandState

    /** A command was just run. [label] is a short human description shown briefly. */
    data class Executing(val label: String) : VoiceCommandState

    /** The turn finished; [message] is the model's short text reply, shown before returning to idle. */
    data class Result(val message: String) : VoiceCommandState

    /** Transcription / model-call failure. [message] is user-facing. */
    data class Error(val message: String) : VoiceCommandState
}
