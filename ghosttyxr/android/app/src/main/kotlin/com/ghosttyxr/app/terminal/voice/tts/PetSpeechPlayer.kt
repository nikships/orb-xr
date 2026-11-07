package com.ghosttyxr.app.terminal.voice.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Progressively plays one raw-PCM pet utterance through a streaming [AudioTrack].
 */
class PetSpeechPlayer(
    private val outputFactory: PetSpeechOutputFactory = AndroidPetSpeechOutputFactory,
) {

    @Volatile
    private var output: PetSpeechOutput? = null
    private val stateLock = Any()
    private val assembler = PcmChunkAssembler()
    private var started = false
    private var writtenBytes = 0

    fun start(sampleRateHz: Int) {
        stop()
        check(sampleRateHz > 0) { "Invalid sample rate: $sampleRateHz" }
        synchronized(stateLock) {
            assembler.reset()
            started = false
            writtenBytes = 0
            output = outputFactory.create(sampleRateHz)
        }
    }

    fun write(chunk: ByteArray) {
        synchronized(stateLock) {
            val active = output ?: throw PetTtsException("TTS audio stream was not started.")
            val completeFrames = assembler.append(chunk)
            if (completeFrames.isEmpty()) return
            var offset = 0
            if (!started) {
                val primeBytes = primingBytes(active.sampleRateHz) - writtenBytes
                if (primeBytes > 0) {
                    val size = minOf(primeBytes, completeFrames.size)
                    writeFully(active, completeFrames, offset, size)
                    offset += size
                    writtenBytes += size
                }
            }
            if (!started && writtenBytes >= primingBytes(active.sampleRateHz)) {
                active.play()
                started = true
            }
            if (offset < completeFrames.size) {
                writeFully(active, completeFrames, offset, completeFrames.size - offset)
                writtenBytes += completeFrames.size - offset
            }
        }
    }

    suspend fun finish() = withContext(Dispatchers.IO) {
        val current = synchronized(stateLock) { output } ?: return@withContext
        try {
            val totalFrames = synchronized(stateLock) {
                if (output !== current) return@withContext
                assembler.finish()
                if (writtenBytes == 0) throw PetTtsException("No audio in TTS stream.")
                if (!started) {
                    current.play()
                    started = true
                }
                writtenBytes / BYTES_PER_FRAME
            }
            while (isActive) {
                val stillPlaying = synchronized(stateLock) {
                    output === current &&
                        current.isPlaying &&
                        current.playbackHeadPosition < totalFrames
                }
                if (!stillPlaying) break
                delay(POLL_MS)
            }
        } finally {
            release(current)
        }
    }

    fun stop() {
        val current = synchronized(stateLock) {
            val active = output
            output = null
            assembler.reset()
            started = false
            writtenBytes = 0
            active
        }
        current?.let(::stopAndRelease)
    }

    private fun release(current: PetSpeechOutput) {
        val shouldRelease = synchronized(stateLock) {
            if (output !== current) return
            output = null
            true
        }
        if (shouldRelease) stopAndRelease(current)
    }

    private fun stopAndRelease(current: PetSpeechOutput) {
        runCatching { current.stop() }
        runCatching { current.release() }
    }

    private fun writeFully(
        current: PetSpeechOutput,
        bytes: ByteArray,
        start: Int,
        size: Int,
    ) {
        var offset = start
        val end = start + size
        while (offset < end) {
            val written = current.write(bytes, offset, end - offset)
            if (written <= 0) throw PetTtsException("AudioTrack write failed ($written).")
            offset += written
        }
    }

    private fun primingBytes(sampleRateHz: Int): Int =
        max(BYTES_PER_FRAME, sampleRateHz * BYTES_PER_FRAME * PRIME_MS / 1_000)

    private companion object {
        const val BYTES_PER_FRAME = 2
        const val PRIME_MS = 75
        const val POLL_MS = 25L
    }
}

interface PetSpeechOutput {
    val sampleRateHz: Int
    val isPlaying: Boolean
    val playbackHeadPosition: Int

    fun write(bytes: ByteArray, offset: Int, size: Int): Int
    fun play()
    fun stop()
    fun release()
}

fun interface PetSpeechOutputFactory {
    fun create(sampleRateHz: Int): PetSpeechOutput
}

/**
 * The production factory: a streaming mono 16-bit PCM [AudioTrack] tagged as assistant speech.
 *
 * Public (not private) so the XR layer can decorate it with
 * [SpatialPetSpeechOutputFactory] to make the pet's voice emanate from the pet's entity.
 */
object AndroidPetSpeechOutputFactory : PetSpeechOutputFactory {
    override fun create(sampleRateHz: Int): PetSpeechOutput {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) throw PetTtsException("AudioTrack buffer unavailable ($minBuffer).")
        val track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRateHz)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            max(minBuffer, sampleRateHz / 5 * BYTES_PER_FRAME),
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw PetTtsException("AudioTrack init failed (state=${track.state}).")
        }
        // Gemini Flash TTS arrives near full digital amplitude and plays much louder than the
        // prerecorded onboarding narration clips; attenuate so confirmations sit at a similar level.
        track.setVolume(PLAYBACK_VOLUME)
        return AndroidPetSpeechOutput(track, sampleRateHz)
    }

    private const val BYTES_PER_FRAME = 2

    /**
     * Linear gain applied to pet TTS relative to full scale (0 = mute, 1 = full).
     * Kept well below onboarding narration: Gemini Flash PCM is near full-scale and
     * USAGE_ASSISTANT routes hot on the headset.
     */
    private const val PLAYBACK_VOLUME = 0.15f
}

private class AndroidPetSpeechOutput(
    private val track: AudioTrack,
    override val sampleRateHz: Int,
) : PetSpeechOutput, AudioTrackBackedOutput {
    override val audioTrack: AudioTrack
        get() = track

    override val isPlaying: Boolean
        get() = track.playState == AudioTrack.PLAYSTATE_PLAYING

    override val playbackHeadPosition: Int
        get() = track.playbackHeadPosition

    override fun write(bytes: ByteArray, offset: Int, size: Int): Int =
        track.write(bytes, offset, size)

    override fun play() {
        track.play()
    }

    override fun stop() {
        track.stop()
    }

    override fun release() {
        track.release()
    }
}
