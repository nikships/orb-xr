package com.ghosttyxr.app.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalGridApi
import androidx.compose.foundation.layout.Grid
import androidx.compose.foundation.layout.GridTrackSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghosttyxr.app.DEFAULT_ENVIRONMENT_ID
import com.ghosttyxr.app.EnvironmentPicker
import com.ghosttyxr.app.R
import com.ghosttyxr.app.terminal.hostkeyboard.HostKeyboardRelayState
import com.ghosttyxr.app.terminal.hostkeyboard.HostKeyboardRelayStatus
import com.ghosttyxr.app.terminal.windowmirror.WindowMirrorState
import com.ghosttyxr.app.terminal.windowmirror.WindowMirrorStatus
import com.ghosttyxr.app.terminal.windowmirror.WindowMirrorTransport
import com.ghosttyxr.app.terminal.session.CockpitLifecycle
import com.ghosttyxr.app.terminal.session.CockpitPane
import com.ghosttyxr.app.terminal.session.SessionState
import com.ghosttyxr.app.terminal.settings.GhosttySettingsStore
import com.ghosttyxr.app.terminal.theme.GhosttyTokens
import com.ghosttyxr.app.ui.FORGET_MAC_TEST_TAG
import com.ghosttyxr.app.ui.HOST_KEYBOARD_STATUS_TEST_TAG
import com.ghosttyxr.app.ui.SETTINGS_PANEL_TEST_TAG
import com.ghosttyxr.app.ui.SPACE_TOGGLE_TEST_TAG
import com.ghosttyxr.app.ui.chrome.GxrButton
import com.ghosttyxr.app.ui.chrome.GxrButtonStyle
import com.ghosttyxr.app.ui.chrome.GxrIconButton
import com.ghosttyxr.app.ui.chrome.GxrPanel
import com.ghosttyxr.app.ui.chrome.GxrPanelHeader
import com.ghosttyxr.app.ui.chrome.GxrSection
import com.ghosttyxr.app.ui.chrome.GxrSlider
import com.ghosttyxr.app.ui.chrome.GxrSwitch
import com.ghosttyxr.app.ui.chrome.GxrText
import com.ghosttyxr.app.ui.pane.statusColor

/** Top-level destinations inside the control panel. */
private enum class SettingsSubScreen { Root, Theme }

private data class StatusPresentation(
    val label: String,
    val detail: String,
    val color: Color,
)

/** One settings card, keyed to a stable named Compose Grid area. */
private class SettingsCard(
    val areaId: String,
    val content: @Composable () -> Unit,
)

/**
 * Unified, collapsible control panel rendered in the ghostty-native chrome kit — a TUI
 * floating in space. Expanded: a width-adaptive grid of box-drawing-framed cards (1–3
 * columns, reflowing with the panel's measured width) holding Terminals (add / remove /
 * focus), Theme, Background, Layout (lock), Focus (immersive), Voice, Pro, Mac relay
 * status and Session actions. The same composable serves FullSpace (spatial panel) and
 * HomeSpace / flat mode; only the container size differs. Collapsed: a small gear button
 * so the panel stays out of the way.
 */
@OptIn(ExperimentalGridApi::class)
@Composable
fun TerminalControlPanel(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    sessions: List<CockpitPane>,
    sessionStates: List<SessionState>,
    focusedPaneId: Int,
    onPaneSelected: (Int) -> Unit,
    canAddPane: Boolean,
    canRemovePane: Boolean,
    onAddPane: () -> Unit,
    onRemovePane: (Int) -> Unit,
    activeTheme: String?,
    availableThemes: List<String>,
    layoutLocked: Boolean,
    snapPanesToSurfaces: Boolean = false,
    onSnapPanesToSurfacesChanged: (Boolean) -> Unit = {},
    terminalTransparencyPercent: Float,
    fontSizeSp: Float,
    onThemeSelected: (String) -> Unit,
    onLayoutLockedChanged: (Boolean) -> Unit,
    onTerminalTransparencyChanged: (Float) -> Unit,
    onTerminalTransparencyChangeFinished: () -> Unit,
    onFontSizeChanged: (Float) -> Unit,
    onFontSizeChangeFinished: () -> Unit,
    immersiveMode: Boolean,
    immersiveAvailable: Boolean,
    onImmersiveModeChanged: (Boolean) -> Unit,
    isFullSpace: Boolean = false,
    spaceToggleAvailable: Boolean = false,
    onToggleSpace: () -> Unit = {},
    selectedEnvironmentId: String = DEFAULT_ENVIRONMENT_ID,
    onEnvironmentSelected: (String) -> Unit = {},
    onDisconnectAll: () -> Unit,
    isPaired: Boolean = false,
    onForgetMac: () -> Unit = {},
    voiceEnabled: Boolean = false,
    onVoiceEnabledChanged: (Boolean) -> Unit = {},
    voiceAutoSubmit: Boolean = false,
    onVoiceAutoSubmitChanged: (Boolean) -> Unit = {},
    voiceSilenceAutoStop: Boolean = false,
    onVoiceSilenceAutoStopChanged: (Boolean) -> Unit = {},
    voiceCommandsEnabled: Boolean = false,
    onVoiceCommandsEnabledChanged: (Boolean) -> Unit = {},
    hostKeyboardRelayState: HostKeyboardRelayState = HostKeyboardRelayState(),
    windowMirrorState: WindowMirrorState = WindowMirrorState(),
    onFocusWindowMirror: () -> Unit = {},
    onCloseWindowMirror: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (!expanded) {
        CollapsedPanelButton(onExpand = { onExpandedChange(true) }, modifier = modifier)
        return
    }

    var subScreen by remember { mutableStateOf(SettingsSubScreen.Root) }
    if (subScreen == SettingsSubScreen.Theme) {
        ThemePickerPanel(
            activeTheme = activeTheme,
            availableThemes = availableThemes,
            onThemeSelected = onThemeSelected,
            onBack = { subScreen = SettingsSubScreen.Root },
            modifier = modifier,
        )
        return
    }

    // Cards stay in priority order and receive explicit named Grid areas.
    // Conditional cards simply aren't added.
    val cards = buildList {
        add(
            SettingsCard(areaId = "terminals") {
                GxrSection("Terminals", iconRes = R.drawable.gxr_ic_terminals) {
                    sessions.indices.forEach { index ->
                        TerminalRow(
                            index = index,
                            state = sessionStates.getOrNull(index),
                            isFocused = index == focusedPaneId,
                            canRemove = canRemovePane,
                            onSelect = { onPaneSelected(index) },
                            onRemove = { onRemovePane(index) },
                        )
                    }
                    GxrButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onAddPane,
                        enabled = canAddPane,
                        style = GxrButtonStyle.Primary,
                    ) {
                        GxrText(
                            text = if (canAddPane) "+  New terminal" else "Max ${CockpitLifecycle.MAX_PANES} terminals",
                            fontSize = 13.sp,
                        )
                    }
                }
            },
        )

        add(
            SettingsCard(areaId = "appearance") {
                GxrSection("Appearance", iconRes = R.drawable.gxr_ic_appearance) {
                    ThemeNavRow(
                        activeTheme = activeTheme,
                        onClick = { subScreen = SettingsSubScreen.Theme },
                    )
                }
            },
        )

        add(
            SettingsCard(areaId = "terminal-text") {
                GxrSection("Terminal text: ${fontSizeSp.toInt()}pt") {
                    GxrSlider(
                        value = fontSizeSp,
                        onValueChange = onFontSizeChanged,
                        onValueChangeFinished = onFontSizeChangeFinished,
                        valueRange = GhosttySettingsStore.MIN_FONT_SIZE_SP..GhosttySettingsStore.MAX_FONT_SIZE_SP,
                        steps = (GhosttySettingsStore.MAX_FONT_SIZE_SP - GhosttySettingsStore.MIN_FONT_SIZE_SP).toInt() - 1,
                    )
                    GxrText(
                        text = "Smaller fits more · larger reads easier at distance",
                        fontSize = 11.sp,
                        color = GhosttyTokens.OnSurfaceVariant,
                    )
                }
            },
        )

        if (isFullSpace) {
            add(
                SettingsCard(areaId = "terminal-background") {
                    GxrSection("Terminal background: ${terminalTransparencyPercent.toInt()}%") {
                        GxrSlider(
                            value = terminalTransparencyPercent,
                            onValueChange = onTerminalTransparencyChanged,
                            onValueChangeFinished = onTerminalTransparencyChangeFinished,
                            valueRange = 0f..100f,
                            steps = 99,
                        )
                        GxrText(
                            text = "0 = floating text · 100 = solid windows",
                            fontSize = 11.sp,
                            color = GhosttyTokens.OnSurfaceVariant,
                        )
                    }
                },
            )

            add(
                SettingsCard(areaId = "layout") {
                    GxrSection("Layout", iconRes = R.drawable.gxr_ic_layout) {
                        SettingToggleRow(
                            label = "Lock panels",
                            checked = layoutLocked,
                            onCheckedChange = onLayoutLockedChanged,
                        )
                        GxrText(
                            text = "Freezes current panel positions and sizes; enables Alt+1–${CockpitLifecycle.MAX_PANES} pane switch",
                            fontSize = 11.sp,
                            color = GhosttyTokens.OnSurfaceVariant,
                        )
                        SettingToggleRow(
                            label = "Snap panes to surfaces",
                            checked = snapPanesToSurfaces,
                            onCheckedChange = onSnapPanesToSurfacesChanged,
                        )
                        GxrText(
                            text = "Detached panes can stick to desk and wall planes",
                            fontSize = 11.sp,
                            color = GhosttyTokens.OnSurfaceVariant,
                        )
                    }
                },
            )
        }

        if (spaceToggleAvailable) {
            add(
                SettingsCard(areaId = "space") {
                    GxrSection("Space", iconRes = R.drawable.gxr_ic_space) {
                        GxrButton(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(SPACE_TOGGLE_TEST_TAG),
                            onClick = onToggleSpace,
                        ) {
                            GxrText(
                                text = if (isFullSpace) "⇲  Exit to windowed mode" else "⇱  Expand to full space",
                                fontSize = 13.sp,
                            )
                        }
                        GxrText(
                            text = if (isFullSpace) {
                                "Return to a single window alongside your other apps"
                            } else {
                                "Take over the whole space for the multi-pane cockpit"
                            },
                            fontSize = 11.sp,
                            color = GhosttyTokens.OnSurfaceVariant,
                        )
                    }
                },
            )
        }

        if (immersiveAvailable) {
            add(
                SettingsCard(areaId = "focus") {
                    GxrSection("Focus", iconRes = R.drawable.gxr_ic_focus) {
                        SettingToggleRow(
                            label = "Immersive mode",
                            checked = immersiveMode,
                            onCheckedChange = onImmersiveModeChanged,
                        )
                        GxrText(
                            text = "Dim the real world to focus on your terminals",
                            fontSize = 11.sp,
                            color = GhosttyTokens.OnSurfaceVariant,
                        )
                        if (immersiveMode) {
                            EnvironmentPicker(
                                selectedId = selectedEnvironmentId,
                                onSelected = onEnvironmentSelected,
                            )
                        }
                    }
                },
            )
        }

        add(
            SettingsCard(areaId = "voice") {
                VoicePanelSection(
                    voiceEnabled = voiceEnabled,
                    onVoiceEnabledChanged = onVoiceEnabledChanged,
                    voiceAutoSubmit = voiceAutoSubmit,
                    onVoiceAutoSubmitChanged = onVoiceAutoSubmitChanged,
                    voiceSilenceAutoStop = voiceSilenceAutoStop,
                    onVoiceSilenceAutoStopChanged = onVoiceSilenceAutoStopChanged,
                    voiceCommandsEnabled = voiceCommandsEnabled,
                    onVoiceCommandsEnabledChanged = onVoiceCommandsEnabledChanged,
                )
            },
        )

        if (isFullSpace) {
            add(
                SettingsCard(areaId = "voice-orb") {
                    GxrSection("Voice orb", iconRes = R.drawable.gxr_ic_voice_pet) {
                        GxrText(
                            text = "Palm-up settles the voice orb onto your hand and opens the mic; " +
                                "speak your command, then flip your palm over to send it. Pinch the " +
                                "orb to open settings.",
                            fontSize = 11.sp,
                            color = GhosttyTokens.OnSurfaceVariant,
                        )
                    }
                },
            )
        }

        add(
            SettingsCard(areaId = "mac-keyboard") {
                GxrSection("Mac keyboard", iconRes = R.drawable.gxr_ic_keyboard) {
                    HostKeyboardStatusRow(hostKeyboardRelayState)
                }
            },
        )

        add(
            SettingsCard(areaId = "window-mirror") {
                GxrSection("Mac window mirror", iconRes = R.drawable.gxr_ic_mirror) {
                    WindowMirrorStatusRow(
                        state = windowMirrorState,
                        onFocus = onFocusWindowMirror,
                        onClose = onCloseWindowMirror,
                    )
                }
            },
        )

        add(
            SettingsCard(areaId = "session") {
                GxrSection("Session", iconRes = R.drawable.gxr_ic_session) {
                    GxrButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onDisconnectAll,
                        style = GxrButtonStyle.DangerFilled,
                    ) {
                        GxrText("Disconnect all", fontSize = 13.sp)
                    }
                    if (isPaired) {
                        GxrButton(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(FORGET_MAC_TEST_TAG),
                            onClick = onForgetMac,
                            style = GxrButtonStyle.Danger,
                        ) {
                            GxrText("Forget this Mac", fontSize = 13.sp)
                        }
                        GxrText(
                            text = "Removes the saved key and returns to QR pairing",
                            fontSize = 11.sp,
                            color = GhosttyTokens.OnSurfaceVariant,
                        )
                    }
                }
            },
        )
    }

    GxrPanel(
        modifier = modifier
            .fillMaxSize()
            .testTag(SETTINGS_PANEL_TEST_TAG),
        shape = RoundedCornerShape(GhosttyTokens.PaneCorner),
        color = GhosttyTokens.Surface,
        borderColor = GhosttyTokens.Outline,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val columnCount = SettingsPanelLayout.columnCount(maxWidth.value)
            val placements = SettingsPanelLayout.placements(
                cards.map(SettingsCard::areaId),
                columnCount,
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                GxrPanelHeader(onMinimize = { onExpandedChange(false) })
                Grid(
                    config = {
                        repeat(columnCount) { column(1f.fr) }
                        repeat(SettingsPanelLayout.rowCount(cards.size, columnCount)) {
                            row(GridTrackSize.Auto)
                        }
                        placements.forEach { placement ->
                            area(
                                areaId = placement.areaId,
                                row = placement.row,
                                column = placement.column,
                            )
                        }
                        gap(16.dp)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    cards.forEachIndexed { index, card ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .gridItem(areaId = placements[index].areaId),
                        ) {
                            card.content()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HostKeyboardStatusRow(state: HostKeyboardRelayState) {
    val presentation = when (state.status) {
        HostKeyboardRelayStatus.WaitingForPairing -> StatusPresentation(
            "Waiting for paired Mac",
            state.detail ?: "Pair this headset with a Mac before using host keyboard forwarding.",
            GhosttyTokens.OnSurfaceVariant,
        )
        HostKeyboardRelayStatus.Connecting -> StatusPresentation(
            "Connecting",
            state.detail ?: "Opening the host keyboard tunnel over SSH.",
            GhosttyTokens.OnSurfaceVariant,
        )
        HostKeyboardRelayStatus.WaitingForCompanion -> StatusPresentation(
            "Waiting for companion",
            state.detail ?: "Open Orb XR on the Mac; terminal sessions continue normally.",
            GhosttyTokens.OnSurfaceVariant,
        )
        HostKeyboardRelayStatus.Connected -> StatusPresentation(
            "Ready",
            state.detail ?: "Arm keyboard forwarding from the Mac companion menu.",
            GhosttyTokens.Accent,
        )
        HostKeyboardRelayStatus.Capturing -> StatusPresentation(
            "Forwarding",
            state.detail ?: "Mac keyboard input is routed to the focused XR terminal.",
            GhosttyTokens.Online,
        )
        HostKeyboardRelayStatus.PermissionNeeded -> StatusPresentation(
            "Permission needed",
            state.detail ?: "Grant Input Monitoring to Orb XR in macOS Privacy & Security.",
            GhosttyTokens.Warning,
        )
        HostKeyboardRelayStatus.Error -> StatusPresentation(
            "Error",
            state.detail ?: "Keyboard relay failed; terminal sessions are unaffected.",
            GhosttyTokens.Warning,
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(HOST_KEYBOARD_STATUS_TEST_TAG),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        GxrText(text = presentation.label, fontSize = 13.sp, color = presentation.color)
        GxrText(text = presentation.detail, fontSize = 11.sp, color = GhosttyTokens.OnSurfaceVariant)
    }
}

@Composable
private fun WindowMirrorStatusRow(
    state: WindowMirrorState,
    onFocus: () -> Unit,
    onClose: () -> Unit,
) {
    val presentation = when (state.status) {
        WindowMirrorStatus.WaitingForPairing -> StatusPresentation(
            "Waiting for paired Mac",
            state.detail ?: "Pair this headset with a Mac before mirroring windows.",
            GhosttyTokens.OnSurfaceVariant,
        )
        WindowMirrorStatus.Connecting -> StatusPresentation(
            "Connecting",
            state.detail ?: "Negotiating the mirror session.",
            GhosttyTokens.OnSurfaceVariant,
        )
        WindowMirrorStatus.WaitingForCompanion -> StatusPresentation(
            "Waiting for companion",
            state.detail ?: "Open Orb XR on the Mac.",
            GhosttyTokens.OnSurfaceVariant,
        )
        WindowMirrorStatus.WaitingForWindow -> StatusPresentation(
            "Ready",
            state.detail ?: "Choose Bring Window into XR from the Mac companion menu.",
            GhosttyTokens.Accent,
        )
        WindowMirrorStatus.Streaming -> StatusPresentation(
            state.metadata?.displayTitle?.ifBlank { null } ?: "Mirroring window",
            "${state.transport.label} · ${
                state.detail ?: if (state.interactive) {
                    "Interactive mirror is live."
                } else {
                    "View-only until Accessibility is granted on the Mac."
                }
            }",
            GhosttyTokens.Online,
        )
        WindowMirrorStatus.PermissionNeeded -> StatusPresentation(
            "Permission needed",
            state.detail ?: "Grant Screen Recording or Accessibility on the Mac.",
            GhosttyTokens.Warning,
        )
        WindowMirrorStatus.Error -> StatusPresentation(
            "Error",
            state.detail ?: "Window mirror failed; terminal sessions are unaffected.",
            GhosttyTokens.Warning,
        )
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GxrText(text = presentation.label, fontSize = 13.sp, color = presentation.color)
        GxrText(text = presentation.detail, fontSize = 11.sp, color = GhosttyTokens.OnSurfaceVariant)
        if (state.hasWindow) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GxrButton(
                    modifier = Modifier.weight(1f),
                    onClick = onFocus,
                    style = GxrButtonStyle.Primary,
                ) {
                    GxrText("Focus mirror", fontSize = 12.sp)
                }
                GxrButton(
                    modifier = Modifier.weight(1f),
                    onClick = onClose,
                    style = GxrButtonStyle.Danger,
                ) {
                    GxrText("Close", fontSize = 12.sp)
                }
            }
        }
    }
}

private val WindowMirrorTransport.label: String
    get() = when (this) {
        WindowMirrorTransport.SshTunnel -> "SSH signaling"
        WindowMirrorTransport.DirectTcp -> "Direct video"
    }

@Composable
private fun CollapsedPanelButton(onExpand: () -> Unit, modifier: Modifier = Modifier) {
    GxrPanel(
        modifier = modifier
            .size(64.dp)
            .testTag(SETTINGS_PANEL_TEST_TAG),
        shape = CircleShape,
        color = GhosttyTokens.Surface,
        borderColor = GhosttyTokens.Accent.copy(alpha = 0.35f),
    ) {
        GxrIconButton(
            onClick = onExpand,
            size = 64.dp,
            modifier = Modifier.semantics { contentDescription = "Open controls" },
        ) {
            GxrText(text = "❯_", fontSize = 20.sp, color = GhosttyTokens.Accent)
        }
    }
}

@Composable
internal fun SettingToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GxrText(label, fontSize = 13.sp)
        GxrSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun TerminalRow(
    index: Int,
    state: SessionState?,
    isFocused: Boolean,
    canRemove: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
) {
    GxrPanel(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        color = if (isFocused) GhosttyTokens.Accent.copy(alpha = 0.08f) else Color.Transparent,
        borderColor = if (isFocused) GhosttyTokens.Accent else GhosttyTokens.Outline.copy(alpha = 0.4f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(state?.let(::statusColor) ?: GhosttyTokens.OnSurfaceFaint),
                )
                GxrText(
                    text = "Terminal ${index + 1}",
                    fontSize = 13.sp,
                    color = if (isFocused) GhosttyTokens.Accent else GhosttyTokens.OnSurface,
                )
            }
            GxrIconButton(
                onClick = onRemove,
                enabled = canRemove,
                size = 32.dp,
                modifier = Modifier.semantics { contentDescription = "Remove terminal ${index + 1}" },
            ) {
                GxrText(
                    text = "✕",
                    fontSize = 15.sp,
                    color = if (canRemove) GhosttyTokens.Danger else GhosttyTokens.OnSurfaceFaint,
                )
            }
        }
    }
}
