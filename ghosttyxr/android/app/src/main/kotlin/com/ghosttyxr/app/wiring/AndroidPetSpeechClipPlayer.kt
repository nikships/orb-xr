package com.ghosttyxr.app.wiring

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.annotation.RawRes
import com.ghosttyxr.app.terminal.voice.tts.PetSpeechClipPlayer
import com.ghosttyxr.app.util.GxrLog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "PetSpeechClip"

/**
 * Production [PetSpeechClipPlayer]: plays a bundled `res/raw` orb notice through [MediaPlayer],
 * tagged as assistant speech like the rest of the orb's voice.
 *
 * [spatialize] binds the player to the orb's entity so a prerecorded notice arrives from the orb
 * riding the user's palm, matching streamed cloud speech (see [ScenePointSourceSpatializer] and
 * `SpatialPetSpeechOutputFactory`). It is injected and failure is non-fatal: an unspatialized
 * notice is still a heard notice.
 *
 * Playback volume mirrors the cloud path's attenuation so a notice doesn't jump out at a
 * different level than the orb's other speech.
 */
class AndroidPetSpeechClipPlayer(
    private val context: Context,
    private val spatialize: (MediaPlayer) -> Unit = {},
) : PetSpeechClipPlayer {

    private val lock = Any()
    private var current: MediaPlayer? = null
    private var released = false

    override suspend fun play(@RawRes resourceId: Int) {
        val player = prepare(resourceId)
        try {
            suspendCancellableCoroutine { continuation ->
                player.setOnCompletionListener {
                    if (continuation.isActive) continuation.resume(Unit)
                }
                player.setOnErrorListener { _, what, extra ->
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            IllegalStateException("clip playback error what=$what extra=$extra"),
                        )
                    }
                    true
                }
                continuation.invokeOnCancellation { discard(player) }
                player.start()
            }
        } finally {
            discard(player)
        }
    }

    private fun prepare(@RawRes resourceId: Int): MediaPlayer {
        stop()
        val created = MediaPlayer()
        try {
            check(!released) { "clip player already released" }
            created.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            val descriptor = context.resources.openRawResourceFd(resourceId)
                ?: error("raw clip descriptor unavailable")
            descriptor.use {
                created.setDataSource(it.fileDescriptor, it.startOffset, it.length)
            }
            created.prepare()
            created.setVolume(PLAYBACK_VOLUME, PLAYBACK_VOLUME)
            runCatching { spatialize(created) }
                .onFailure { GxrLog.w(TAG, "clip spatialization unavailable; playing flat", it) }
        } catch (err: Throwable) {
            runCatching { created.release() }
            throw err
        }
        synchronized(lock) { current = created }
        return created
    }

    override fun stop() {
        val active = synchronized(lock) {
            val player = current
            current = null
            player
        }
        active?.let(::stopAndRelease)
    }

    override fun release() {
        synchronized(lock) { released = true }
        stop()
    }

    private fun discard(player: MediaPlayer) {
        val shouldRelease = synchronized(lock) {
            if (current !== player) return
            current = null
            true
        }
        if (shouldRelease) stopAndRelease(player)
    }

    private fun stopAndRelease(player: MediaPlayer) {
        runCatching { if (player.isPlaying) player.stop() }
        runCatching { player.release() }
    }

    private companion object {
        /** Matches the cloud path's attenuation so notices sit at the same level as other speech. */
        const val PLAYBACK_VOLUME = 0.15f
    }
}
