package com.ghosttyxr.app.intro

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.annotation.RawRes
import com.ghosttyxr.app.util.GxrLog

private const val TAG = "OnboardingNarration"

/** Plays prerecorded Gemini narration bundled in `res/raw`; no runtime network or synthesis. */
class OnboardingNarrationPlayer(private val context: Context) {
    private var player: MediaPlayer? = null

    fun play(@RawRes resourceId: Int) {
        stop()
        val created = MediaPlayer()
        try {
            created.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            context.resources.openRawResourceFd(resourceId)?.use { descriptor ->
                created.setDataSource(
                    descriptor.fileDescriptor,
                    descriptor.startOffset,
                    descriptor.length,
                )
            } ?: run {
                GxrLog.w(TAG, "Raw resource descriptor not found for onboarding clip.")
                created.release()
                return
            }
            created.prepare()
        } catch (e: Exception) {
            GxrLog.w(TAG, "Failed to initialize MediaPlayer for onboarding clip", e)
            created.release()
            return
        }
        created.setOnCompletionListener { completed ->
            completed.release()
            if (player === completed) player = null
        }
        created.setOnErrorListener { failed, what, extra ->
            GxrLog.w(TAG, "Onboarding playback error what=$what extra=$extra")
            failed.release()
            if (player === failed) player = null
            true
        }
        player = created
        created.start()
    }

    fun stop() {
        player?.let { active ->
            runCatching { if (active.isPlaying) active.stop() }
            runCatching { active.release() }
        }
        player = null
    }

    fun release() = stop()
}
