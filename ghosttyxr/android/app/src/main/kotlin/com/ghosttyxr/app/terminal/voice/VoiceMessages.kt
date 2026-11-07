package com.ghosttyxr.app.terminal.voice

/**
 * Shared voice constants + transcript normalization for the Live voice path (commands +
 * dictation). Split out of the old batch-STT state machine so user strings stay byte-identical
 * and newline-collapsing rules are shared without depending on deleted code.
 */
object VoiceMessages {
    /** Shown when a free-tier user taps a voice affordance. */
    const val PRO_REQUIRED_MESSAGE = "Voice features need Orb XR Pro — upgrade in Settings."

    /**
     * Shown when a turn ends with no usable speech (empty buffer or blank transcript).
     * The orb speaks this one from a bundled clip rather than cloud TTS — see
     * [com.ghosttyxr.app.terminal.voice.tts.PrebakedPetSpeechSynthesizer]. Matching is by
     * exact string, so every no-speech path must use this constant.
     */
    const val NO_SPEECH_MESSAGE = "Didn't catch that — try again."

    /**
     * Strip newlines/carriage returns so a multi-line transcription can't accidentally
     * submit a command — only the explicit auto-submit path appends a single "\r". Inner
     * line breaks collapse to spaces; surrounding whitespace is trimmed.
     */
    fun normalizeTranscript(raw: String): String =
        raw.lineSequence()
            .joinToString(" ") { it.trim() }
            .trim()
            .replace(MULTIPLE_SPACES, " ")

    private val MULTIPLE_SPACES = Regex(" {2,}")
}
