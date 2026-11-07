package com.ghosttyxr.app.ui.pane

import android.os.Build
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.xr.compose.spatial.PlanarEmbeddedSubspace
import androidx.xr.compose.subspace.SpatialExternalSurface
import androidx.xr.compose.subspace.StereoMode
import androidx.xr.compose.subspace.SuperSamplingPattern
import androidx.xr.compose.subspace.draw.spatialSmoothFeatheringEffect
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.fillMaxSize
import com.ghosttyxr.app.terminal.theme.GhosttyTokens
import com.ghosttyxr.app.terminal.windowmirror.WindowMirrorRelayController
import com.ghosttyxr.app.terminal.windowmirror.WindowMirrorState
import com.ghosttyxr.app.terminal.windowmirror.WindowMirrorStatus
import com.ghosttyxr.app.ui.WINDOW_MIRROR_FALLBACK_TEST_TAG
import com.ghosttyxr.app.ui.WINDOW_MIRROR_VIDEO_TEST_TAG
import com.ghosttyxr.app.ui.chrome.GxrIconButton
import com.ghosttyxr.app.ui.chrome.GxrPanel
import com.ghosttyxr.app.ui.chrome.GxrText
import com.ghosttyxr.app.util.GxrLog
import android.view.SurfaceView
import java.util.Locale
import kotlin.math.roundToInt

private const val TAG = "WindowMirror"

/**
 * Surface-side mirror spec, kept as pure constants so JVM unit tests pin the
 * task contract without an XR session or emulator:
 * - Mono stereo: the shared display is the same picture for both eyes.
 * - Pentagon supersampling: the runtime's highest-quality video scaling path.
 * - Small symmetric edge feather (percent of canvas per side).
 * - 60 Hz fixed-source frame rate on the legacy SurfaceView fallback.
 *
 * The enums mirror the scenecore/compose wrapper values by name so the test
 * fails loudly if the production call sites ever drift to another mode.
 */
enum class MirrorStereoMode { MONO }

enum class MirrorSuperSampling { PENTAGON }

internal const val MIRROR_FEATHER_PERCENT = 2
internal const val MIRROR_FRAME_RATE_HZ = 60f

@Composable
fun WindowMirrorSurface(
    controller: WindowMirrorRelayController,
    isFocused: Boolean,
    onFocus: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    inputEnabled: Boolean = true,
    spatialEnabledOverride: Boolean? = null,
) {
    val state by controller.state.collectAsState()
    val focusRequester = remember { FocusRequester() }
    var videoSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(isFocused) {
        if (isFocused) {
            runCatching { focusRequester.requestFocus() }
        }
    }
    DisposableEffect(controller) {
        onDispose {
            controller.setSurface(null)
        }
    }
    val isSpatial = spatialEnabledOverride
        ?: LocalSpatialCapabilities.current.isSpatialUiEnabled

    Column(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(GhosttyTokens.PaneCorner))
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (isFocused) controller.sendKey(event.nativeKeyEvent) else false
            }
            .background(Color.Black),
    ) {
        WindowMirrorStatusBar(
            state = state,
            isFocused = isFocused,
            onClose = onClose,
        )
        // Letterbox the video to the source aspect ratio (a 16:10 display share would
        // otherwise stretch to the pane's shape). Pointer normalization tracks the
        // letterboxed box, so coordinates stay accurate.
        val aspect = state.metadata
            ?.takeIf { it.width > 0 && it.height > 0 }
            ?.let { it.width.toFloat() / it.height.toFloat() }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .then(
                        if (aspect != null) Modifier.aspectRatio(aspect) else Modifier.fillMaxSize(),
                    )
                    .onSizeChanged { videoSize = it }
                    .then(
                        // Gate real hand-pinch forwarding to the Mac during a display
                        // share; the Mac's own pointer never enters via this filter.
                        if (inputEnabled) Modifier.mirrorPointerInput(controller, videoSize, onFocus)
                        else Modifier,
                    ),
            ) {
                // Full Space: MediaCodec decodes straight into a hardware-composited
                // SpatialExternalSurface (no SurfaceView hop — the frame stays on its
                // secure/composited path from codec to eye). Mono because the shared
                // display is the same picture for both eyes. Home Space (or any host
                // without spatial UI): the legacy SurfaceView path, which also keeps
                // Compose previews and JVM tests working without an XR session.
                if (isSpatial) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(WINDOW_MIRROR_VIDEO_TEST_TAG),
                    ) {
                        MirrorSpatialVideo(
                            controller = controller,
                            visible = state.hasWindow,
                        )
                    }
                } else {
                    MirrorFallbackVideo(
                        controller = controller,
                        visible = state.hasWindow,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(WINDOW_MIRROR_FALLBACK_TEST_TAG),
                    )
                }
            }
            if (state.status != WindowMirrorStatus.Streaming) {
                MirrorOverlayText(state.detail ?: "Choose Bring Window into XR from the Mac companion.")
            }
        }
    }
}

/**
 * Hardware-composited mirror video: a [SpatialExternalSurface] embedded in the pane's
 * 2D layout area via [PlanarEmbeddedSubspace], so the video quad inherits the
 * letterboxed box (aspect ratio + pointer normalization) while the frame itself
 * never passes through a `SurfaceView`.
 *
 * Contract notes for the runtime behind this composable:
 * - `stereoMode = Mono`: the shared display is the same picture for both eyes.
 * - `superSamplingPattern = Pentagon`: the runtime's highest-quality video scaling path.
 * - `spatialSmoothFeatheringEffect`: soft edge fade so the quad's silhouette blends
 *   into the pane instead of ending in a hard composited line.
 *
 * The controller contract is unchanged: surfaces arrive through
 * [WindowMirrorRelayController.setSurface] exactly as the `SurfaceView` holder
 * delivered them, so [WindowVideoDecoder][com.ghosttyxr.app.terminal.windowmirror.WindowVideoDecoder]
 * and the media/relay controllers are untouched.
 */
@Composable
private fun MirrorSpatialVideo(
    controller: WindowMirrorRelayController,
    visible: Boolean,
) {
    PlanarEmbeddedSubspace {
        SpatialExternalSurface(
            stereoMode = StereoMode.Mono,
            modifier = SubspaceModifier.fillMaxSize(),
            featheringEffect = spatialSmoothFeatheringEffect(
                MIRROR_FEATHER_PERCENT,
                MIRROR_FEATHER_PERCENT,
            ),
            superSamplingPattern = SuperSamplingPattern.Pentagon,
        ) {
            onSurfaceCreated { surface ->
                GxrLog.d(TAG) { "spatial mirror surface created" }
                controller.setSurface(surface)
            }
            onSurfaceDestroyed { surface ->
                GxrLog.d(TAG) { "spatial mirror surface destroyed" }
                controller.setSurface(null)
            }
        }
    }
    // SpatialExternalSurface has no View to hide; clearing the surface while no
    // window is shared stops the codec, and the overlay below covers the quad.
    DisposableEffect(visible) {
        if (!visible) controller.setSurface(null)
        onDispose {}
    }
}

/**
 * Home Space / non-XR fallback: MediaCodec decodes straight into this Surface, so
 * frames never touch the CPU or an intermediate GL texture.
 */
@Composable
private fun MirrorFallbackVideo(
    controller: WindowMirrorRelayController,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                requestMirrorFrameRate(this)
                holder.addCallback(object : android.view.SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                        controller.setSurface(holder.surface)
                    }

                    override fun surfaceChanged(
                        holder: android.view.SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int,
                    ) {
                        controller.setSurface(holder.surface)
                    }

                    override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                        controller.setSurface(null)
                    }
                })
            }
        },
        update = { view ->
            view.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        },
    )
}

private fun requestMirrorFrameRate(view: SurfaceView) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

    fun applyFrameRate(surface: android.view.Surface) {
        if (surface.isValid) {
            surface.setFrameRate(
                MIRROR_FRAME_RATE_HZ,
                android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
            )
        }
    }

    view.holder.addCallback(object : android.view.SurfaceHolder.Callback {
        override fun surfaceCreated(holder: android.view.SurfaceHolder) {
            applyFrameRate(holder.surface)
        }

        override fun surfaceChanged(
            holder: android.view.SurfaceHolder,
            format: Int,
            width: Int,
            height: Int,
        ) {
            applyFrameRate(holder.surface)
        }

        override fun surfaceDestroyed(holder: android.view.SurfaceHolder) = Unit
    })
    applyFrameRate(view.holder.surface)
}

private fun Modifier.mirrorPointerInput(
    controller: WindowMirrorRelayController,
    size: IntSize,
    onFocus: () -> Unit,
): Modifier =
    pointerInteropFilter { event ->
        val width = size.width.toFloat()
        val height = size.height.toFloat()
        val x = if (width > 0f) event.x / width else 0f
        val y = if (height > 0f) event.y / height else 0f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onFocus()
                controller.sendPointer("down", x, y)
                true
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                controller.sendPointer("move", x, y)
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                controller.sendPointer("up", x, y)
                true
            }
            MotionEvent.ACTION_SCROLL -> {
                controller.sendScroll(
                    x = x,
                    y = y,
                    dx = event.getAxisValue(MotionEvent.AXIS_HSCROLL) * 48f,
                    dy = event.getAxisValue(MotionEvent.AXIS_VSCROLL) * 48f,
                )
                true
            }
            else -> false
        }
    }

@Composable
private fun WindowMirrorStatusBar(
    state: WindowMirrorState,
    isFocused: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (isFocused) GhosttyTokens.Accent else null
    GxrPanel(
        modifier = modifier.padding(10.dp),
        shape = RoundedCornerShape(GhosttyTokens.ChromeCorner),
        color = GhosttyTokens.ScrimStrong,
        borderColor = borderColor,
        borderWidth = 1.5.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor(state.status)),
            )
            GxrText(
                text = state.metadata?.displayTitle?.ifBlank { null } ?: "Mac window",
                style = GhosttyTokens.PaneTitle,
                color = if (isFocused) GhosttyTokens.Accent else GhosttyTokens.OnSurface,
                modifier = Modifier.weight(1f),
            )
            GxrText(
                text = describeStatus(state),
                style = GhosttyTokens.StatusLabel,
                color = GhosttyTokens.OnSurfaceVariant,
            )
            GxrIconButton(
                onClick = onClose,
                size = 30.dp,
            ) {
                GxrText("✕", fontSize = 14.sp, color = GhosttyTokens.Danger)
            }
        }
    }
}

@Composable
private fun MirrorOverlayText(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GhosttyTokens.ScrimStrong.copy(alpha = 0.82f)),
        contentAlignment = Alignment.Center,
    ) {
        GxrText(
            text = text,
            color = GhosttyTokens.OnSurface,
            fontSize = 13.sp,
            modifier = Modifier.padding(18.dp),
        )
    }
}

private fun statusColor(status: WindowMirrorStatus): Color = when (status) {
    WindowMirrorStatus.Streaming -> GhosttyTokens.Online
    WindowMirrorStatus.Connecting -> GhosttyTokens.Warning
    WindowMirrorStatus.WaitingForCompanion,
    WindowMirrorStatus.WaitingForPairing,
    WindowMirrorStatus.WaitingForWindow -> GhosttyTokens.OnSurfaceFaint
    WindowMirrorStatus.PermissionNeeded -> GhosttyTokens.Warning
    WindowMirrorStatus.Error -> GhosttyTokens.Danger
}

private fun describeStatus(state: WindowMirrorState): String = when (state.status) {
    WindowMirrorStatus.WaitingForPairing -> "pair Mac"
    WindowMirrorStatus.Connecting -> "connecting"
    WindowMirrorStatus.WaitingForCompanion -> "companion"
    WindowMirrorStatus.WaitingForWindow -> "waiting"
    WindowMirrorStatus.Streaming -> state.streamingStatusText()
    WindowMirrorStatus.PermissionNeeded -> "permission"
    WindowMirrorStatus.Error -> "error"
}

private fun WindowMirrorState.streamingStatusText(): String {
    val currentStats = stats
    if (currentStats.receivedFramesPerSecond > 0) {
        return "${currentStats.receivedFramesPerSecond.roundToInt()}fps · " +
            "${String.format(Locale.US, "%.1f", currentStats.receivedBitrateMbps)}Mbps · " +
            "${currentStats.endToEndLatencyMs.roundToInt()}ms"
    }
    return if (interactive) "interactive" else "view-only"
}
