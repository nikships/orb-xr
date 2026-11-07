package com.ghosttyxr.app.wiring

import android.os.SystemClock
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.xr.runtime.Session
import androidx.xr.runtime.math.FloatSize2d
import androidx.xr.runtime.math.IntSize2d
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.ExperimentalSurfaceEntityPixelDimensionsApi
import androidx.xr.scenecore.InputEvent
import androidx.xr.scenecore.InteractableComponent
import androidx.xr.scenecore.PanelEntity
import androidx.xr.scenecore.PointSourceParams
import androidx.xr.scenecore.SpatialMediaPlayer
import androidx.xr.scenecore.SurfaceEntity
import androidx.xr.scenecore.scene
import com.ghosttyxr.app.terminal.hands.AgentOrbConfig
import com.ghosttyxr.app.terminal.hands.OrbBodyDynamics
import com.ghosttyxr.app.terminal.hands.OrbEyeProjection
import com.ghosttyxr.app.terminal.hands.SpatialVoicePetSurface
import com.ghosttyxr.app.terminal.hands.VoicePetSurface
import com.ghosttyxr.app.terminal.settings.GhosttySettingsStore
import com.ghosttyxr.app.terminal.theme.GhosttyTokens
import com.ghosttyxr.app.terminal.theme.GhosttyXRTheme
import com.ghosttyxr.app.terminal.voice.command.VoiceCommandState
import com.ghosttyxr.app.terminal.voice.tts.AndroidPetSpeechOutputFactory
import com.ghosttyxr.app.terminal.voice.tts.PetSpeechCoordinator
import com.ghosttyxr.app.terminal.voice.tts.PetSpeechPlayer
import com.ghosttyxr.app.terminal.voice.tts.PrebakedPetSpeechSynthesizer
import com.ghosttyxr.app.terminal.voice.tts.ScenePointSourceSpatializer
import com.ghosttyxr.app.terminal.voice.tts.SilentSpeechSynthesizer
import com.ghosttyxr.app.terminal.voice.tts.SpatialPetSpeechOutputFactory
import com.ghosttyxr.app.ui.chrome.GxrPanel
import com.ghosttyxr.app.ui.chrome.GxrText
import com.ghosttyxr.app.util.GxrLog
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val TAG = "VoicePet"

/**
 * Builds the production [SpatialVoicePetSurface]: a stereo [SurfaceEntity] hosting
 * [AgentOrbRenderer], a transient Result/Error toast panel, and the spatialized Live PCM sink.
 *
 * Entities are pose-driven directly in activity space — the documented way to attach content to a
 * hand joint (a Compose `SpatialPanel` can't be placed at the palm's activity-space pose; see
 * `HandMenuController`/`HandMenuPlacement`).
 *
 * **Why a `SurfaceEntity` and not a `PanelEntity`.** A panel is monoscopic: the runtime shows both
 * eyes the same image. However good the shading is, zero disparity means the visual system
 * correctly concludes it is looking at a flat card, which is exactly why the pet used to read as a
 * picture of a sphere rather than a ball in your hand. A `SurfaceEntity` in
 * [SurfaceEntity.StereoMode.SIDE_BY_SIDE] lets us render the two halves from the viewer's two real
 * eye positions, so the orb finally has depth.
 *
 * The pet is a companion, not a status dashboard: it plays a continuous procedural idle motion
 * (bob + micro-sway + breathing scale) whenever visible, now filtered through [OrbBodyDynamics] so
 * it trails, leans, and squashes instead of being welded to the palm joint. Selecting (pinching)
 * the pet fires [onSelect] (opens Settings); flipping the palm down is handled upstream by the
 * controller.
 *
 * All entities parent to `activitySpace` (required since scenecore alpha15 defaults `parent` to
 * null) and start hidden. [VoicePetSurface.show]'s reveal animation runs a short scale/rise pop
 * and fires `onRevealed` exactly once when finished — unless [VoicePetSurface.hide] cancels it
 * first (palm flipped back down mid-reveal), in which case the mic must never open.
 */
@OptIn(ExperimentalSurfaceEntityPixelDimensionsApi::class)
fun buildVoicePetSurface(
    activity: ComponentActivity,
    session: Session,
    scope: CoroutineScope,
    orbConfig: AgentOrbConfig,
    voiceState: StateFlow<VoiceCommandState>,
    onSelect: () -> Unit,
): SpatialVoicePetSurface {
    // Size the quad off the legacy panel so the ball keeps exactly the physical size users know,
    // then add headroom for the halo, the mote orbits, and off-axis viewing: the quad is a window,
    // and anything that would fall outside it gets its silhouette clipped when you look from a
    // steep angle.
    val density = activity.resources.displayMetrics.density
    val legacyPanelSideM = session.scene.virtualPixelDensity
        .convertPixelsToMeters(PET_PANEL_DP * density)
    val orbRadiusM = legacyPanelSideM * ORB_RADIUS_FRACTION
    val quadHalfExtentM = orbRadiusM * QUAD_HEADROOM
    val quadSideM = quadHalfExtentM * 2f

    val stereo = runCatching { createPetSurfaceEntity(session, quadSideM, stereo = true) }
        .recoverCatching {
            GxrLog.w(TAG, "side-by-side pet surface unavailable; falling back to monoscopic", it)
            createPetSurfaceEntity(session, quadSideM, stereo = false)
        }
        .getOrThrow()
    val petEntity = stereo.entity
    runCatching {
        petEntity.setSurfacePixelDimensions(IntSize2d(SURFACE_WIDTH_PX, SURFACE_HEIGHT_PX))
    }.onFailure { GxrLog.w(TAG, "could not fix pet surface pixel size; using runtime default", it) }

    val renderer = AgentOrbRenderer(
        resources = activity.resources,
        config = orbConfig,
        quadHalfExtent = quadHalfExtentM,
        orbRadius = orbRadiusM,
    ).apply { attach(petEntity.getSurface(), SURFACE_WIDTH_PX, SURFACE_HEIGHT_PX) }
    val eyeTracker = if (stereo.isStereo) OrbEyeTracker(session) else null

    // Pinch/raycast select on the pet opens Settings. DOWN (not UP) so a quick pinch registers
    // even if the release lands off the mesh.
    val interactable = runCatching {
        InteractableComponent.create(session, ContextCompat.getMainExecutor(activity)) { event ->
            if (event.action == InputEvent.Action.DOWN) onSelect()
        }.also { component ->
            check(petEntity.addComponent(component)) { "addComponent returned false" }
        }
    }.onFailure {
        // Rare/alpha API risk: without the component the pet still works for voice; settings
        // remain reachable via the control-panel gear and the pet ring's log points here.
        GxrLog.w(TAG, "InteractableComponent unavailable; pet select-to-settings disabled", it)
    }.getOrNull()

    // Spoken voice (issue #55): the Live model's own speech, streamed as 24 kHz PCM into a
    // PetSpeechPlayer on a point-source spatialized factory, so the voice arrives from wherever
    // the orb actually is — riding the palm, including the idle bob and sway, because SceneCore
    // follows the entity's live pose rather than a fixed point. Degrades to plain mono on
    // devices without a spatializer (see [SpatialPetSpeechOutputFactory]). Additive — failures
    // log and the toast stands alone.
    //
    // Speech ownership: the session's PlayerLiveSpeechOutput on this spatialized factory plays
    // model audio, while the engine event path exclusively owns transcript-without-audio
    // fallback. PetSpeechCoordinator is deliberately limited to the fixed bundled no-speech
    // notice; anything without a clip is toast-only (SilentSpeechSynthesizer).
    //
    // The old `petTts` Cloud Function path (CloudPetSpeechSynthesizer → PetTtsClient) is deleted:
    // Live audio replaced it outright.
    val spatialSpeechFactory = SpatialPetSpeechOutputFactory(
        delegate = AndroidPetSpeechOutputFactory,
        spatializer = ScenePointSourceSpatializer(
            session = session,
            entity = petEntity,
        ),
    )
    val liveSpeech = com.ghosttyxr.app.terminal.voice.command.PlayerLiveSpeechOutput(
        player = PetSpeechPlayer(outputFactory = spatialSpeechFactory),
    )
    val speech = PetSpeechCoordinator(
        scope = scope,
        voiceState = voiceState,
        synthesizer = PrebakedPetSpeechSynthesizer(
            // Only the exact fixed no-speech notice reaches this coordinator. Other text is
            // toast-only here; Live model audio and event fallback have separate owners.
            delegate = SilentSpeechSynthesizer(),
            clipPlayer = AndroidPetSpeechClipPlayer(
                context = activity,
                spatialize = { player ->
                    SpatialMediaPlayer.setPointSourceParams(
                        session = session,
                        mediaPlayer = player,
                        params = PointSourceParams(),
                        entity = petEntity,
                    )
                },
            ),
        ),
        enabled = { GhosttySettingsStore.petSpeechEnabled.value },
    ).also { it.start() }

    val toastView = petComposeView(activity) { VoiceCommandToast(voiceState) }
    val toastEntity = PanelEntity.create(
        session = session,
        view = toastView,
        pixelDimensions = toastView.pixelSize(TOAST_WIDTH_DP, TOAST_HEIGHT_DP),
        name = "ghosttyxr-voice-pet-toast",
    ).apply {
        parent = session.scene.activitySpace
        setEnabled(false)
    }

    return object : SpatialVoicePetSurface,
        com.ghosttyxr.app.terminal.voice.command.LiveSpeechOutput by liveSpeech {
        private var basePose = Pose.Identity

        /** Bumped on every show/hide so a stale reveal animation can never fire its callback. */
        private var generation = 0
        private var visible = false
        private var animationJob: Job? = null
        private var voiceToastJob: Job? = null
        private var toastDisplayJob: Job? = null
        private val dynamics = OrbBodyDynamics()

        init {
            // Result/Error land after the palm flip hides the pet (flip → transcribe → think →
            // execute). Surface them as a transient toast at the pet's last pose — where the
            // user's eyes just were. The pose was billboarded by HandMenuPlacement, so the toast
            // faces the user for free.
            voiceToastJob = scope.launch {
                voiceState.collect { state ->
                    val isTerminal = state is VoiceCommandState.Result || state is VoiceCommandState.Error
                    if (isTerminal && !visible) {
                        showToast(stillCurrent = { voiceState.value == state })
                    }
                }
            }
        }

        private fun CoroutineScope.showToast(
            stillCurrent: () -> Boolean,
        ) {
            toastEntity.setPose(basePose)
            toastEntity.setEnabled(true)
            toastDisplayJob?.cancel()
            toastDisplayJob = launch {
                delay(TOAST_VISIBLE_MS)
                if (stillCurrent()) {
                    toastEntity.setEnabled(false)
                }
            }
        }

        override fun show(onRevealed: () -> Unit) {
            val gen = ++generation
            visible = true
            toastEntity.setEnabled(false)
            petEntity.setEnabled(true)
            dynamics.reset()
            renderer.start()
            animationJob?.cancel()
            animationJob = scope.launch {
                var t = 0L
                var lastNanos = SystemClock.elapsedRealtimeNanos()
                // Reveal pop: scale 0 → 1 with a slight rise over REVEAL_MS.
                while (t < REVEAL_MS) {
                    val f = t / REVEAL_MS.toFloat()
                    lastNanos = applyIdle(t, 0.01f + 0.99f * ease(f), lastNanos)
                    delay(FRAME_MS)
                    t += FRAME_MS
                }
                if (gen != generation) return@launch
                onRevealed()
                // Continuous idle: bob + sway + breathing, until hidden.
                while (gen == generation) {
                    lastNanos = applyIdle(t, 1f, lastNanos)
                    delay(FRAME_MS)
                    t += FRAME_MS
                }
            }
        }

        override fun hide() {
            if (!visible && animationJob == null) return
            val gen = ++generation
            visible = false
            animationJob?.cancel()
            animationJob = scope.launch {
                // Brief "wrapping up" beat: a squash-and-shrink, then gone.
                var t = 0L
                while (t < WRAP_UP_MS) {
                    val f = t / WRAP_UP_MS.toFloat()
                    val scale = (1f + 0.1f * sin(f * Math.PI).toFloat()) * (1f - ease(f)) + 0.01f
                    petEntity.setScale(PET_SCALE * scale)
                    delay(FRAME_MS)
                    t += FRAME_MS
                }
                if (gen != generation) return@launch
                petEntity.setEnabled(false)
                renderer.stop()
            }
        }

        override fun setPose(pose: Pose) {
            basePose = pose
        }

        override fun dispose() {
            generation++
            speech.dispose()
            liveSpeech.stop()
            renderer.release()
            animationJob?.cancel()
            voiceToastJob?.cancel()
            toastDisplayJob?.cancel()
            interactable?.let { runCatching { petEntity.removeComponent(it) } }
            // scenecore alpha15 removed Entity.dispose(): detach from the scene graph and drop
            // the compositions; the runtime reclaims the entities once unreferenced.
            petEntity.parent = null
            toastEntity.parent = null
            toastView.disposeComposition()
        }

        /**
         * One idle-motion frame: vertical bob + yaw micro-sway on the cached palm pose, plus a
         * breathing scale. The two sine periods are incommensurate so the motion never reads as
         * a short loop. Amplitudes are small (±1 cm, ±3°) — alive, not distracting.
         *
         * The spring layer then decides where the body actually sits: it trails the palm, leans
         * into travel, and squashes under acceleration. Finally the viewer's two eyes are resolved
         * and handed to the renderer, all from the same pose, so the stereo can never disagree with
         * where the quad ended up.
         *
         * @return the timestamp to pass back in as `lastNanos` next frame.
         */
        private fun applyIdle(elapsedMs: Long, revealScale: Float, lastNanos: Long): Long {
            val now = SystemClock.elapsedRealtimeNanos()
            val deltaSeconds = ((now - lastNanos) / NANOS_PER_SECOND).toFloat()
            val t = elapsedMs / 1000f
            val bob = BOB_AMPLITUDE_M * sin(2f * Math.PI.toFloat() * t / BOB_PERIOD_S)
            val yaw = SWAY_AMPLITUDE_DEG * sin(2f * Math.PI.toFloat() * t / SWAY_PERIOD_S)
            val breath = 1f + BREATH_AMPLITUDE * sin(2f * Math.PI.toFloat() * t / BREATH_PERIOD_S)
            val target = basePose.translation + Vector3.Up * (PET_VERTICAL_OFFSET_M + bob)
            val body = dynamics.update(target, deltaSeconds)

            val rotation = basePose.rotation * Quaternion.fromAxisAngle(Vector3.Up, yaw)
            val pose = Pose(target + body.offset, rotation)
            val scale = PET_SCALE * revealScale * breath
            petEntity.setPose(pose)
            petEntity.setScale(scale)

            // The dynamics work in activity space; the renderer works in the quad's local space.
            // A rotation moves between the two by conjugation, a direction by a single rotate.
            val inverse = rotation.inverse
            val eyes = eyeTracker?.eyes()
            val left = eyes?.let { OrbEyeProjection.eyeInQuadLocal(pose, scale, it.left) }
            val right = eyes?.let { OrbEyeProjection.eyeInQuadLocal(pose, scale, it.right) }
            val fallback = Vector3(0f, 0f, NOMINAL_VIEW_DISTANCE_M / scale)
            renderer.submit(
                OrbFrame(
                    leftEye = left ?: fallback,
                    rightEye = right ?: fallback,
                    lean = inverse * body.lean * rotation,
                    stretchAxis = inverse * body.stretchAxis,
                    stretch = body.stretch,
                    energy = body.energy,
                ),
            )
            return now
        }
    }
}

/** A [SurfaceEntity] carrying the orb, side-by-side when the runtime allows it. */
private class PetSurface(val entity: SurfaceEntity, val isStereo: Boolean)

/**
 * Rounded, feathered orb quad.
 *
 * The orb renders an opaque black body on a transparent clear, so the quad's own
 * silhouette matters: sharp square corners would print a visible card edge against
 * the passthrough room wherever the ball doesn't cover the quad (steep viewing
 * angles, reveal/wrap scale beats). [cornerRadius] rounds the geometry and
 * [RectangleFeather] fades the alpha at the rim, so the window the orb floats in
 * dissolves instead of ending in a line.
 *
 * Bounds (enforced by scenecore, pinned here as constants so tests cover them):
 * - radius <= half the smaller quad side ([PET_CORNER_RADIUS_M] is far inside it)
 * - feather radii in 0.0..0.5 (fractions of the canvas, not metres)
 */
internal const val PET_CORNER_RADIUS_M = 0.035f
internal const val PET_FEATHER_LEFT_RIGHT = 0.06f
internal const val PET_FEATHER_TOP_BOTTOM = 0.06f

internal fun petQuadShape(quadSideM: Float): SurfaceEntity.Shape.Quad =
    SurfaceEntity.Shape.Quad(
        FloatSize2d(quadSideM, quadSideM),
        cornerRadius = PET_CORNER_RADIUS_M.coerceAtMost(quadSideM / 2f),
    )

internal fun petEdgeFeathering(): SurfaceEntity.EdgeFeatheringParams.RectangleFeather =
    SurfaceEntity.EdgeFeatheringParams.RectangleFeather(
        leftRight = PET_FEATHER_LEFT_RIGHT,
        topBottom = PET_FEATHER_TOP_BOTTOM,
    )

private fun createPetSurfaceEntity(
    session: Session,
    quadSideM: Float,
    stereo: Boolean,
): PetSurface {
    val entity = SurfaceEntity.create(
        session = session,
        pose = Pose.Identity,
        shape = petQuadShape(quadSideM),
        stereoMode = if (stereo) {
            SurfaceEntity.StereoMode.SIDE_BY_SIDE
        } else {
            SurfaceEntity.StereoMode.MONO
        },
        superSampling = SurfaceEntity.SuperSampling.PENTAGON,
        parent = session.scene.activitySpace,
    ).apply {
        setEnabled(false)
        runCatching { edgeFeatheringParams = petEdgeFeathering() }
            .onFailure { GxrLog.w(TAG, "pet edge feathering unavailable; quad keeps hard edges", it) }
    }
    return PetSurface(entity, stereo)
}

/** A ComposeView wired with the Activity as its ViewTree owners, running [content] in theme. */
private fun petComposeView(
    activity: ComponentActivity,
    content: @Composable () -> Unit,
): ComposeView = ComposeView(activity).apply {
    setViewTreeLifecycleOwner(activity)
    setViewTreeViewModelStoreOwner(activity)
    setViewTreeSavedStateRegistryOwner(activity)
    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    setContent { GhosttyXRTheme { content() } }
}

private fun View.pixelSize(widthDp: Int, heightDp: Int): IntSize2d {
    val density = resources.displayMetrics.density
    return IntSize2d((widthDp * density).toInt(), (heightDp * density).toInt())
}

/**
 * Transient confirmation pill for the voice turn that just finished: `✓ message` for
 * [VoiceCommandState.Result], `! message` for [VoiceCommandState.Error], nothing otherwise.
 * Visibility timing (auto-hide) is owned by the hosting entity — this composable is stateless so
 * it never mutates controller state (a later `startIfIdle()` from Result/Error starts fresh).
 *
 * Reads the command controller: [VoiceCommandController.state] is the single toast source —
 * both the legacy batch path and the Live path ([LiveCommandSession] → [LiveCommandEngine])
 * publish their final reply through it.
 */
@Composable
private fun VoiceCommandToast(
    voiceState: StateFlow<VoiceCommandState>,
) {
    val state by voiceState.collectAsState()
    val (glyph, message, tint) = when (val s = state) {
        is VoiceCommandState.Result -> Triple("✓", s.message, GhosttyTokens.Online)
        is VoiceCommandState.Error -> Triple("!", s.message, GhosttyTokens.Danger)
        else -> return
    }
    Box(modifier = Modifier.fillMaxSize()) {
        GxrPanel(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(GhosttyTokens.ChromeCorner),
            color = GhosttyTokens.Surface,
            borderColor = tint.copy(alpha = 0.5f),
        ) {
            Box(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                GxrText(
                    "$glyph $message",
                    fontSize = 13.sp,
                    color = tint,
                    maxLines = 3,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
            }
        }
    }
}

private const val TOAST_WIDTH_DP = 300
private const val TOAST_HEIGHT_DP = 84
private const val TOAST_VISIBLE_MS = 2_500L

private const val FRAME_MS = 11L // ~90 Hz: head parallax judders visibly at the old 30 Hz
private const val REVEAL_MS = 280L
private const val WRAP_UP_MS = 250L
private const val NANOS_PER_SECOND = 1_000_000_000.0

private const val PET_SCALE = 1f
private const val PET_VERTICAL_OFFSET_M = -0.025f

/** Side of the panel the pet used to live in. Kept as the unit of measure so the orb's physical size is unchanged. */
private const val PET_PANEL_DP = 220

/** Orb radius as a fraction of [PET_PANEL_DP], matching how much of that panel the old orb filled. */
private const val ORB_RADIUS_FRACTION = 0.475f

/** Quad half-extent in orb radii: halo (1.34) + mote orbits (1.62) + margin for off-axis views. */
private const val QUAD_HEADROOM = 2.1f

// Side-by-side: each eye gets a square half. Sized so the ball's on-surface diameter roughly
// matches the old panel's, since the quad is now ~2x wider in metres than the ball.
private const val SURFACE_WIDTH_PX = 2048
private const val SURFACE_HEIGHT_PX = 1024

/** Used only when head tracking is unavailable, so both halves render identically. */
private const val NOMINAL_VIEW_DISTANCE_M = 0.45f

private const val BOB_AMPLITUDE_M = 0.01f
private const val BOB_PERIOD_S = 3.1f
private const val SWAY_AMPLITUDE_DEG = 3f
private const val SWAY_PERIOD_S = 5.3f
private const val BREATH_AMPLITUDE = 0.02f
private const val BREATH_PERIOD_S = 2.3f

private fun ease(f: Float): Float = f * f * (3f - 2f * f) // smoothstep
