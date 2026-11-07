package com.ghosttyxr.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import com.ghosttyxr.app.terminal.theme.GhosttyXRTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.ghosttyxr.app.terminal.TERMINAL_CANVAS_TEST_TAG
import com.ghosttyxr.app.ui.SETTINGS_PANEL_TEST_TAG
import com.ghosttyxr.app.ui.THEME_NAV_TEST_TAG
import com.ghosttyxr.app.ui.THEME_SEARCH_TEST_TAG
import com.ghosttyxr.app.ui.panel.TerminalControlPanel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose semantics smoke for the XR shell. Runs on the official Android
 * XR AVD (`system-images/android-34/google-xr/arm64-v8a`, see
 * `TESTING.md`).
 *
 * **Scope:** prove the Compose ui-test rule resolves
 * `TERMINAL_CANVAS_TEST_TAG` (the public test contract `android-xr`
 * shipped on `TerminalCanvas`) and a `contentDescription`-based matcher
 * on the XR runtime. These are off-process Compose harness assertions that
 * avoid launching the full spatial activity.
 *
 * **Assertion choice:** `testTag` over `contentDescription` per
 * `android-xr`'s guidance — the live `cols × rows` figure in the canvas
 * description depends on rendered Canvas size, which varies with panel
 * and AVD display. The tag is the stable contract.
 *
 * Run with:
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     --tests com.ghosttyxr.app.SpatialPanelSemanticsTest
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class SpatialPanelSemanticsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun terminalCanvasTestTagIsReachable() {
        composeTestRule.setContent {
            GhosttyXRTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(TERMINAL_CANVAS_TEST_TAG),
                )
            }
        }

        composeTestRule.onNodeWithTag(TERMINAL_CANVAS_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun terminalCanvasContentDescriptionIsReachable() {
        composeTestRule.setContent {
            GhosttyXRTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { contentDescription = "Terminal 1 (80×24)" },
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription("Terminal 1 (80×24)")
            .assertIsDisplayed()
    }

    @Test
    fun controlPanelSearchFiltersCatalogAndUpdatesSelection() {
        var selectedTheme by mutableStateOf("Dracula")

        composeTestRule.setContent {
            GhosttyXRTheme {
                TerminalControlPanel(
                    expanded = true,
                    onExpandedChange = {},
                    sessions = emptyList(),
                    sessionStates = emptyList(),
                    focusedPaneId = 0,
                    onPaneSelected = {},
                    canAddPane = true,
                    canRemovePane = false,
                    onAddPane = {},
                    onRemovePane = {},
                    activeTheme = selectedTheme,
                    availableThemes = listOf(
                        "Catppuccin Mocha",
                        "Dracula",
                        "TokyoNight",
                        "GitHub Dark",
                        "Solarized Dark Higher Contrast",
                        "Builtin Dark",
                    ),
                    layoutLocked = false,
                    terminalTransparencyPercent = 100f,
                    fontSizeSp = 14f,
                    onThemeSelected = { selectedTheme = it },
                    onLayoutLockedChanged = {},
                    onTerminalTransparencyChanged = {},
                    onTerminalTransparencyChangeFinished = {},
                    onFontSizeChanged = {},
                    onFontSizeChangeFinished = {},
                    immersiveMode = false,
                    immersiveAvailable = false,
                    onImmersiveModeChanged = {},
                    isFullSpace = true,
                    onDisconnectAll = {},
                )
            }
        }

        composeTestRule.onNodeWithTag(SETTINGS_PANEL_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("Terminal background: 100%").assertIsDisplayed()
        composeTestRule.onNodeWithText("Terminal text: 14pt").assertIsDisplayed()

        // The root panel shows the active theme on a drill-in row, not the full catalog.
        composeTestRule.onNodeWithText("Dracula").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Builtin Dark").assertCountEquals(0)

        // Drilling into the theme sub-menu reveals the full catalog.
        composeTestRule.onNodeWithTag(THEME_NAV_TEST_TAG).performClick()
        composeTestRule.onNodeWithText("Builtin Dark").assertIsDisplayed()
        composeTestRule.onNodeWithText("GitHub Dark").assertIsDisplayed()

        // Search narrows the list.
        composeTestRule.onNodeWithTag(THEME_SEARCH_TEST_TAG).performTextInput("tokyo")
        composeTestRule.onNodeWithText("TokyoNight").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Builtin Dark").assertCountEquals(0)

        // Selecting an item from the filtered list applies it.
        composeTestRule.onNodeWithText("TokyoNight").performClick()
        assert(selectedTheme == "TokyoNight")

        // Returning to the root panel shows the new active theme on the drill-in row.
        composeTestRule.onNodeWithContentDescription("Back to settings").performClick()
        composeTestRule.onNodeWithText("TokyoNight").assertIsDisplayed()
    }

    @Test
    fun collapsedControlPanelShowsGearAffordance() {
        composeTestRule.setContent {
            GhosttyXRTheme {
                TerminalControlPanel(
                    expanded = false,
                    onExpandedChange = {},
                    sessions = emptyList(),
                    sessionStates = emptyList(),
                    focusedPaneId = 0,
                    onPaneSelected = {},
                    canAddPane = true,
                    canRemovePane = false,
                    onAddPane = {},
                    onRemovePane = {},
                    activeTheme = null,
                    availableThemes = emptyList(),
                    layoutLocked = false,
                    terminalTransparencyPercent = 100f,
                    fontSizeSp = 14f,
                    onThemeSelected = {},
                    onLayoutLockedChanged = {},
                    onTerminalTransparencyChanged = {},
                    onTerminalTransparencyChangeFinished = {},
                    onFontSizeChanged = {},
                    onFontSizeChangeFinished = {},
                    immersiveMode = false,
                    immersiveAvailable = false,
                    onImmersiveModeChanged = {},
                    onDisconnectAll = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Open controls").assertIsDisplayed()
    }
}
