package com.ghosttyxr.app.terminal.spatial

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import com.ghosttyxr.app.terminal.TerminalCanvas
import com.ghosttyxr.app.terminal.buildDemoSnapshot
import com.ghosttyxr.app.terminal.theme.GhosttyXRTheme

/**
 * Form-factor previews per the `adaptive` skill's Step 1 — verify the 2D fallback path of
 * [AdaptivePaneGrid] renders correctly on each non-XR device class. The XR HMD preview isn't
 * a stock device profile; verifying the spatial path needs the Google XR Emulator
 * (integration-qa is spinning up the `system-images;android-34;google-xr;arm64-v8a` AVD).
 *
 * These previews intentionally exercise the **non-spatial** code path — when
 * `LocalSpatialCapabilities.current.isSpatialUiEnabled` is `false`, [AdaptivePaneGrid]
 * routes to the flat 2×2 [Column]/[Row] layout. Studio's preview pane reports
 * `isSpatialUiEnabled = false` by default, so these previews show exactly what a user sees
 * when they launch the app in HomeSpace or on a non-XR phone/tablet/foldable.
 */
@Preview(name = "Phone", device = Devices.PHONE, showBackground = true, backgroundColor = 0xFF000000)
@Preview(name = "Foldable", device = Devices.FOLDABLE, showBackground = true, backgroundColor = 0xFF000000)
@Preview(name = "Tablet", device = Devices.TABLET, showBackground = true, backgroundColor = 0xFF000000)
@Preview(name = "Desktop", device = Devices.DESKTOP, showBackground = true, backgroundColor = 0xFF000000)
annotation class FormFactorPreviews

@FormFactorPreviews
@Composable
private fun AdaptivePaneGridPreview() {
    GhosttyXRTheme {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            val snap = buildDemoSnapshot()
            AdaptivePaneGrid(
                panes = List(4) {
                    PaneContent { id ->
                        Box(modifier = Modifier.fillMaxSize().background(Color(snap.defaultBgArgb))) {
                            TerminalCanvas(
                                snapshot = snap,
                                paneLabel = "Preview terminal ${id + 1}",
                            )
                        }
                    }
                },
            )
        }
    }
}

@FormFactorPreviews
@Composable
private fun SingleTerminalPreview() {
    GhosttyXRTheme {
        val snap = buildDemoSnapshot()
        Box(modifier = Modifier.fillMaxSize().background(Color(snap.defaultBgArgb))) {
            TerminalCanvas(snapshot = snap, paneLabel = "Preview terminal 1")
        }
    }
}
