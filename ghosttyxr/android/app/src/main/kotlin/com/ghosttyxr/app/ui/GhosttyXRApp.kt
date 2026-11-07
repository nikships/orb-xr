package com.ghosttyxr.app.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.xr.compose.platform.LocalSession
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.xr.compose.platform.requestFullSpace
import androidx.xr.compose.platform.requestHomeSpace
import androidx.xr.runtime.Config
import androidx.xr.runtime.DeviceTrackingMode
import androidx.xr.runtime.SessionConfigureSuccess
import androidx.xr.scenecore.GltfModel
import androidx.xr.scenecore.scene
import com.ghosttyxr.app.applyImmersiveEnvironment
import com.ghosttyxr.app.environmentForId
import com.ghosttyxr.app.intro.OnboardingCoach
import com.ghosttyxr.app.terminal.hands.AgentOrbConfig
import com.ghosttyxr.app.terminal.hands.GhosttyBrandPalette
import com.ghosttyxr.app.terminal.hands.HandMenuController
import com.ghosttyxr.app.terminal.hands.OrbArchetype
import com.ghosttyxr.app.terminal.hands.SpatialVoicePetSurface
import com.ghosttyxr.app.terminal.hostkeyboard.HostKeyboardRelayState
import com.ghosttyxr.app.terminal.input.PaneInputController
import com.ghosttyxr.app.terminal.session.CockpitLifecycle
import com.ghosttyxr.app.terminal.session.CockpitState
import com.ghosttyxr.app.terminal.session.SessionState
import com.ghosttyxr.app.terminal.session.SessionSummary
import com.ghosttyxr.app.terminal.settings.GhosttySettingsStore
import com.ghosttyxr.app.terminal.spatial.AdaptivePaneGrid
import com.ghosttyxr.app.terminal.spatial.AdaptiveTerminalPanel
import com.ghosttyxr.app.terminal.spatial.PaneContent
import com.ghosttyxr.app.terminal.ssh.PairedMacStore
import com.ghosttyxr.app.terminal.theme.GhosttyThemeRegistry
import com.ghosttyxr.app.terminal.voice.command.LiveDictationController
import com.ghosttyxr.app.billing.BillingUiState
import com.ghosttyxr.app.billing.EntitlementStore
import com.ghosttyxr.app.terminal.voice.command.VoiceCommandController
import com.ghosttyxr.app.terminal.windowmirror.SymbiosisPointerOwner
import com.ghosttyxr.app.terminal.windowmirror.WindowMirrorRelayController
import com.ghosttyxr.app.terminal.windowmirror.WindowMirrorState
import com.ghosttyxr.app.ui.pairing.PairingScannerPanel
import com.ghosttyxr.app.ui.pairing.pairingStatusText
import com.ghosttyxr.app.ui.pane.PaneSurface
import com.ghosttyxr.app.ui.pane.WindowMirrorSurface
import com.ghosttyxr.app.ui.panel.TerminalControlPanel
import com.ghosttyxr.app.ui.pointer.CockpitSpace
import com.ghosttyxr.app.ui.pointer.XrCursorOverlay
import com.ghosttyxr.app.ui.pointer.XrPointerRouter
import com.ghosttyxr.app.ui.resume.SessionResumePanel
import com.ghosttyxr.app.util.GxrLog
import com.ghosttyxr.app.wiring.buildVoicePetSurface
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val TAG = "GhosttyXRApp"

/** Collects a nullable controller flow, falling back to [default] when the controller is absent. */
@Composable
private fun <T> StateFlow<T>?.collectAsStateOr(default: T): T =
    if (this != null) collectAsState().value else default

@Composable
fun GhosttyXRApp(
    cockpit: CockpitLifecycle,
    inputController: PaneInputController = remember { PaneInputController(cockpit) },
    layoutLocked: Boolean = false,
    onLayoutLockedChanged: (Boolean) -> Unit = {},
    focusedPaneId: Int = 0,
    onFocusedPaneChanged: (Int) -> Unit = {},
    panelExpanded: Boolean = true,
    onPanelExpandedChanged: (Boolean) -> Unit = {},
    onResumeSession: (String) -> Unit = {},
    onStartFreshSession: () -> Unit = {},
    onEndSession: (String) -> Unit = {},
    onEndAllSessions: () -> Unit = {},
    spatialEnabledOverride: Boolean? = null,
    // Forget the paired Mac: disconnect and clear the persisted key so the
    // headset returns to QR pairing. No-op default for tests/previews.
    onForgetMac: () -> Unit = {},
    // Voice dictation controller (Live transcriptions → focused pane). Null in previews/tests.
    voiceController: LiveDictationController? = null,
    // Voice **command** controller (cockpit control). Null in previews/tests.
    voiceCommandController: VoiceCommandController? = null,
    // Toggles voice command mode (enables Alt+Space / hand-menu capture). No-op default for previews.
    onVoiceCommandsEnabledChanged: (Boolean) -> Unit = {},
    // Palm-up voice pet (gesture-summoned, always-listening companion). Null in previews/tests.
    handMenuController: HandMenuController? = null,
    // The pet was pinch-selected: discard the recording (settings, not speech).
    onVoicePetSelected: () -> Unit = {},
    // The palm pet surface was (re)built in Full Space: it carries the spatialized Live speech
    // output MainActivity's voice session should speak through. No-op default for previews.
    onVoicePetSurfaceReady: (SpatialVoicePetSurface?) -> Unit = {},
    // Reports the live immersive capability so the voice command layer can gate on it.
    onImmersiveAvailabilityChanged: (Boolean) -> Unit = {},
    // Reports live Home Space / Full Space so session capture can persist the mode.
    onFullSpaceChanged: (Boolean) -> Unit = {},
    // After session resume: request Full Space once when the XR scene is ready.
    pendingFullSpaceRestore: Boolean = false,
    onPendingFullSpaceRestoreConsumed: () -> Unit = {},
    hostKeyboardRelayState: StateFlow<HostKeyboardRelayState>? = null,
    windowMirrorController: WindowMirrorRelayController? = null,
    // Pro subscription paywall state + actions. No-op defaults for previews/tests.
    billingState: BillingUiState = BillingUiState.Idle,
    onUpgradeToPro: () -> Unit = {},
    onManageSubscription: () -> Unit = {},
    onRestorePurchase: () -> Unit = {},
    onboardingCoachVisible: Boolean = false,
    onOnboardingComplete: () -> Unit = {},
    onCoachNarrationRequested: (Int?) -> Unit = {},
) {
    val cockpitState by cockpit.state.collectAsState()
    val activeCockpit = cockpitState as? CockpitState.Active
    val sessions = activeCockpit?.panes.orEmpty()
    val resumeSessions = (cockpitState as? CockpitState.ResumePicker)?.sessions.orEmpty()
    val focusPaneAt: (Int) -> Unit = { index ->
        sessions.getOrNull(index)?.let { pane -> cockpit.focusPane(pane.id) }
        onFocusedPaneChanged(index)
    }
    val availableThemes by GhosttyThemeRegistry.themes.collectAsState()
    val activeTheme by GhosttyThemeRegistry.active.collectAsState()
    val terminalTransparencyPercent by GhosttySettingsStore.backgroundTransparencyPercent.collectAsState()
    val fontSizeSp by GhosttySettingsStore.fontSizeSp.collectAsState()
    val immersiveMode by GhosttySettingsStore.immersiveMode.collectAsState()
    val selectedEnvironmentId by GhosttySettingsStore.selectedEnvironmentId.collectAsState()
    val voiceEnabled by GhosttySettingsStore.voiceEnabled.collectAsState()
    val voiceAutoSubmit by GhosttySettingsStore.voiceAutoSubmit.collectAsState()
    val voiceSilenceAutoStop by GhosttySettingsStore.voiceSilenceAutoStop.collectAsState()
    val voiceCommandsEnabled by GhosttySettingsStore.voiceCommandsEnabled.collectAsState()
    val snapPanesToSurfaces by GhosttySettingsStore.snapPanesToSurfaces.collectAsState()
    val entitlement by EntitlementStore.entitlement.collectAsState()
    val hostKeyboardState = hostKeyboardRelayState.collectAsStateOr(HostKeyboardRelayState())
    val windowMirrorState = windowMirrorController?.state.collectAsStateOr(WindowMirrorState())
    val mirrorVisible = windowMirrorController != null && windowMirrorState.hasWindow
    // Loaded sky-dome glbs, kept across toggles so re-selecting is instant.
    val environmentModels = remember { mutableMapOf<String, GltfModel>() }

    // Symbiosis: virtual XR cursor driven by the Mac's mouse during a full-screen
    // share. The router only exists while a display share is streaming.
    val symbiosisActive = windowMirrorController != null && mirrorVisible && windowMirrorState.isDisplayShare
    // Suppress inadvertent hand-pinch input (pane focus changes, Mac clicks via the
    // mirror) while a display share is active — except when the palm-up hand menu is
    // summoned, where deliberate pinch interaction is expected.
    val handMenuVisible = handMenuController?.isMenuVisible.collectAsStateOr(false)
    val suppressHandInput = symbiosisActive && !handMenuVisible
    val pointerRouter = if (symbiosisActive) {
        checkNotNull(windowMirrorController)
        // Router routes semantically via callbacks; keep them reading live state.
        val currentSessions by rememberUpdatedState(sessions)
        val currentOnFocus by rememberUpdatedState(focusPaneAt)
        val router = remember(windowMirrorController) {
            XrPointerRouter(
                onPointerReturn = windowMirrorController::sendPointerReturn,
                onTargetClicked = { targetId ->
                    CockpitSpace.paneIndexOf(targetId)?.let(currentOnFocus)
                },
                onTargetScrolled = { targetId, lines ->
                    CockpitSpace.paneIndexOf(targetId)?.let { index ->
                        currentSessions.getOrNull(index)?.scroll(lines)
                    }
                },
            )
        }
        // Keep the logical cockpit layout in sync with the pane count. The settings
        // control panel is deliberately excluded (hasPanel = false) so the Mac's
        // forwarded pointer never roams onto it while sharing its screen in XR.
        LaunchedEffect(router, sessions.size) {
            router.updateLayout(
                CockpitSpace.layout(
                    terminalPaneCount = sessions.size,
                    hasMirror = true,
                    hasPanel = false,
                ),
            )
        }
        // Feed relay pointer events into the router on the main thread.
        LaunchedEffect(router, windowMirrorController) {
            windowMirrorController.pointerEvents.collect(router::handle)
        }
        // Dismiss the cursor whenever the Mac owns the pointer (return, emergency
        // chord, disconnect).
        LaunchedEffect(router, windowMirrorController) {
            windowMirrorController.state.collect { state ->
                if (state.pointerOwner == SymbiosisPointerOwner.Mac) router.deactivate()
            }
        }
        router
    } else {
        null
    }

    // Keep focus in range when panes are removed.
    LaunchedEffect(sessions.size, mirrorVisible) {
        val paneCount = sessions.size + if (mirrorVisible) 1 else 0
        if (paneCount > 0 && focusedPaneId > paneCount - 1) {
            focusPaneAt(paneCount - 1)
        }
    }

    // Immersive "focus mode": replace the passthrough view of the real room
    // with a black skybox so the terminals are all you see, and hand control
    // back to the system when off. On Galaxy XR (video passthrough) this dims
    // the world; on optical see-through glasses passthrough/environment control
    // is unavailable, so the toggle is hidden. Both halves are *preferences*
    // that only apply while the matching capability is live, hence the gate.
    val xrSession = LocalSession.current
    val spatialCapabilities = LocalSpatialCapabilities.current
    val immersiveAvailable = spatialCapabilities.isPassthroughControlEnabled &&
        spatialCapabilities.isAppEnvironmentEnabled
    // Hoist the capability out of composition so the "toggle immersive" voice command (which runs
    // on the pipeline, not in Compose) can gate on the live value.
    LaunchedEffect(immersiveAvailable) { onImmersiveAvailabilityChanged(immersiveAvailable) }

    // Home Space ↔ Full Space toggle. Fresh cockpits launch directly into Full
    // Space (the immersive cockpit is the product default); [DefaultCockpitLifecycle]
    // primes `pendingFullSpaceRestore` on connectFresh, which the effect below
    // honors once the XR scene is ready. Resumed sessions restore whichever mode
    // was saved. The user toggles between modes from the control panel.
    // `isSpatialUiEnabled` tracks the live mode, so the button label flips
    // automatically after the system completes the transition. Hidden when
    // there's no XR session (previews, phones) or when tests pin the mode via
    // [spatialEnabledOverride].
    val isFullSpace = spatialCapabilities.isSpatialUiEnabled
    val spaceToggleAvailable = spatialEnabledOverride == null && xrSession != null
    // Hoist Full Space for session snapshot capture (same pattern as immersiveAvailable).
    LaunchedEffect(isFullSpace) { onFullSpaceChanged(isFullSpace) }
    // Space transitions go through the alpha16 suspending Activity extensions, which await the
    // system's transition and report a SpaceRequestResult instead of the old fire-and-forget
    // Scene.requestFullSpace(). That turns "assume it worked" into a verified outcome — see
    // [SpaceTransitions] for how the result maps onto the restore latch.
    // LocalActivity is null in previews and tests, so the existing no-host path
    // remains safe without assuming that an arbitrary LocalContext is an Activity.
    val spaceActivity = LocalActivity.current as? ComponentActivity
    // Session resume requested Full Space: apply once the XR scene can honor it.
    LaunchedEffect(
        pendingFullSpaceRestore,
        xrSession,
        spaceToggleAvailable,
        isFullSpace,
        spaceActivity,
    ) {
        if (!pendingFullSpaceRestore) return@LaunchedEffect
        if (xrSession?.scene == null || !spaceToggleAvailable) return@LaunchedEffect
        if (spaceActivity == null) {
            // No ComponentActivity host (previews/tests): nothing can honor the restore, and
            // holding the latch would re-run this effect forever.
            onPendingFullSpaceRestoreConsumed()
            return@LaunchedEffect
        }
        if (!isFullSpace) {
            onPanelExpandedChanged(false)
            val result = spaceActivity.requestFullSpace()
            if (SpaceTransitions.isFailure(result)) {
                GxrLog.w(TAG, "Full Space restore did not apply: $result")
                // Leave the panel visible so the user isn't stranded in Home Space with the
                // settings surface collapsed after a failed immersive restore.
                onPanelExpandedChanged(true)
            }
            if (!SpaceTransitions.shouldConsumeRestore(result)) return@LaunchedEffect
        }
        onPendingFullSpaceRestoreConsumed()
    }
    val spaceScope = rememberCoroutineScope()
    val onToggleSpace: () -> Unit = {
        val activity = spaceActivity
        if (activity != null && xrSession?.scene != null) {
            spaceScope.launch {
                val result = if (isFullSpace) {
                    activity.requestHomeSpace()
                } else {
                    onPanelExpandedChanged(false)
                    activity.requestFullSpace()
                }
                if (SpaceTransitions.isFailure(result)) {
                    GxrLog.w(TAG, "space toggle rejected: $result")
                    // The transition didn't happen, so undo the pre-emptive panel collapse
                    // rather than leaving the user in Home Space with no settings surface.
                    if (!isFullSpace) onPanelExpandedChanged(true)
                }
            }
        }
    }

    // Enable head/device tracking on the session. The `rotateToLookAtUser` modifier on movable
    // panes and the hand menu's "face me" billboard read the user's head pose via ARCore's
    // ArDevice, which is only initialized when the session's deviceTracking is not DISABLED.
    // DeviceTrackingMode.SPATIAL requires the dangerous HEAD_TRACKING permission on immersive
    // devices (requested once at app startup). Re-check on resume so a grant from the startup
    // dialog is picked up without launching a second mid-flow prompt. Degrades gracefully if
    // denied or unsupported.
    val headTrackingContext = LocalContext.current
    var headTrackingGranted by remember {
        mutableStateOf(headTrackingContext.hasPermission(HEAD_TRACKING_PERMISSION))
    }
    LifecycleResumeEffect(Unit) {
        headTrackingGranted = headTrackingContext.hasPermission(HEAD_TRACKING_PERMISSION)
        onPauseOrDispose {}
    }
    LaunchedEffect(xrSession, headTrackingGranted) {
        val session = xrSession ?: return@LaunchedEffect
        if (!headTrackingGranted) {
            GxrLog.d(TAG) { "HEAD_TRACKING not granted; panes won't self-orient" }
            return@LaunchedEffect
        }
        if (session.config.deviceTracking == DeviceTrackingMode.DISABLED) {
            runCatching {
                val newConfig = Config.Builder(session.config)
                    .setDeviceTracking(DeviceTrackingMode.SPATIAL)
                    .build()
                session.configure(newConfig) is SessionConfigureSuccess
            }.onFailure {
                GxrLog.w(TAG, "device tracking configure failed; panes won't self-orient", it)
            }
        }
    }
    LaunchedEffect(immersiveMode, selectedEnvironmentId, xrSession, immersiveAvailable) {
        applyImmersiveEnvironment(
            session = xrSession,
            immersive = immersiveMode,
            environment = environmentForId(selectedEnvironmentId),
            modelCache = environmentModels,
        )
    }

    val themeContext = LocalContext.current
    LaunchedEffect(activeTheme) {
        val name = activeTheme ?: return@LaunchedEffect
        val theme = GhosttyThemeRegistry.getTheme(themeContext, name) ?: return@LaunchedEffect
        sessions.forEach { it.setColors(theme) }
    }

    // Palm-following voice pet: configure hand tracking + watch the secondary hand once we have an
    // XR session and the feature is enabled. The controller no-ops where hand tracking is
    // unavailable (emulator, glasses), so the keyboard hotkey stays the way in.
    val handMenuEnabled by GhosttySettingsStore.handMenuEnabled.collectAsState()
    // Hand tracking is a dangerous, runtime-granted permission (requested once at app startup).
    // Without it, configuring hand tracking on the session throws SecurityException and the
    // palm-up menu silently disables. Re-check on resume so a startup grant is picked up.
    val handMenuContext = LocalContext.current
    var handTrackingGranted by remember {
        mutableStateOf(handMenuContext.hasPermission(HAND_TRACKING_PERMISSION))
    }
    LifecycleResumeEffect(Unit) {
        handTrackingGranted = handMenuContext.hasPermission(HAND_TRACKING_PERMISSION)
        onPauseOrDispose {}
    }
    val handMenuScope = rememberCoroutineScope()
    val orbConfig = remember {
        AgentOrbConfig(
            seed = "ghosttyxr",
            palette = GhosttyBrandPalette,
            archetype = OrbArchetype.Spiral,
        )
    }
    // Keyed on isFullSpace: the pet's entities live in the scene graph, which only
    // renders in Full Space — skip it entirely while running as a Home Space window and
    // rebuild it when the user expands back out.
    DisposableEffect(handMenuController, xrSession, handMenuEnabled, handTrackingGranted, isFullSpace) {
        val controller = handMenuController
        val session = xrSession
        val commandController = voiceCommandController
        val activity = handMenuContext as? ComponentActivity
        if (controller != null && session != null && handMenuEnabled && isFullSpace &&
            commandController != null && activity != null &&
            handTrackingGranted
        ) {
            val surface = buildVoicePetSurface(
                activity = activity,
                session = session,
                scope = handMenuScope,
                orbConfig = orbConfig,
                voiceState = commandController.state,
                onSelect = {
                    onVoicePetSelected()
                    onPanelExpandedChanged(true)
                },
            )
            // Hand MainActivity the explicit visual + spatial-audio capability.
            onVoicePetSurfaceReady(surface)
            controller.start(session, handMenuScope, surface)
            onDispose {
                controller.stop()
                // Detach the session before disposing the player so late Live frames fall back
                // to the durable non-spatial output instead of writing into a released surface.
                onVoicePetSurfaceReady(null)
                surface.dispose()
            }
        } else {
            controller?.stop()
            onDispose {}
        }
    }

    val isPaired by PairedMacStore.isPaired.collectAsState()
    val sessionStates = sessions.map { it.state.collectAsState().value }
    // Stay in terminal mode through reconnects. A network blip flips every pane
    // to Reconnecting; gating only on Connected would tear the whole grid down
    // and rebuild it on recovery, recreating each SpatialPanel and losing the
    // user-dragged movable positions (panes "snap back to start" + reload).
    val hasActivePane = sessionStates.any { state ->
        state is SessionState.Connected || state is SessionState.Reconnecting
    }

    if (resumeSessions.isNotEmpty()) {
        SessionResumePanel(
            sessions = resumeSessions,
            onResume = onResumeSession,
            onStartFresh = onStartFreshSession,
            onEndSession = onEndSession,
            onEndAllSessions = onEndAllSessions,
            spatialEnabledOverride = spatialEnabledOverride,
        )
        return
    }

    if (!hasActivePane && !mirrorVisible) {
        PairingScannerPanel(
            statusText = pairingStatusText(sessionStates, isPaired),
            errorText = sessionStates.firstNotNullOfOrNull { state ->
                (state as? SessionState.Failed)?.error
            },
            isPaired = isPaired,
            onForgetMac = onForgetMac,
            spatialEnabledOverride = spatialEnabledOverride,
        )
        return
    }

    // In Full Space with a working palm menu, the minimized settings gear vanishes
    // entirely — the hand menu's Settings button is the recall path, so no stray
    // floating icon drifts around the cockpit. When hand tracking is unavailable
    // (emulator, glasses) the gear stays as the only way back into settings.
    val handMenuAvailable = handMenuController?.isAvailable.collectAsStateOr(false)

    AdaptiveTerminalPanel(
        panelExpanded = panelExpanded,
        hideCollapsedPanel = isFullSpace && handMenuAvailable,
        coachContent = if (onboardingCoachVisible) {
            {
                OnboardingCoach(
                    onSpawnPane = {
                        cockpit.addPane(inheritFrom = sessions.getOrNull(focusedPaneId)?.id)
                            ?.let { focusPaneAt(sessions.lastIndex + 1) } != null
                    },
                    onComplete = onOnboardingComplete,
                    // Hands lesson: the coach observes the real lock flag and pet visibility so a
                    // prayer hold / palm-up reveal during the lesson is recognized and acknowledged.
                    layoutLocked = layoutLocked,
                    petVisible = handMenuVisible,
                    gesturesAvailable = handMenuAvailable,
                    onNarrationRequested = onCoachNarrationRequested,
                )
            }
        } else {
            null
        },
        panelContent = {
            TerminalControlPanel(
                expanded = panelExpanded,
                onExpandedChange = onPanelExpandedChanged,
                sessions = sessions,
                sessionStates = sessionStates,
                focusedPaneId = focusedPaneId,
                onPaneSelected = focusPaneAt,
                canAddPane = sessions.size < CockpitLifecycle.MAX_PANES,
                canRemovePane = sessions.size > 1,
                onAddPane = {
                    cockpit.addPane(inheritFrom = sessions.getOrNull(focusedPaneId)?.id)
                        ?.let { focusPaneAt(sessions.lastIndex + 1) }
                },
                onRemovePane = { index -> sessions.getOrNull(index)?.let { cockpit.removePane(it.id) } },
                activeTheme = activeTheme,
                availableThemes = availableThemes,
                layoutLocked = layoutLocked,
                snapPanesToSurfaces = snapPanesToSurfaces,
                onSnapPanesToSurfacesChanged = GhosttySettingsStore::setSnapPanesToSurfaces,
                terminalTransparencyPercent = terminalTransparencyPercent,
                fontSizeSp = fontSizeSp,
                onThemeSelected = GhosttyThemeRegistry::setActive,
                onLayoutLockedChanged = onLayoutLockedChanged,
                onTerminalTransparencyChanged = GhosttySettingsStore::setBackgroundTransparencyPercent,
                onTerminalTransparencyChangeFinished = GhosttySettingsStore::persistBackgroundTransparencyPercent,
                onFontSizeChanged = GhosttySettingsStore::setFontSizeSp,
                onFontSizeChangeFinished = GhosttySettingsStore::persistFontSizeSp,
                immersiveMode = immersiveMode,
                immersiveAvailable = immersiveAvailable,
                onImmersiveModeChanged = GhosttySettingsStore::setImmersiveMode,
                isFullSpace = isFullSpace,
                spaceToggleAvailable = spaceToggleAvailable,
                onToggleSpace = onToggleSpace,
                selectedEnvironmentId = selectedEnvironmentId,
                onEnvironmentSelected = GhosttySettingsStore::setSelectedEnvironmentId,
                onDisconnectAll = cockpit::disconnectAllPanes,
                isPaired = isPaired,
                onForgetMac = onForgetMac,
                voiceEnabled = voiceEnabled,
                onVoiceEnabledChanged = GhosttySettingsStore::setVoiceEnabled,
                voiceAutoSubmit = voiceAutoSubmit,
                onVoiceAutoSubmitChanged = GhosttySettingsStore::setVoiceAutoSubmit,
                voiceSilenceAutoStop = voiceSilenceAutoStop,
                onVoiceSilenceAutoStopChanged = GhosttySettingsStore::setVoiceSilenceAutoStop,
                voiceCommandsEnabled = voiceCommandsEnabled,
                isPro = entitlement.isPro(),
                billingState = billingState,
                onUpgradeToPro = onUpgradeToPro,
                onManageSubscription = onManageSubscription,
                onRestorePurchase = onRestorePurchase,
                onVoiceCommandsEnabledChanged = onVoiceCommandsEnabledChanged,
                hostKeyboardRelayState = hostKeyboardState,
                windowMirrorState = windowMirrorState,
                onFocusWindowMirror = {
                    if (mirrorVisible) onFocusedPaneChanged(sessions.size)
                },
                onCloseWindowMirror = { windowMirrorController?.closeMirror() },
            )
        },
        spatialEnabledOverride = spatialEnabledOverride,
    ) {
        val terminalPanes = sessions.map { ref ->
            PaneContent { paneId ->
                Box(modifier = Modifier.fillMaxSize()) {
                    PaneSurface(
                        pane = ref,
                        paneId = paneId,
                        inputController = inputController,
                        isFocused = paneId == focusedPaneId,
                        backgroundOpacity = terminalTransparencyPercent / 100f,
                        fontSizeSp = fontSizeSp,
                        voiceController = voiceController.takeIf { voiceEnabled },
                    )
                    XrCursorOverlay(pointerRouter, CockpitSpace.paneTarget(paneId))
                }
            }
        }
        val activeMirrorController = windowMirrorController
        val panes = if (mirrorVisible) {
            checkNotNull(activeMirrorController)
            terminalPanes + PaneContent { paneId ->
                // The router never targets the mirror: entering its cockpit-space rect
                // returns the pointer to the Mac.
                WindowMirrorSurface(
                    controller = activeMirrorController,
                    isFocused = paneId == focusedPaneId,
                    onFocus = { onFocusedPaneChanged(paneId) },
                    onClose = { activeMirrorController.closeMirror() },
                    inputEnabled = !suppressHandInput,
                )
            }
        } else {
            terminalPanes
        }
        val paneKeys = sessions.map { it.id.value } + if (mirrorVisible) {
            listOf(windowMirrorPaneKey(windowMirrorState.metadata?.id ?: 0L))
        } else {
            emptyList()
        }
        AdaptivePaneGrid(
            panes = panes,
            panesMovable = !layoutLocked,
            panesResizable = !layoutLocked,
            panesSelectable = !layoutLocked && !suppressHandInput,
            paneKeys = paneKeys,
            snapPanesToSurfaces = snapPanesToSurfaces,
            cockpitPanes = sessions + if (mirrorVisible) {
                listOf(null)
            } else {
                emptyList()
            },
            onPaneFocused = focusPaneAt,
            spatialEnabledOverride = spatialEnabledOverride,
        )
    }
}

internal fun windowMirrorPaneKey(metadataId: Long): Long =
    Long.MIN_VALUE or (metadataId and Long.MAX_VALUE)

private fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
