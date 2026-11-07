package com.ghosttyxr.app.terminal.voice.tts

/**
 * Marker for TTS-audio failures. The `petTts` Cloud Function client that defined this is deleted
 * (Gemini Live audio replaced it); [PetSpeechPlayer] and [PcmChunkAssembler] still throw it for
 * local playback faults, so the type stays until those call sites migrate to a Live-era error.
 */
class PetTtsException(message: String, cause: Throwable? = null) : Exception(message, cause)
