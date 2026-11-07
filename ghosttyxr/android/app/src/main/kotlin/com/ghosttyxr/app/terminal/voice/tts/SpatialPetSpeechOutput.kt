package com.ghosttyxr.app.terminal.voice.tts

import android.media.AudioTrack
import androidx.xr.runtime.Session
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.PointSourceParams
import androidx.xr.scenecore.SpatialAudioTrack
import com.ghosttyxr.app.util.GxrLog

private const val TAG = "PetSpeechSpatial"

/**
 * Binds the pet's TTS [AudioTrack] to a point in the scene graph so its voice comes *from the pet*
 * rather than from everywhere at once.
 *
 * The pet is a palm-riding companion whose panel is repositioned onto the hand every tracked frame
 * (`HandMenuController` → `HandMenuPlacement` → `VoicePetSurface.setPose`). Its speech, though, was
 * plain non-spatial mono: the orb bobbed over your palm while its voice sat flat in the middle of
 * your head. Binding the track to the pet's entity closes that gap — and because SceneCore tracks
 * the entity's live pose rather than a fixed point, the voice follows the orb for free, including the
 * idle bob and sway.
 *
 * Why this seam: spatialization applies to the `AudioTrack` *instance*, and [PetSpeechOutputFactory]
 * is the one place that instance is created. Decorating the factory leaves [PetSpeechPlayer]'s
 * streaming/priming logic and every existing test path untouched.
 *
 * Failure is always non-fatal. Point-source spatialization only means anything on a device with a
 * live spatializer; on the emulator, on optical see-through glasses, or if the call throws, we log
 * once and fall through to the unspatialized track. A talking pet in mono beats a silent pet.
 *
 * @param delegate the real output factory (production: [AndroidPetSpeechOutputFactory]).
 * @param spatializer applies spatialization to a track. Injected because `Session`/`Entity` are final
 *   SDK types that can't be constructed on the host JVM — production passes
 *   [ScenePointSourceSpatializer], tests pass a fake.
 */
class SpatialPetSpeechOutputFactory(
    private val delegate: PetSpeechOutputFactory,
    private val spatializer: PetSpeechSpatializer,
) : PetSpeechOutputFactory {

    override fun create(sampleRateHz: Int): PetSpeechOutput {
        val output = delegate.create(sampleRateHz)
        // A fake or future non-AudioTrack backend has nothing to spatialize: hand it back as-is
        // rather than forcing every implementation to carry a track it doesn't own.
        val track = (output as? AudioTrackBackedOutput)?.audioTrack ?: return output
        runCatching { spatializer.spatialize(track) }
            .onFailure {
                GxrLog.w(TAG, "point-source spatialization unavailable; pet speaks unspatialized", it)
            }
        return output
    }
}

/** Applies spatialization to one [AudioTrack]. Injected so the factory stays host-JVM testable. */
fun interface PetSpeechSpatializer {
    fun spatialize(track: AudioTrack)
}

/**
 * Production [PetSpeechSpatializer]: point-source spatialization anchored to [entity], so the audio
 * originates wherever that entity currently is in the scene.
 */
class ScenePointSourceSpatializer(
    private val session: Session,
    private val entity: Entity,
) : PetSpeechSpatializer {
    override fun spatialize(track: AudioTrack) {
        SpatialAudioTrack.setPointSourceParams(
            session = session,
            track = track,
            params = PointSourceParams(),
            entity = entity,
        )
    }
}

/**
 * Exposes the underlying [AudioTrack] of a [PetSpeechOutput] so a decorator can spatialize it.
 *
 * Deliberately narrow: only the production Android output implements it, so wrapping a test fake is
 * a no-op instead of a crash or a mandatory stub on the [PetSpeechOutput] interface.
 */
interface AudioTrackBackedOutput {
    val audioTrack: AudioTrack
}
