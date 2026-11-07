package com.ghosttyxr.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.xr.arcore.Hand
import com.ghosttyxr.app.intro.MAC_COMPANION_DOWNLOAD_URL
import com.ghosttyxr.app.intro.OnboardingFlow
import com.ghosttyxr.app.intro.OnboardingNarrationPlayer
import com.ghosttyxr.app.qr.DebugPairing
import com.ghosttyxr.app.qr.PairingPayload
import com.ghosttyxr.app.qr.PendingPairing
import com.ghosttyxr.app.terminal.handoff.TERMINAL_HANDOFF_RELAY_PORT
import com.ghosttyxr.app.terminal.handoff.TerminalHandoffController
import com.ghosttyxr.app.terminal.hands.HandMenuController
import com.ghosttyxr.app.terminal.hostkeyboard.HostKeyboardRelayController
import com.ghosttyxr.app.terminal.input.AnalogScrollAccumulator
import com.ghosttyxr.app.terminal.input.AppShortcutAction
import com.ghosttyxr.app.terminal.input.AppShortcutRouter
import com.ghosttyxr.app.terminal.input.PaneInputController
import com.ghosttyxr.app.terminal.input.dominantVerticalControllerAxis
import com.ghosttyxr.app.terminal.session.CockpitLifecycle
import com.ghosttyxr.app.terminal.session.CockpitState
import com.ghosttyxr.app.terminal.session.DefaultCockpitLifecycle
import com.ghosttyxr.app.terminal.session.PairedCredentials
import com.ghosttyxr.app.terminal.session.SessionSnapshotStore
import com.ghosttyxr.app.terminal.settings.GhosttySettingsStore
import com.ghosttyxr.app.terminal.ssh.MacProvisioner
import com.ghosttyxr.app.terminal.ssh.PairedMacStore
import com.ghosttyxr.app.terminal.ssh.SshKey
import com.ghosttyxr.app.terminal.theme.GhosttyThemeRegistry
import com.ghosttyxr.app.terminal.theme.GhosttyXRTheme
import com.ghosttyxr.app.billing.BillingController
import com.ghosttyxr.app.billing.EntitlementStore
import com.ghosttyxr.app.billing.FirebaseGate
import com.ghosttyxr.app.billing.OnboardingAccessClient
import com.ghosttyxr.app.billing.VerifyPurchaseClient
import com.ghosttyxr.app.terminal.voice.command.LiveCommandSession
import com.ghosttyxr.app.terminal.voice.command.LiveDictationController
import com.ghosttyxr.app.terminal.voice.command.LiveInputLifecycle
import com.ghosttyxr.app.terminal.voice.command.VoiceCommandController
import com.ghosttyxr.app.terminal.windowmirror.WindowMirrorRelayController
import com.ghosttyxr.app.ui.GhosttyXRApp
import com.ghosttyxr.app.util.GxrLog
import com.ghosttyxr.app.wiring.buildVoiceCommandController
import com.ghosttyxr.app.wiring.buildVoiceCommandSession
import com.ghosttyxr.app.wiring.buildVoiceDictationController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private companion object {
        const val TAG = "MainActivity"
    }

    private lateinit var cockpit: CockpitLifecycle
    private lateinit var inputController: PaneInputController
    private lateinit var voiceController: LiveDictationController
    private lateinit var voiceCommandController: VoiceCommandController
    private lateinit var voiceCommandSession: LiveCommandSession
    private lateinit var handMenuController: HandMenuController
    private lateinit var hostKeyboardRelayController: HostKeyboardRelayController
    private lateinit var windowMirrorRelayController: WindowMirrorRelayController
    private lateinit var onboardingNarration: OnboardingNarrationPlayer
    private lateinit var terminalHandoffController: TerminalHandoffController
    private val controllerScroll = AnalogScrollAccumulator()

    // Pro subscription purchase/restore. Lazy so construction happens after onCreate context
    // is ready; verification runs server-side (verifyPurchase function) via VerifyPurchaseClient.
    private val billingController by lazy {
        BillingController(
            context = applicationContext,
            scope = lifecycleScope,
            verifyClient = VerifyPurchaseClient(tokens = FirebaseGate::tokens),
        )
    }

    private val onboardingAccessClient by lazy {
        OnboardingAccessClient(tokens = FirebaseGate::tokens)
    }

    // Drives the first-launch flight plan. The existing intro flag is retained as a migration-safe
    // completion bit so users who already saw the legacy welcome are not forced through setup.
    private val onboardingVisible = mutableStateOf(false)
    private val onboardingCoachVisible = mutableStateOf(false)

    // Hoisted to the Activity so [dispatchKeyEvent] can read lock state and move focus
    // regardless of which Compose/XR node currently holds focus.
    private val layoutLocked = mutableStateOf(false)
    private val focusedPaneId = mutableIntStateOf(0)
    private val panelExpanded = mutableStateOf(false)

    // Live immersive capability, hoisted out of composition (GhosttyXRApp reports it) so the
    // voice-command pipeline can gate the toggle_immersive function on it.
    private val immersiveAvailable = mutableStateOf(false)

    /**
     * Batch-request every dangerous permission at launch so system dialogs don't fire mid-flow
     * (mic hotkey, QR scan, hand/head tracking configure) and glitch XR layout. Feature code still
     * checks grant state; soft re-requests remain as fallbacks when the user denied at startup.
     */
    private val startupPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            GxrLog.d(TAG) {
                "startup permissions: " +
                    grants.entries.joinToString { "${it.key.substringAfterLast('.')}=${it.value}" }
            }
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                if (hasLocalNetworkPermission()) {
                    windowMirrorRelayController.start()
                } else {
                    windowMirrorRelayController.showLocalNetworkPermissionRequired()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestMaxRefreshRate()
        GhosttyThemeRegistry.init(applicationContext)
        GhosttySettingsStore.init(applicationContext)
        EntitlementStore.init(applicationContext)
        onboardingVisible.value = !GhosttySettingsStore.introPlayed.value
        // Play Billing doesn't depend on Firebase Auth: start it directly so product
        // details / restore work even if sign-in is slow or offline. (Purchase
        // verification fetches its own tokens per call via FirebaseGate.tokens.)
        billingController.start()
        // Anonymous sign-in + live entitlement mirror. Off the main thread; voice
        // gates read the cached entitlement until this lands.
        lifecycleScope.launch {
            runCatching {
                val uid = FirebaseGate.ensureSignedIn()
                EntitlementStore.startListening(uid)
                if (onboardingVisible.value) {
                    GhosttySettingsStore.markOnboardingAccessCompletionPending()
                    EntitlementStore.update(onboardingAccessClient.begin())
                } else if (GhosttySettingsStore.onboardingAccessCompletionPending) {
                    completePendingOnboardingAccess()
                }
            }.onFailure { GxrLog.w(TAG, "entitlement bootstrap failed: ${it.message}") }
        }
        focusedPaneId.intValue = GhosttySettingsStore.focusedPaneId.value
        cockpit = DefaultCockpitLifecycle.getInstance(application)
        inputController = PaneInputController(cockpit)
        voiceController = buildVoiceDictationController(
            scope = lifecycleScope,
            cockpit = cockpit,
            focusedPaneId = { focusedPaneId.intValue },
        )
        // Terminal handoff ("send terminals to Mac"): every dependency is the same
        // source the rest of the app uses — the shared VerifiedHostAccess for the
        // direct-tcpip tunnel, PairedMacStore for credentials, SessionSnapshotStore
        // for resume bookkeeping — so the controller itself stays unit-testable.
        terminalHandoffController = TerminalHandoffController(
            loadPairedMac = {
                PairedMacStore.init(applicationContext)
                PairedMacStore.load()
            },
            openTunnel = { mac, key ->
                val payload = mac.toPayload()
                cockpit.hostAccess.connectTunnel(
                    host = payload.host,
                    port = payload.port,
                    username = payload.user,
                    expectedFingerprint = payload.fingerprint,
                    password = "",
                    key = key,
                    targetHost = "127.0.0.1",
                    targetPort = TERMINAL_HANDOFF_RELAY_PORT,
                )
            },
            liveSessions = { cockpit.liveHostSessions() },
            activeCockpitId = { cockpit.currentCockpitId },
            stopCapture = { cockpit.stopCapture() },
            removeSnapshot = SessionSnapshotStore::remove,
            startFreshSnapshot = SessionSnapshotStore::startFresh,
            resetCockpit = { newCockpitId ->
                cockpit.preparePostHandoffCockpit(newCockpitId)
                // Compose state writes belong on the main thread; the handoff runs on IO.
                runOnUiThread { focusPane(0) }
            },
        )
        voiceCommandController = buildVoiceCommandController(
            cockpit = cockpit,
            focusPane = ::focusPane,
            focusedPaneId = { focusedPaneId.intValue },
            immersiveAvailable = { immersiveAvailable.value },
            sendTerminalsToMac = { terminalHandoffController.handoffAll() },
        )
        voiceCommandSession = buildVoiceCommandSession(
            controller = voiceCommandController,
            scope = lifecycleScope,
            cockpit = cockpit,
        )
        // Dictation rides the same Live connection: transcriptions fan out to the pane
        // mic-button controller as well as the command engine. Its button owns only the input
        // turn; stopping it pauses mic emission while the shared receive loop finishes.
        voiceCommandSession.dictation = voiceController
        voiceController.inputLifecycle = object : LiveInputLifecycle {
            override fun startInput() = voiceCommandSession.startIfIdle()
            override fun endInput() = voiceCommandSession.endInput()
        }
        handMenuController = HandMenuController(
            primaryHandSide = { Hand.getPrimaryHandSide(contentResolver) },
            // Palm-down closes microphone emission but leaves receiving/model response alive.
            onPalmFlipDismiss = voiceCommandSession::endInput,
            // Reveal finished = the mic opens. No tap-to-listen step.
            onRevealComplete = ::onVoicePetRevealed,
            // Prayer hold toggles the same session-only lock flag as Settings → Lock panels.
            onLayoutLockToggle = { layoutLocked.value = !layoutLocked.value },
        )
        hostKeyboardRelayController = HostKeyboardRelayController(
            context = applicationContext,
            scope = lifecycleScope,
            inputSink = inputController,
            layoutLocked = { layoutLocked.value },
            triggerVoiceCapture = ::triggerVoiceCapture,
            focusPane = ::focusPane,
            hostAccess = cockpit.hostAccess,
        )
        windowMirrorRelayController = WindowMirrorRelayController(
            context = applicationContext,
            scope = lifecycleScope,
            hostAccess = cockpit.hostAccess,
        )
        requestAllRuntimePermissions()
        onboardingNarration = OnboardingNarrationPlayer(applicationContext)
        // EncryptedSharedPreferences init + load do disk I/O and Keystore-backed
        // crypto, so keep them off the main thread to avoid a startup ANR. The
        // UI starts on the scanner and flips to terminals once auto-connect runs.
        lifecycleScope.launch { maybeAutoReconnect() }
        maybeInjectDebugPairing(intent)
        setContent {
            GhosttyXRTheme {
                val cockpitState by cockpit.state.collectAsState()
                val sessions = (cockpitState as? CockpitState.Active)?.panes.orEmpty()
                val sessionStates by remember(sessions) {
                    if (sessions.isEmpty()) {
                        flowOf(emptyList())
                    } else {
                        combine(sessions.map { it.state }) { states -> states.toList() }
                    }
                }.collectAsState(initial = emptyList())
                val isPaired by PairedMacStore.isPaired.collectAsState()
                val entitlement by EntitlementStore.entitlement.collectAsState()
                val billingState by billingController.uiState.collectAsState()

                // Consume QR results at the Activity boundary so the exact same provisioning path
                // works during first-run onboarding and in the regular reconnect scanner.
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    PendingPairing.flow.collect { payload ->
                        payload?.let {
                            try {
                                onQrPayload(it)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                GxrLog.e(TAG, "Failed to provision or connect QR payload", e)
                            } finally {
                                PendingPairing.clear()
                            }
                        }
                    }
                }

                if (onboardingVisible.value) {
                    OnboardingFlow(
                        isPaired = isPaired,
                        sessionStates = sessionStates,
                        onGetMacCompanion = ::openMacCompanionDownload,
                        onNarrationRequested = { resourceId ->
                            if (resourceId == null) onboardingNarration.stop()
                            else onboardingNarration.play(resourceId)
                        },
                        onEnterCockpit = ::enterOnboardingCockpit,
                        onSkip = ::onOnboardingFinished,
                    )
                } else {
                    GhosttyXRApp(
                        cockpit = cockpit,
                        inputController = inputController,
                        layoutLocked = layoutLocked.value,
                        onLayoutLockedChanged = { layoutLocked.value = it },
                        focusedPaneId = focusedPaneId.intValue,
                        onFocusedPaneChanged = ::focusPane,
                        panelExpanded = panelExpanded.value,
                        onPanelExpandedChanged = { panelExpanded.value = it },
                        onResumeSession = { id -> resumeSession(id) },
                        onStartFreshSession = ::startFreshSession,
                        onEndSession = ::endSession,
                        onEndAllSessions = ::endAllSessions,
                        onForgetMac = ::forgetMac,
                        voiceController = voiceController,
                        voiceCommandController = voiceCommandController,
                        onVoiceCommandsEnabledChanged = ::setVoiceCommandsEnabled,
                        handMenuController = handMenuController,
                        onVoicePetSelected = ::onVoicePetSelected,
                        onVoicePetSurfaceReady = { surface ->
                            if (surface == null) {
                                voiceCommandSession.resetSpeechOutput()
                            } else {
                                // Full Space (re)built the pet: speak subsequent turns through
                                // its spatialized player so the voice comes from the pet entity.
                                voiceCommandSession.speechOutput = surface
                            }
                        },
                        onImmersiveAvailabilityChanged = { immersiveAvailable.value = it },
                        onFullSpaceChanged = ::onFullSpaceChanged,
                        pendingFullSpaceRestore = cockpit.pendingFullSpaceRestore.collectAsState().value,
                        onPendingFullSpaceRestoreConsumed = cockpit::consumePendingFullSpaceRestore,
                        hostKeyboardRelayState = hostKeyboardRelayController.state,
                        windowMirrorController = windowMirrorRelayController,
                        billingState = billingState,
                        onUpgradeToPro = { billingController.launchPurchase(this) },
                        onManageSubscription = ::openManageSubscription,
                        onRestorePurchase = billingController::restorePurchases,
                        onboardingCoachVisible = onboardingCoachVisible.value,
                        onOnboardingComplete = ::onOnboardingFinished,
                        onCoachNarrationRequested = { resourceId ->
                            if (resourceId == null) onboardingNarration.stop()
                            else onboardingNarration.play(resourceId)
                        },
                    )
                }
            }
        }
    }

    /**
     * Ask the compositor for the display's fastest mode (90 Hz on Galaxy XR, which otherwise
     * defaults to 72 Hz). Sets both `preferredDisplayModeId` (exact mode request) and
     * `preferredRefreshRate` (legacy hint) so whichever path the platform honours wins.
     */
    private fun requestMaxRefreshRate() {
        val display = if (Build.VERSION.SDK_INT >= 30) display else @Suppress("DEPRECATION") windowManager.defaultDisplay
        val modes = display?.supportedModes ?: return
        val best = modes.maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.apply {
            preferredDisplayModeId = best.modeId
            preferredRefreshRate = best.refreshRate
        }
        GxrLog.d(TAG) {
            "Requested display mode ${best.modeId} @ ${best.refreshRate} Hz " +
                "(available: ${modes.joinToString { "${it.modeId}:${it.refreshRate}Hz" }})"
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** Open the Play subscription management page (cancel / payment method). */
    private fun openManageSubscription() =
        openUrl(BillingController.MANAGE_URL, "manage-subscription open failed")

    private fun openMacCompanionDownload() =
        openUrl(MAC_COMPANION_DOWNLOAD_URL, "Mac companion download page open failed")

    private fun openUrl(url: String, failureMessage: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure { GxrLog.w(TAG, "$failureMessage: ${it.message}") }
    }

    private fun enterOnboardingCockpit() {
        onboardingNarration.stop()
        onboardingVisible.value = false
        onboardingCoachVisible.value = true
    }

    /** Setup finished (completed or skipped): persist once and reveal the live cockpit. */
    private fun onOnboardingFinished() {
        onboardingNarration.stop()
        GhosttySettingsStore.markIntroPlayed()
        onboardingVisible.value = false
        onboardingCoachVisible.value = false
        GhosttySettingsStore.markOnboardingAccessCompletionPending()
        lifecycleScope.launch {
            runCatching {
                completePendingOnboardingAccess()
            }.onFailure { GxrLog.w(TAG, "onboarding access completion failed: ${it.message}") }
        }
    }

    private suspend fun completePendingOnboardingAccess() {
        EntitlementStore.update(onboardingAccessClient.complete())
        GhosttySettingsStore.clearOnboardingAccessCompletionPending()
    }

    /**
     * Toggle voice command mode (Live session). The session connects lazily on the first
     * reveal/hotkey (mic permission is requested at startup), so enabling just reveals the
     * control; disabling closes the session. Persisted so the panel switch reflects state.
     */
    private fun setVoiceCommandsEnabled(enabled: Boolean) {
        GhosttySettingsStore.setVoiceCommandsEnabled(enabled)
        if (!enabled) {
            voiceCommandSession.reset()
        }
    }

    /**
     * Entry point for the keyboard hotkey (Alt+Space): ensure voice command mode is on, then
     * open the mic via the Live session. RECORD_AUDIO is requested at startup; if still
     * denied, no-op (the user can re-grant from system Settings).
     */
    private fun triggerVoiceCapture() {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            GxrLog.w(TAG, "voice hotkey ignored: RECORD_AUDIO not granted")
            return
        }
        startVoiceCaptureTurn()
    }

    private fun startVoiceCaptureTurn() {
        enableVoiceCommands()
        voiceCommandSession.startIfIdle()
    }

    /** The pet finished its reveal animation: open the mic. Never toggles off an in-flight turn. */
    private fun onVoicePetRevealed() {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            GxrLog.w(TAG, "voice pet listen skipped: RECORD_AUDIO not granted")
            return
        }
        startPetListening()
    }

    private fun startPetListening() {
        enableVoiceCommands()
        voiceCommandSession.startIfIdle()
    }

    private fun enableVoiceCommands() {
        if (!GhosttySettingsStore.voiceCommandsEnabled.value) {
            GhosttySettingsStore.setVoiceCommandsEnabled(true)
        }
    }

    /** The pet was pinch-selected: settings, not speech. Discard the audio, put the pet away. */
    private fun onVoicePetSelected() {
        voiceCommandSession.cancel()
        handMenuController.dismiss()
    }

    override fun onResume() {
        super.onResume()
        billingController.onForeground(this)
        hostKeyboardRelayController.start()
        if (hasLocalNetworkPermission()) {
            windowMirrorRelayController.start()
        } else {
            windowMirrorRelayController.showLocalNetworkPermissionRequired()
        }
    }

    private fun hasLocalNetworkPermission(): Boolean =
        Build.VERSION.SDK_INT < 37 ||
            hasPermission(Manifest.permission.ACCESS_LOCAL_NETWORK)

    /** Fire a single multi-permission system dialog for every missing runtime grant. */
    private fun requestAllRuntimePermissions() {
        val missing = RuntimePermissions.missing(this)
        if (missing.isEmpty()) {
            GxrLog.d(TAG) { "all runtime permissions already granted" }
            return
        }
        GxrLog.d(TAG) {
            "requesting runtime permissions: ${missing.joinToString { it.substringAfterLast('.') }}"
        }
        startupPermissionsLauncher.launch(missing)
    }

    // Never hold the mic or host keyboard relay in the background.
    override fun onPause() {
        super.onPause()
        billingController.onBackground(this)
        hostKeyboardRelayController.stop()
        windowMirrorRelayController.stop()
        voiceController.release()
        voiceCommandSession.reset()
        handMenuController.stop()
        cockpit.flushCapture()
        GhosttySettingsStore.setVoiceCommandsEnabled(false)
        onboardingNarration.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        hostKeyboardRelayController.stop()
        windowMirrorRelayController.release()
        onboardingNarration.release()
        billingController.release()
        EntitlementStore.stopListening()
    }

    /**
     * A QR was just scanned. Provision the Mac for key-based login (generate/
     * reuse the headset key, push it into `~/.ssh/authorized_keys`), persist
     * the pairing, then connect via pubkey so subsequent launches skip the QR.
     * If provisioning fails but the token is still valid, fall back to a
     * one-shot token connection (not persisted) so the user isn't blocked.
     */
    private suspend fun onQrPayload(payload: PairingPayload) {
        val result = withContext(Dispatchers.IO) {
            MacProvisioner(cockpit.hostAccess).provision(payload).also { r ->
                // Persist on IO too — save() encrypts + writes to disk. init()
                // is idempotent, so this is safe even before maybeAutoReconnect.
                r.getOrNull()?.let { provisioned ->
                    PairedMacStore.init(applicationContext)
                    PairedMacStore.save(provisioned.mac, provisioned.key)
                }
            }
        }
        val provisioned = result.getOrNull()
        if (provisioned != null) {
            connectFreshCockpit(provisioned.mac.toPayload(), provisioned.key)
            hostKeyboardRelayController.restart()
            windowMirrorRelayController.restart()
        } else {
            // Couldn't set up passwordless auth — connect this session with the
            // one-time token so the user still gets a terminal now.
            connectFreshCockpit(payload, null)
        }
    }

    /** Forget the paired Mac: tear down sessions and clear the persisted key. */
    private fun forgetMac() {
        cockpit.disconnectAllPanes()
        PairedMacStore.clear()
        hostKeyboardRelayController.restart()
        windowMirrorRelayController.restart()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeInjectDebugPairing(intent)
    }

    private fun focusPane(paneIndex: Int) {
        focusedPaneId.intValue = paneIndex
        activePanes().getOrNull(paneIndex)?.let { pane ->
            inputController.setFocusedPane(pane.id)
        }
        GhosttySettingsStore.setFocusedPaneId(paneIndex)
        cockpit.flushCapture()
    }

    /**
     * Intercept Alt+1..6 to switch the focused pane **while lock mode is on**. Catching it here
     * (rather than per-pane) means it works no matter which panel/widget has focus. Every other
     * key — including Ctrl+C and ordinary typing — falls through to `super` and reaches the
     * focused terminal's normal key path unchanged.
     */
    // ComponentActivity marks dispatchKeyEvent as library-group restricted, but overriding and
    // delegating to the superclass is the standard Activity dispatch hook. Keep suppression local.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (focusedPaneId.intValue >= activePanes().size &&
            windowMirrorRelayController.state.value.hasWindow &&
            windowMirrorRelayController.sendKey(event)
        ) {
            return true
        }
        val locked = layoutLocked.value
        when (val action = AppShortcutRouter.local(event, locked)) {
            AppShortcutAction.ToggleVoiceCapture -> triggerVoiceCapture()
            is AppShortcutAction.FocusPane -> {
                if (action.paneId < activePanes().size) {
                    focusPane(action.paneId)
                }
            }
            null -> Unit
        }
        if (AppShortcutRouter.shouldConsumeLocal(event, locked)) return true
        return super.dispatchKeyEvent(event)
    }

    /** Scroll the focused terminal with either XR controller thumbstick. */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val isJoystick = event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        if (isJoystick && event.action == MotionEvent.ACTION_MOVE) {
            if (focusedPaneId.intValue !in activePanes().indices) {
                controllerScroll.consume(0f, event.eventTime)
                return super.dispatchGenericMotionEvent(event)
            }
            val axis = dominantVerticalControllerAxis(
                y = event.getAxisValue(MotionEvent.AXIS_Y),
                ry = event.getAxisValue(MotionEvent.AXIS_RY),
                rz = event.getAxisValue(MotionEvent.AXIS_RZ),
            )
            val lines = controllerScroll.consume(axis, event.eventTime)
            if (lines != 0) inputController.scroll(lines)
            if (controllerScroll.isActive(axis)) return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    /**
     * If the headset has already paired with a Mac, skip the QR scanner and
     * reconnect straight away using the persisted SSH key. A reachable Mac
     * goes Connecting→Connected and the scanner never shows; an unreachable
     * one (IP moved, Mac off) lands in [SessionState.Failed], and
     * [GhosttyXRApp] surfaces "scan to reconnect" so the user can re-pair.
     */
    private suspend fun maybeAutoReconnect() {
        val paired = withContext(Dispatchers.IO) {
            PairedMacStore.init(applicationContext)
            PairedMacStore.load()
        }
        cockpit.start(paired?.let { (mac, key) -> PairedCredentials(mac.toPayload(), key) })
    }

    private fun startFreshSession() {
        lifecycleScope.launch {
            val paired = loadPairedMac() ?: return@launch
            cockpit.startFresh(PairedCredentials(paired.first.toPayload(), paired.second))
        }
    }

    private fun endSession(id: String) {
        lifecycleScope.launch {
            val paired = loadPairedMac() ?: return@launch
            try {
                withContext(Dispatchers.IO) {
                    cockpit.terminateSession(PairedCredentials(paired.first.toPayload(), paired.second), id)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                GxrLog.w(TAG, "Failed to terminate session $id", error)
            }
        }
    }

    private fun endAllSessions() {
        lifecycleScope.launch {
            val paired = loadPairedMac() ?: return@launch
            try {
                withContext(Dispatchers.IO) {
                    cockpit.terminateAllSessions(PairedCredentials(paired.first.toPayload(), paired.second))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                GxrLog.w(TAG, "Failed to terminate all sessions", error)
            }
        }
    }

    private fun resumeSession(id: String) {
        lifecycleScope.launch {
            val paired = loadPairedMac() ?: return@launch
            cockpit.resume(PairedCredentials(paired.first.toPayload(), paired.second), id)
        }
    }

    /** GhosttyXRApp reports live Home/Full Space so captures include the mode. */
    private fun onFullSpaceChanged(isFullSpace: Boolean) = cockpit.onFullSpaceChanged(isFullSpace)

    private fun connectFreshCockpit(
        payload: PairingPayload,
        key: SshKey?,
    ) = cockpit.startFresh(PairedCredentials(payload, key))

    private suspend fun loadPairedMac() =
        withContext(Dispatchers.IO) { PairedMacStore.load() }

    private fun activePanes() = (cockpit.state.value as? CockpitState.Active)?.panes.orEmpty()

    /**
     * Emulator convenience: there's no camera to scan the Mac's pairing QR, so on
     * debug builds we let `adb` inject the same [com.ghosttyxr.app.qr.PairingPayload]
     * via intent extras (see [DebugPairing]). Compiled out of release builds — the
     * `BuildConfig.DEBUG` branch is statically false there, so release always pairs
     * through the QR scanner.
     */
    private fun maybeInjectDebugPairing(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        DebugPairing.fromIntent(intent)?.let { payload ->
            PendingPairing.set(payload)
        }
    }
}
