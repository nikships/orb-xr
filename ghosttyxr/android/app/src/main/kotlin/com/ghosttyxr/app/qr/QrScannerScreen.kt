package com.ghosttyxr.app.qr

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.xr.arcore.QrCode
import androidx.xr.arcore.TrackingState
import androidx.xr.compose.platform.LocalSession
import androidx.xr.runtime.Config
import androidx.xr.runtime.QrCodeTrackingMode
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionConfigureSuccess
import com.ghosttyxr.app.RuntimePermissions
import com.ghosttyxr.app.terminal.theme.GhosttyTokens
import com.ghosttyxr.app.ui.chrome.GxrButton
import com.ghosttyxr.app.ui.chrome.GxrButtonStyle
import com.ghosttyxr.app.ui.chrome.GxrText
import com.ghosttyxr.app.util.GxrLog
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.ZoomSuggestionOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException

private const val TAG = "QrScannerScreen"

/** Cap for ML Kit's auto-zoom suggestion — enough for a distant Mac screen, safely below telephoto extremes. */
internal const val QR_AUTO_ZOOM_MAX_RATIO = 4.0f

/**
 * Uses Android XR QR tracking when available, with CameraX + ML Kit as the fallback. When a QR is
 * decoded into a valid [PairingPayload], the result is stashed in [PendingPairing] and surfaced
 * through [onPaired].
 */
@Composable
fun QrScannerScreen(
    modifier: Modifier = Modifier,
    onPaired: (PairingPayload) -> Unit = {},
    onRawQr: ((String) -> Boolean)? = null,
    xrTrackingMode: QrCodeTrackingMode = QrCodeTrackingMode.STATIC,
    // Fires with the XR 3D pose of the decoded pairing QR (alongside the string payload) so
    // the fresh cockpit can seed primary-pane / window-mirror placement next to the physical
    // Mac display. Also stashed in QrPlacementAnchor for consumers that don't observe this
    // callback. No-op default keeps existing call sites signature-stable.
    onPlacementSeeded: (QrPlacementSeed) -> Unit = {},
) {
    val session = LocalSession.current
    var useCameraFallback by remember(session) { mutableStateOf(session == null) }

    if (session != null && !useCameraFallback) {
        XrQrScanner(
            modifier = modifier,
            session = session,
            trackingMode = xrTrackingMode,
            onPaired = onPaired,
            onRawQr = onRawQr,
            onPlacementSeeded = onPlacementSeeded,
            onFallback = { useCameraFallback = true },
        )
    } else {
        CameraQrScanner(
            modifier = modifier,
            onPaired = onPaired,
            onRawQr = onRawQr,
        )
    }
}

@Composable
private fun XrQrScanner(
    modifier: Modifier,
    session: Session,
    trackingMode: QrCodeTrackingMode,
    onPaired: (PairingPayload) -> Unit,
    onRawQr: ((String) -> Boolean)?,
    onPlacementSeeded: (QrPlacementSeed) -> Unit,
    onFallback: () -> Unit,
) {
    val context = LocalContext.current
    // SCENE_UNDERSTANDING is batch-requested at app startup. Soft re-request remains only
    // as a recovery path if the user denied it there; otherwise skip straight to XR tracking.
    var hasScenePermission by remember {
        mutableStateOf(context.hasPermission(RuntimePermissions.SCENE_UNDERSTANDING_PERMISSION))
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasScenePermission = granted
        if (!granted) onFallback()
    }

    LifecycleResumeEffect(Unit) {
        hasScenePermission =
            context.hasPermission(RuntimePermissions.SCENE_UNDERSTANDING_PERMISSION)
        onPauseOrDispose {}
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (hasScenePermission) {
            XrQrTrackingContent(
                session = session,
                trackingMode = trackingMode,
                onPaired = onPaired,
                onRawQr = onRawQr,
                onPlacementSeeded = onPlacementSeeded,
                onFallback = onFallback,
            )
            ScannerPrompt(
                title = "Look at the pairing QR",
                detail = "Orb XR will detect the code through Android XR.",
            )
        } else {
            PermissionRequest(
                onRequest = {
                    permissionLauncher.launch(RuntimePermissions.SCENE_UNDERSTANDING_PERMISSION)
                },
                onFallback = onFallback,
                rationale = "Allow scene understanding to scan pairing QR codes without opening a camera preview.",
            )
        }
    }
}

@Composable
private fun PermissionRequest(
    onRequest: () -> Unit,
    onFallback: (() -> Unit)? = null,
    rationale: String,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GxrText(
            text = "Connect your Mac",
            color = GhosttyTokens.OnSurface,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
        )
        GxrText(
            text = rationale,
            color = GhosttyTokens.OnSurfaceVariant,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
        )
        GxrButton(
            modifier = Modifier.padding(top = 8.dp),
            onClick = onRequest,
            style = GxrButtonStyle.Primary,
        ) {
            GxrText("Allow QR scanning")
        }
        if (onFallback != null) {
            GxrButton(
                onClick = onFallback,
                style = GxrButtonStyle.Outline,
            ) {
                GxrText("Use camera scanner")
            }
        }
    }
}

@Composable
private fun ScannerPrompt(title: String, detail: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GxrText(
            text = title,
            color = GhosttyTokens.OnSurface,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        GxrText(
            text = detail,
            color = GhosttyTokens.OnSurfaceVariant,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun XrQrTrackingContent(
    session: Session,
    trackingMode: QrCodeTrackingMode,
    onPaired: (PairingPayload) -> Unit,
    onRawQr: ((String) -> Boolean)?,
    onPlacementSeeded: (QrPlacementSeed) -> Unit,
    onFallback: () -> Unit,
) {
    val currentOnPaired by rememberUpdatedState(onPaired)
    val currentOnRawQr by rememberUpdatedState(onRawQr)
    val currentOnPlacementSeeded by rememberUpdatedState(onPlacementSeeded)
    val currentOnFallback by rememberUpdatedState(onFallback)
    val router = remember { QrPayloadRouter() }

    LaunchedEffect(session, trackingMode) {
        val configured = runCatching { session.setQrTrackingMode(trackingMode) }.getOrElse {
            GxrLog.w(TAG, "XR QR tracking configuration failed", it)
            false
        }
        if (!configured) {
            currentOnFallback()
            return@LaunchedEffect
        }

        try {
            QrCode.subscribe(session).collect { codes ->
                codes.forEach { code ->
                    val state = code.state.value
                    // Seed cockpit placement from the XR 3D pose alongside the string data:
                    // the fresh cockpit's primary pane + window mirror start next to the
                    // physical Mac display instead of the grid default. Seeding is gated on
                    // TRACKING + usable extents; stale/degenerate poses leave no seed behind.
                    if (state.trackingState == TrackingState.TRACKING) {
                        QrPlacementSeed.fromPose(
                            xMeters = state.centerPose.translation.x,
                            yMeters = state.centerPose.translation.y,
                            zMeters = state.centerPose.translation.z,
                            qrWidthMeters = state.extents.width,
                            qrHeightMeters = state.extents.height,
                        )?.let {
                            QrPlacementAnchor.seed(it)
                            currentOnPlacementSeeded(it)
                        }
                    }
                    router.route(
                        raw = state.data,
                        onRawQr = currentOnRawQr,
                        onPaired = currentOnPaired,
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            GxrLog.w(TAG, "XR QR tracking failed; falling back to camera", error)
            currentOnFallback()
        } finally {
            runCatching { session.setQrTrackingMode(QrCodeTrackingMode.DISABLED) }.onFailure { error ->
                GxrLog.w(TAG, "Failed to disable XR QR tracking", error)
            }
        }
    }
}

@Composable
private fun CameraQrScanner(
    modifier: Modifier,
    onPaired: (PairingPayload) -> Unit,
    onRawQr: ((String) -> Boolean)?,
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(context.hasPermission(Manifest.permission.CAMERA))
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCameraPermission = granted }

    LifecycleResumeEffect(Unit) {
        hasCameraPermission = context.hasPermission(Manifest.permission.CAMERA)
        onPauseOrDispose {}
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (hasCameraPermission) {
            CameraQrScannerContent(onPaired = onPaired, onRawQr = onRawQr)
        } else {
            PermissionRequest(
                onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                rationale = "Orb XR needs camera access to scan pairing QR codes shown on your Mac.",
            )
        }
    }
}

@SuppressLint("UnsafeOptInUsageError")
@Composable
private fun CameraQrScannerContent(
    onPaired: (PairingPayload) -> Unit,
    onRawQr: ((String) -> Boolean)?,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    // Filled in once bindToLifecycle returns; the ML Kit zoom callback reads it to drive
    // CameraControl.setZoomRatio(). A holder (not a recomposed state) because the callback
    // fires from ML Kit's executor thread, outside composition.
    val zoomCameraHolder = remember { ZoomCameraHolder() }
    // enableAllPotentialBarcodes keeps partially-visible codes flowing so zoom suggestions
    // arrive for small/distant Mac-screen QR codes the user shouldn't have to lean into.
    val barcodeScanner: BarcodeScanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .enableAllPotentialBarcodes()
                .setZoomSuggestionOptions(zoomSuggestionOptions(zoomCameraHolder))
                .build(),
        )
    }
    var lastError by remember { mutableStateOf<String?>(null) }
    var cameraUnavailable by remember { mutableStateOf<String?>(null) }
    val currentOnPaired by rememberUpdatedState(onPaired)
    val currentOnRawQr by rememberUpdatedState(onRawQr)
    val router = remember { QrPayloadRouter() }

    DisposableEffect(Unit) {
        onDispose {
            analyzerExecutor.shutdown()
            barcodeScanner.close()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analyzerExecutor) { proxy ->
                    val image = proxy.image
                    if (image == null) {
                        proxy.close()
                        return@setAnalyzer
                    }
                    val input = InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees)
                    barcodeScanner.process(input)
                        .addOnSuccessListener { barcodes ->
                            GxrLog.d(TAG) { "Scanned ${barcodes.size} barcodes" }
                            val raw = barcodes.firstNotNullOfOrNull { it.rawValue }
                            if (raw != null) {
                                GxrLog.d(TAG) { "Decoded new QR payload" }
                                val result = router.route(
                                    raw = raw,
                                    onRawQr = currentOnRawQr,
                                    onPaired = currentOnPaired,
                                )
                                if (result is QrRouteResult.Invalid) {
                                    GxrLog.w(TAG, "QR parse failed for '$raw'", result.error)
                                    lastError = result.error.message
                                }
                            }
                        }
                        .addOnFailureListener { err ->
                            GxrLog.w(TAG, "ML Kit barcode scan failed", err)
                        }
                        .addOnCompleteListener {
                            proxy.close()
                        }
                }

                provider.unbindAll()
                // Pre-flight: on XR headsets the camera may be claimed by the spatial runtime.
                // Validate the Preview + Analysis session before binding so a resource conflict
                // surfaces a readable status instead of stalling pairing inside bindToLifecycle.
                val available = runCatching {
                    provider.getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA)
                }.fold(
                    onSuccess = { cameraInfo ->
                        cameraInfo.isSessionConfigSupported(SessionConfig(preview, analysis))
                    },
                    onFailure = { false },
                )
                if (!available) {
                    GxrLog.w(TAG, "Camera session not supported; showing pairing guidance")
                    cameraUnavailable =
                        "Camera is busy or unavailable — check no other app is using it, then reopen the scanner."
                    analysis.clearAnalyzer()
                    return@addListener
                }
                val camera: Camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
                // Hand the bound camera to the ML Kit zoom callback so suggested ratios for
                // distant Mac-screen QR codes reach CameraControl.
                zoomCameraHolder.camera = camera
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )

    val errorMessage = lastError
    if (errorMessage != null) {
        Box(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            contentAlignment = Alignment.BottomStart,
        ) {
            GxrText(
                text = "ignored invalid QR: $errorMessage",
                color = GhosttyTokens.Danger,
                fontSize = 12.sp,
                modifier = Modifier
                    .background(Color(0x99000000))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }

    // Pre-flight session check failed (XR runtime holding the camera): keep the scanner chrome
    // up with a real explanation instead of a black stall. Never invents connectivity — the
    // pairing payload path is untouched, only camera availability is reported.
    val unavailableMessage = cameraUnavailable
    if (unavailableMessage != null) {
        ScannerPrompt(title = "Camera unavailable", detail = unavailableMessage)
    }
}

/**
 * Bound-camera holder for ML Kit's zoom callback. Written once from the CameraX main-executor
 * bind callback, read from ML Kit's detector thread — hence volatile.
 */
internal class ZoomCameraHolder {
    @Volatile
    var camera: Camera? = null
}

/**
 * Intelligent auto-zoom for QR pairing: ML Kit suggests a zoom ratio when the code is small or
 * distant (the Mac screen across the room) and the callback applies it through
 * CameraControl.setZoomRatio, clamped to [QR_AUTO_ZOOM_MAX_RATIO] and the device's reported
 * max so out-of-range suggestions can't throw. Declines (returns false) when no camera is
 * bound yet.
 */
internal fun zoomSuggestionOptions(holder: ZoomCameraHolder): ZoomSuggestionOptions =
    ZoomSuggestionOptions.Builder { suggestedRatio ->
        val camera = holder.camera ?: return@Builder false
        val maxZoom = camera.cameraInfo.zoomState.value
            ?.maxZoomRatio
            ?.takeIf { it >= 1f }
            ?: QR_AUTO_ZOOM_MAX_RATIO
        val clamped = suggestedRatio.coerceIn(1f, minOf(maxZoom, QR_AUTO_ZOOM_MAX_RATIO))
        camera.cameraControl.setZoomRatio(clamped)
        true
    }
        .setMaxSupportedZoomRatio(QR_AUTO_ZOOM_MAX_RATIO)
        .build()

private fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun Session.setQrTrackingMode(mode: QrCodeTrackingMode): Boolean {
    val newConfig = Config.Builder(config)
        .setQrCodeTracking(mode)
        .build()
    return configure(newConfig) is SessionConfigureSuccess
}

internal class QrPayloadRouter {
    private var lastDecodedRaw: String? = null

    fun route(
        raw: String,
        onRawQr: ((String) -> Boolean)?,
        onPaired: (PairingPayload) -> Unit,
    ): QrRouteResult {
        if (raw == lastDecodedRaw) return QrRouteResult.Duplicate
        lastDecodedRaw = raw

        if (onRawQr?.invoke(raw) == true) return QrRouteResult.HandledRaw

        return PairingPayload.parse(raw).fold(
            onSuccess = { payload ->
                PendingPairing.set(payload)
                onPaired(payload)
                QrRouteResult.Paired(payload)
            },
            onFailure = QrRouteResult::Invalid,
        )
    }
}

internal sealed interface QrRouteResult {
    data object Duplicate : QrRouteResult
    data object HandledRaw : QrRouteResult
    data class Paired(val payload: PairingPayload) : QrRouteResult
    data class Invalid(val error: Throwable) : QrRouteResult
}
