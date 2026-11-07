package com.ghosttyxr.app.terminal.voice.command

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/**
 * Continuous mic streamer for the Gemini Live voice path. Unlike [AudioCaptureController]
 * (record-one-utterance → finished buffer for batch STT), this emits fixed-size 16 kHz mono
 * 16-bit PCM chunks while running so the adapter can forward them with
 * `LiveSession.sendAudioRealtime`. Encoding matches the Live API input format
 * (`audio/pcm;rate=16000`, little-endian).
 *
 * Lifecycle: [start] opens the mic and begins emitting on [chunks]; [setPaused] gates emission
 * (used for echo suppression while model audio plays) without releasing the mic; [stop]
 * releases everything. [amplitude] drives the pet's listening pulse at ~25 Hz.
 *
 * Android-only (AudioRecord); the Live turn logic in [LiveCommandEngine] stays host-JVM testable.
 */
class LiveAudioStreamer {

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    /** Emissions are hot: collectors receive chunks only while [start]ed and unpaused. */
    private val _chunks = MutableStateFlow<ByteArray?>(null)

    @Volatile
    private var running = false

    @Volatile
    private var paused = false

    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private val lock = Any()

    val isRunning: Boolean get() = running

    /**
     * Begin streaming. [onChunk] is invoked on the capture thread for every [CHUNK_BYTES] of
     * PCM. Throws [IllegalStateException] when the mic can't initialise so the session can
     * surface a clear error. RECORD_AUDIO is gated upstream (same as the old tap-to-talk path).
     */
    @SuppressLint("MissingPermission")
    fun start(onChunk: (ByteArray) -> Unit) {
        if (running) return
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBuffer != AudioRecord.ERROR && minBuffer != AudioRecord.ERROR_BAD_VALUE) {
            "Microphone unavailable on this device."
        }
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer.coerceAtLeast(CHUNK_BYTES * 4) * 2,
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            "Couldn't start the microphone."
        }
        _amplitude.value = 0f
        paused = false
        record = recorder
        running = true
        recorder.startRecording()
        thread = Thread({ captureLoop(recorder, onChunk) }, "live-voice-capture").apply { start() }
    }

    /** Gate chunk emission without releasing the mic (echo suppression during model speech). */
    fun setPaused(paused: Boolean) {
        this.paused = paused
        if (paused) _amplitude.value = 0f
    }

    /** Stop streaming and release the mic. Idempotent. */
    fun stop() {
        running = false
        thread?.let { runCatching { it.join(JOIN_TIMEOUT_MS) } }
        thread = null
        val recorder = synchronized(lock) {
            val current = record
            record = null
            current
        }
        if (recorder != null) {
            runCatching {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            }
            runCatching { recorder.release() }
        }
        _amplitude.value = 0f
    }

    private fun captureLoop(recorder: AudioRecord, onChunk: (ByteArray) -> Unit) {
        val buffer = ByteArray(CHUNK_BYTES)
        var lastMeterMs = 0L
        try {
            while (running) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                if (!paused) {
                    onChunk(buffer.copyOf(read))
                    val now = System.currentTimeMillis()
                    if (now - lastMeterMs >= METER_INTERVAL_MS) {
                        lastMeterMs = now
                        _amplitude.value = rms(buffer, read)
                    }
                }
            }
        } catch (_: Throwable) {
            // Teardown races release; stop() owns the error surface.
        }
    }

    private fun rms(bytes: ByteArray, length: Int): Float {
        if (length < 2) return 0f
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < length) {
            val sample = (bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8)
            val value = sample.toDouble()
            sum += value * value
            count++
            i += 2
        }
        if (count == 0) return 0f
        return (sqrt(sum / count) / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
    }

    companion object {
        /** Live API input format: raw 16-bit PCM at 16 kHz, mono. */
        const val SAMPLE_RATE_HZ = 16_000

        /** ~100 ms per chunk at 16 kHz mono 16-bit: low latency without per-packet overhead. */
        const val CHUNK_BYTES = 3_200

        private const val METER_INTERVAL_MS = 40L
        private const val JOIN_TIMEOUT_MS = 500L
    }
}
