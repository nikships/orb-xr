package com.ghosttyxr.app.terminal.spatial

import android.content.Context
import androidx.xr.runtime.Session
import androidx.xr.scenecore.PointSourceParams
import androidx.xr.scenecore.SoundEffect
import androidx.xr.scenecore.SoundEffectPool
import androidx.xr.scenecore.SoundEffectPoolComponent
import com.ghosttyxr.app.R
import com.ghosttyxr.app.util.GxrLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

private const val TAG = "PaneBellPlayer"

/**
 * Plays terminal-bell chimes (`\a` / completion chimes) through a per-pane
 * [SoundEffectPoolComponent] whose output the spatial layer attaches to that pane's
 * `SpatialPanel` via `SubspaceModifier.spatializedAudioOutput(...)`, so the chime
 * emits from the pane's own 3D position instead of the headset's stereo mix.
 *
 * The component is created here (it needs the XR [Session] + a loaded [SoundEffect],
 * neither of which the grid's layout code should touch) and handed to [SpatialPaneGrid]
 * as a `SoundEffectPoolComponent?` per pane; the grid only calls the compose-side
 * `asSpatializedAudioOutput()` adapter and attaches the modifier.
 *
 * Each emission on [bellEvents] is the coalesced chime count for one snapshot tick
 * (see `BellChimePolicy`) — one [play] per count. A null session (Flat/HomeSpace mode
 * has no spatial stage) or a failed load degrades to silent: bells must never crash
 * the cockpit or spam Logcat.
 */
class PaneBellPlayer(
    context: Context,
    session: Session?,
    bellEvents: SharedFlow<Int>,
    maxStreams: Int = MAX_STREAMS,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loaded: LoadedChime? = loadChime(context, session, maxStreams)

    /** Attach this to the pane's `SpatialPanel`; null when spatial audio is unavailable. */
    val component: SoundEffectPoolComponent? get() = loaded?.component

    init {
        if (loaded != null) {
            scope.launch {
                bellEvents.collect { chimes ->
                    repeat(chimes.coerceAtLeast(0)) { playOnce() }
                }
            }
        }
    }

    private fun playOnce() {
        val loaded = loaded ?: return
        runCatching {
            loaded.component.play(loaded.effect, VOLUME, PRIORITY, false)
        }.onFailure { e ->
            GxrLog.w(TAG, "bell chime play failed: ${e.message}")
        }
    }

    fun release() {
        scope.cancel()
        loaded?.let { loaded ->
            runCatching { loaded.pool.release() }
                .onFailure { e -> GxrLog.w(TAG, "bell pool release failed: ${e.message}") }
        }
        loaded = null
    }

    private data class LoadedChime(
        val pool: SoundEffectPool,
        val effect: SoundEffect,
        val component: SoundEffectPoolComponent,
    )

    companion object {
        const val MAX_STREAMS = 4
        const val VOLUME = 1f
        const val PRIORITY = 0

        private fun loadChime(
            context: Context,
            session: Session?,
            maxStreams: Int,
        ): LoadedChime? {
            if (session == null) return null
            return runCatching {
                val pool = SoundEffectPool.create(session, maxStreams)
                val effect = pool.load(context.applicationContext, R.raw.bell_chime)
                val component = SoundEffectPoolComponent.create(session, pool, PointSourceParams())
                LoadedChime(pool, effect, component)
            }.getOrElse { e ->
                GxrLog.w(TAG, "spatial bell audio unavailable: ${e.message}")
                null
            }
        }
    }
}
