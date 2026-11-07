package com.ghosttyxr.app.ui.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.MovePolicy
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.movable
import androidx.xr.compose.subspace.layout.width
import com.ghosttyxr.app.qr.QrPlacementSeed
import com.ghosttyxr.app.qr.QrScannerScreen
import com.ghosttyxr.app.terminal.session.SessionState
import com.ghosttyxr.app.terminal.theme.GhosttyTokens
import com.ghosttyxr.app.ui.FORGET_MAC_TEST_TAG
import com.ghosttyxr.app.ui.PAIRING_STATUS_TEST_TAG
import com.ghosttyxr.app.ui.chrome.GxrButton
import com.ghosttyxr.app.ui.chrome.GxrButtonStyle
import com.ghosttyxr.app.ui.chrome.GxrText

/**
 * Pairing/reconnect surface shown until at least one pane is live. Wraps the QR
 * scanner in a movable [SpatialPanel] when spatial UI is available, falling back
 * to a plain full-screen 2D [Box] otherwise (HomeSpace / non-XR / tests).
 */
@Composable
fun PairingScannerPanel(
    statusText: String,
    errorText: String?,
    isPaired: Boolean,
    onForgetMac: () -> Unit,
    spatialEnabledOverride: Boolean?,
    // Mirrors the XR scanner's 3D QR pose for the Mac display (see onPlacementSeeded below).
    // No-op default keeps existing call sites signature-stable.
    onPlacementSeeded: (QrPlacementSeed) -> Unit = {},
) {
    val isSpatial = spatialEnabledOverride ?: LocalSpatialCapabilities.current.isSpatialUiEnabled
    if (isSpatial) {
        Subspace {
            SpatialPanel(
                SubspaceModifier
                    .width(640.dp)
                    .height(520.dp)
                    .movable(movePolicy = MovePolicy.Default),
            ) {
                PairingScannerContent(
                    statusText = statusText,
                    errorText = errorText,
                    isPaired = isPaired,
                    onForgetMac = onForgetMac,
                    onPlacementSeeded = onPlacementSeeded,
                )
            }
        }
    } else {
        PairingScannerContent(
            modifier = Modifier.fillMaxSize(),
            statusText = statusText,
            errorText = errorText,
            isPaired = isPaired,
            onForgetMac = onForgetMac,
            onPlacementSeeded = onPlacementSeeded,
        )
    }
}

@Composable
private fun PairingScannerContent(
    statusText: String,
    errorText: String?,
    isPaired: Boolean,
    onForgetMac: () -> Unit,
    modifier: Modifier = Modifier,
    // Fires when the XR scanner captures a 3D QR pose for the Mac display; the fresh cockpit
    // consumes the seed to place its primary pane (and window mirror) beside the physical
    // display. No-op default keeps previews/tests signature-stable.
    onPlacementSeeded: (QrPlacementSeed) -> Unit = {},
) {
    Box(modifier = modifier.fillMaxSize()) {
        QrScannerScreen(
            modifier = Modifier.fillMaxSize(),
            onPlacementSeeded = onPlacementSeeded,
        )
        StartupStatusOverlay(
            statusText = statusText,
            errorText = errorText,
            modifier = Modifier.align(Alignment.TopStart),
        )
        // A paired-but-unreachable Mac sits on the scanner; let the user drop
        // the saved pairing and start over (e.g. paired with the wrong Mac).
        if (isPaired) {
            GxrButton(
                onClick = onForgetMac,
                style = GxrButtonStyle.DangerFilled,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .testTag(FORGET_MAC_TEST_TAG),
            ) {
                GxrText("Forget this Mac")
            }
        }
    }
}

@Composable
private fun StartupStatusOverlay(
    statusText: String,
    errorText: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .padding(12.dp)
            .background(Color(0xDD0B0F14))
            .testTag(PAIRING_STATUS_TEST_TAG),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            GxrText(statusText, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
            if (errorText != null) {
                GxrText(
                    text = errorText,
                    color = GhosttyTokens.Danger,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/** Human-readable status line for the pairing/reconnect overlay, given the panes' states. */
fun pairingStatusText(states: List<SessionState>, isPaired: Boolean): String = when {
    states.any { it is SessionState.Connecting } -> "Connecting to your Mac…"
    states.any { it is SessionState.Authenticated } -> "Opening terminal shells…"
    states.any { it is SessionState.Reconnecting } -> "Reconnecting to your Mac…"
    states.any { it is SessionState.Failed } && isPaired ->
        "Couldn't reach your paired Mac. Make sure it's awake on the same network, or scan to re-pair."
    states.any { it is SessionState.Failed } ->
        "Connection failed. Scan again or check Remote Login on your Mac."
    isPaired -> "Reconnecting to your Mac…"
    else -> "Scan the pairing QR from your Mac."
}
