package com.ghosttyxr.app

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.rule.GrantPermissionRule
import com.ghosttyxr.app.qr.PairingPayload
import com.ghosttyxr.app.terminal.TERMINAL_CANVAS_TEST_TAG
import com.ghosttyxr.app.ui.GhosttyXRApp
import com.ghosttyxr.app.ui.HAND_TRACKING_PERMISSION
import com.ghosttyxr.app.ui.HEAD_TRACKING_PERMISSION
import com.ghosttyxr.app.ui.SETTINGS_PANEL_TEST_TAG
import com.ghosttyxr.app.ui.RESUME_LIST_TEST_TAG
import com.ghosttyxr.app.ui.END_ALL_SESSIONS_TEST_TAG
import com.ghosttyxr.app.ui.END_SESSION_TEST_TAG_PREFIX
import com.ghosttyxr.app.ui.START_FRESH_TEST_TAG
import com.ghosttyxr.app.ui.THEME_NAV_TEST_TAG
import com.ghosttyxr.app.ui.THEME_SEARCH_TEST_TAG
import com.ghosttyxr.app.terminal.session.FakeCockpitLifecycle
import com.ghosttyxr.app.terminal.session.PairedCredentials
import com.ghosttyxr.app.terminal.session.SessionState
import com.ghosttyxr.app.terminal.session.SessionSummary
import com.ghosttyxr.app.terminal.theme.GhosttyThemeRegistry
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Flat-mode full-app end-to-end smoke. Composes the real [GhosttyXRApp] with
 * `spatialEnabledOverride = false` so the entire cockpit renders as ordinary 2D
 * Compose — no `Subspace`/`SpatialPanel`, hence reachable by the Compose test
 * harness without driving the spatial XR runtime by hand.
 *
 * Backed by [FakeCockpitLifecycle]: real [TerminalSessionEngine]s on
 * real libghostty surfaces, fed by [FakeSshTransport]. Bytes enqueued on a
 * pane's fake transport flow through libghostty to the rendered
 * [com.ghosttyxr.app.terminal.TerminalSnapshot], so this exercises the same
 * pane → engine → snapshot → Canvas path production uses, minus the SSH socket.
 *
 * This closes the "End-to-end smoke (TBD)" row in TESTING.md without needing a
 * reachable Mac. The real-Mac round-trip stays in
 * [com.ghosttyxr.app.terminal.session.TerminalSessionEngineIntegrationTest.connectsToLocalhostSsh].
 *
 * Runs on the `GhosttyXR_Headset` AVD (or any arm64-v8a device — libghostty is
 * the only device requirement; nothing here needs FullSpace).
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class FlatAppE2ETest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    // Pre-grant the XR tracking permissions. On the XR AVD an XR session exists even in
    // flat mode, so GhosttyXRApp launches the HEAD_TRACKING permission prompt on first
    // composition; the system dialog pauses the test activity and the Compose harness
    // then fails every hierarchy query with "No compose hierarchies found in the app".
    @get:Rule
    val trackingPermissions: GrantPermissionRule = GrantPermissionRule.grant(
        HEAD_TRACKING_PERMISSION,
        HAND_TRACKING_PERMISSION,
    )

    private lateinit var cockpit: FakeCockpitLifecycle

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        GhosttyThemeRegistry.init(context)
        cockpit = FakeCockpitLifecycle()
    }

    @After
    fun tearDown() {
        cockpit.close()
    }

    /**
     * Pane → engine → snapshot → Canvas: connect, push bytes on the fake
     * transport, and assert the terminal Canvas renders (its semantics node
     * carries the live `cols×rows` the renderer measured).
     */
    @Test
    fun connectedPaneRendersTerminalCanvas() {
        composeTestRule.setContent {
            GhosttyXRApp(
                cockpit = cockpit,
                spatialEnabledOverride = false,
            )
        }

        // Idle: no pane connected yet → the pairing scanner is up, no terminal Canvas.
        // The grid wraps each pane in a `clickable` (mergeDescendants), so the canvas
        // testTag lives in the *unmerged* tree — match against it throughout.
        composeTestRule.onAllNodesWithTag(TERMINAL_CANVAS_TEST_TAG, useUnmergedTree = true)
            .assertCountEquals(0)

        // Pair (token connect), then feed the pane a line of output.
        cockpit.startFresh(PairedCredentials(PAIRING))
        awaitState(0) { it is SessionState.Connected }
        cockpit.transportFor(0).enqueueRead("hello from fake ssh\r\n".toByteArray())

        // The grid swaps in once a pane is Connected; the Canvas must materialise.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag(TERMINAL_CANVAS_TEST_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag(TERMINAL_CANVAS_TEST_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun resumableSessionsShowPickerBeforeCockpit() {
        var startedFresh = false
        var endedSession: String? = null
        var endedAll = false
        cockpit.showResumePicker(
            listOf(SessionSummary(id = "saved", savedAtEpochMs = 0L, paneCount = 2)),
        )
        composeTestRule.setContent {
            GhosttyXRApp(
                cockpit = cockpit,
                spatialEnabledOverride = false,
                onResumeSession = {},
                onStartFreshSession = { startedFresh = true },
                onEndSession = { endedSession = it },
                onEndAllSessions = { endedAll = true },
            )
        }

        composeTestRule.onNodeWithTag(RESUME_LIST_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("2 panes").assertIsDisplayed()
        composeTestRule.onNodeWithTag(START_FRESH_TEST_TAG).performClick()
        composeTestRule.onNodeWithTag("${END_SESSION_TEST_TAG_PREFIX}saved").performClick()
        composeTestRule.onNodeWithTag(END_ALL_SESSIONS_TEST_TAG).performClick()
        composeTestRule.runOnIdle {
            assert(startedFresh)
            assert(endedSession == "saved")
            assert(endedAll)
        }
    }

    /** Settings panel: theme drill-in + search filters the catalog and applies a pick. */
    @Test
    fun settingsPanelThemeSearchAndSelect() {
        composeTestRule.setContent {
            GhosttyXRApp(
                cockpit = cockpit,
                spatialEnabledOverride = false,
                panelExpanded = true,
            )
        }
        cockpit.startFresh(PairedCredentials(PAIRING))
        awaitState(0) { it is SessionState.Connected }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag(SETTINGS_PANEL_TEST_TAG)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag(SETTINGS_PANEL_TEST_TAG).assertIsDisplayed()

        // Drill into the theme sub-screen, search, and pick.
        composeTestRule.onNodeWithTag(THEME_NAV_TEST_TAG).performClick()
        composeTestRule.onNodeWithTag(THEME_SEARCH_TEST_TAG).performTextInput("dracula")
        composeTestRule.onNodeWithText("Dracula").assertIsDisplayed()
        composeTestRule.onNodeWithText("Dracula").performClick()
        assert(GhosttyThemeRegistry.active.value == "Dracula") {
            "theme select did not apply: active=${GhosttyThemeRegistry.active.value}"
        }
    }

    /** Add panes up to the cap, then remove back down — the grid tracks the cockpit. */
    @Test
    fun addAndRemovePanesUpdatesGrid() {
        composeTestRule.setContent {
            GhosttyXRApp(
                cockpit = cockpit,
                spatialEnabledOverride = false,
                panelExpanded = true,
            )
        }
        cockpit.startFresh(PairedCredentials(PAIRING))
        awaitState(0) { it is SessionState.Connected }
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag(TERMINAL_CANVAS_TEST_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        assert(cockpit.paneCount() == 1)

        // "+ New terminal" adds a pane (auto-connects to the last payload).
        composeTestRule.onNodeWithText("+  New terminal").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            cockpit.paneCount() == 2
        }
        awaitState(1) { it is SessionState.Connected }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag(TERMINAL_CANVAS_TEST_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().size == 2
        }
        composeTestRule.onAllNodesWithTag(TERMINAL_CANVAS_TEST_TAG, useUnmergedTree = true)
            .assertCountEquals(2)
    }

    /** EOF on the transport routes the pane through the reconnect state machine. */
    @Test
    fun transportEofDrivesReconnectState() {
        composeTestRule.setContent {
            GhosttyXRApp(
                cockpit = cockpit,
                spatialEnabledOverride = false,
            )
        }
        cockpit.startFresh(PairedCredentials(PAIRING))
        awaitState(0) { it is SessionState.Connected }

        cockpit.transportFor(0).signalEof()
        awaitState(0, timeoutMs = 5_000) {
            it is SessionState.Reconnecting || it is SessionState.Failed
        }
    }

    private fun awaitState(
        paneId: Int,
        timeoutMs: Long = 3_000,
        predicate: (SessionState) -> Boolean,
    ) {
        val state = cockpit.stateFor(paneId)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate(state.value)) return
            Thread.sleep(20)
        }
        throw AssertionError("pane $paneId state never matched; last=${state.value}")
    }

    private companion object {
        val PAIRING = PairingPayload(
            host = "mac.local",
            port = 22,
            user = "dev",
            token = "hunter2",
            fingerprint = null,
        )
    }
}
