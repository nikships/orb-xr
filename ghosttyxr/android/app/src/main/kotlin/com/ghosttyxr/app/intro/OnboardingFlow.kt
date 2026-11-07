package com.ghosttyxr.app.intro

import androidx.annotation.RawRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.width
import com.ghosttyxr.app.R
import com.ghosttyxr.app.qr.QrScannerScreen
import com.ghosttyxr.app.terminal.session.SessionState
import com.ghosttyxr.app.terminal.theme.GhosttyTokens
import com.ghosttyxr.app.ui.ONBOARDING_BACK_TEST_TAG
import com.ghosttyxr.app.ui.ONBOARDING_CONTINUE_TEST_TAG
import com.ghosttyxr.app.ui.ONBOARDING_MUTE_TEST_TAG
import com.ghosttyxr.app.ui.ONBOARDING_ROOT_TEST_TAG
import com.ghosttyxr.app.ui.ONBOARDING_SKIP_TEST_TAG
import com.ghosttyxr.app.ui.chrome.GxrButton
import com.ghosttyxr.app.ui.chrome.GxrButtonStyle
import com.ghosttyxr.app.ui.chrome.GxrPanel
import com.ghosttyxr.app.ui.chrome.GxrText
import androidx.lifecycle.compose.LifecycleResumeEffect

const val MAC_COMPANION_DOWNLOAD_URL = "https://ghosttyxr-headset.web.app"

enum class OnboardingStep(@RawRes val narrationRes: Int) {
    WELCOME(R.raw.onboarding_welcome),
    PAIR(R.raw.onboarding_pair),
    ;

    fun next(): OnboardingStep = entries.getOrElse(ordinal + 1) { PAIR }
    fun previous(): OnboardingStep = entries.getOrElse(ordinal - 1) { WELCOME }
}

/**
 * First-run setup before the live cockpit coaching pass. It teaches the product promise and
 * performs real QR pairing. Narration is prerecorded and bundled; [onNarrationRequested] never
 * performs network work.
 */
@Composable
fun OnboardingFlow(
    isPaired: Boolean,
    sessionStates: List<SessionState>,
    onGetMacCompanion: () -> Unit,
    onNarrationRequested: (Int?) -> Unit,
    onEnterCockpit: () -> Unit,
    onSkip: () -> Unit,
    spatialEnabledOverride: Boolean? = null,
) {
    var step by rememberSaveable { mutableStateOf(OnboardingStep.WELCOME) }
    var narrationEnabled by rememberSaveable { mutableStateOf(true) }
    val isSpatial = spatialEnabledOverride ?: LocalSpatialCapabilities.current.isSpatialUiEnabled
    val connected = sessionStates.any { it is SessionState.Connected }

    LifecycleResumeEffect(step, narrationEnabled) {
        onNarrationRequested(step.narrationRes.takeIf { narrationEnabled })
        onPauseOrDispose { onNarrationRequested(null) }
    }

    val content: @Composable () -> Unit = {
        OnboardingContent(
            step = step,
            narrationEnabled = narrationEnabled,
            isPaired = isPaired,
            connected = connected,
            sessionStates = sessionStates,
            onToggleNarration = { narrationEnabled = !narrationEnabled },
            onBack = { step = step.previous() },
            onContinue = { step = step.next() },
            onGetMacCompanion = onGetMacCompanion,
            onEnterCockpit = onEnterCockpit,
            onSkip = onSkip,
        )
    }

    if (isSpatial) {
        Subspace {
            SpatialPanel(SubspaceModifier.width(1024.dp).height(720.dp)) { content() }
        }
    } else {
        Box(modifier = Modifier.fillMaxSize()) { content() }
    }
}

@Composable
private fun OnboardingContent(
    step: OnboardingStep,
    narrationEnabled: Boolean,
    isPaired: Boolean,
    connected: Boolean,
    sessionStates: List<SessionState>,
    onToggleNarration: () -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onGetMacCompanion: () -> Unit,
    onEnterCockpit: () -> Unit,
    onSkip: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(GhosttyTokens.Accent.copy(alpha = 0.16f), GhosttyTokens.Background),
                    radius = 900f,
                ),
            )
            .padding(24.dp)
            .testTag(ONBOARDING_ROOT_TEST_TAG)
            .semantics { contentDescription = "Orb XR setup" },
    ) {
        GxrPanel(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                OnboardingHeader(
                    step = step,
                    narrationEnabled = narrationEnabled,
                    onToggleNarration = onToggleNarration,
                    onSkip = onSkip,
                )
                Spacer(Modifier.height(20.dp))
                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        (fadeIn() + slideInHorizontally { it / 10 }) togetherWith
                            (fadeOut() + slideOutHorizontally { -it / 12 })
                    },
                    modifier = Modifier.weight(1f),
                    label = "onboarding-step",
                ) { visibleStep ->
                    when (visibleStep) {
                        OnboardingStep.WELCOME -> WelcomeStep()
                        OnboardingStep.PAIR -> PairStep(
                            isPaired = isPaired,
                            connected = connected,
                            sessionStates = sessionStates,
                            onGetMacCompanion = onGetMacCompanion,
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                OnboardingFooter(
                    step = step,
                    connected = connected,
                    onBack = onBack,
                    onContinue = onContinue,
                    onEnterCockpit = onEnterCockpit,
                    onSkip = onSkip,
                )
            }
        }
    }
}

@Composable
private fun OnboardingHeader(
    step: OnboardingStep,
    narrationEnabled: Boolean,
    onToggleNarration: () -> Unit,
    onSkip: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column {
            GxrText("ORB XR", color = GhosttyTokens.Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            GxrText("FIRST FLIGHT  /  ${step.ordinal + 1} OF ${OnboardingStep.entries.size}", color = GhosttyTokens.OnSurfaceFaint, fontSize = 11.sp)
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GxrButton(
                onClick = onToggleNarration,
                modifier = Modifier.testTag(ONBOARDING_MUTE_TEST_TAG),
            ) {
                GxrText(if (narrationEnabled) "Voice on" else "Voice off", fontSize = 12.sp)
            }
            GxrButton(
                onClick = onSkip,
                modifier = Modifier.testTag(ONBOARDING_SKIP_TEST_TAG),
            ) {
                GxrText("Skip tour", fontSize = 12.sp)
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OnboardingStep.entries.forEach { candidate ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(
                        if (candidate.ordinal <= step.ordinal) GhosttyTokens.Accent
                        else GhosttyTokens.Outline,
                    ),
            )
        }
    }
}

@Composable
private fun WelcomeStep() {
    ResponsiveStep(
        primary = {
            StepCopy(
                eyebrow = "YOUR TEAM, AT A GLANCE",
                title = "Step inside the work.",
                body = "Orb XR turns your headset into a room full of real terminals. " +
                    "Give every coding agent a pane, keep every workstream visible, and steer the one that needs you.",
            )
            Spacer(Modifier.height(22.dp))
            MetricRow("01" to "developer", "06" to "live agents", "00" to "hidden tabs")
        },
        secondary = { CockpitConstellation() },
    )
}

@Composable
private fun PairStep(
    isPaired: Boolean,
    connected: Boolean,
    sessionStates: List<SessionState>,
    onGetMacCompanion: () -> Unit,
) {
    ResponsiveStep(
        primary = {
            StepCopy(
                eyebrow = "CONNECT YOUR MAC",
                title = if (connected) "Mac online. Cockpit linked." else "One scan. Your whole dev environment.",
                body = "On your Mac, visit $MAC_COMPANION_DOWNLOAD_URL to download the companion, then install and launch it. " +
                    "If Remote Login is off, the companion explains why it is needed and opens the right System Settings page. " +
                    "Then look at its pairing QR. " +
                    "Your Mac keeps running every shell, toolchain, and agent—even when the headset comes off.",
            )
            Spacer(Modifier.height(18.dp))
            if (!isPaired) {
                GxrButton(onClick = onGetMacCompanion, style = GxrButtonStyle.Primary) {
                    GxrText("Get the Mac companion", fontSize = 13.sp)
                }
                Spacer(Modifier.height(14.dp))
            }
            SetupChecklist(isPaired = isPaired, connected = connected)
            Spacer(Modifier.height(14.dp))
            ConnectionStatus(sessionStates = sessionStates, isPaired = isPaired)
        },
        secondary = {
            GxrPanel(
                modifier = Modifier.fillMaxSize(),
                color = Color.Black,
                borderColor = if (connected) GhosttyTokens.Online else GhosttyTokens.Accent.copy(alpha = 0.7f),
            ) {
                if (connected) ConnectedMacCard() else QrScannerScreen(modifier = Modifier.fillMaxSize())
            }
        },
    )
}

@Composable
private fun ResponsiveStep(
    primary: @Composable () -> Unit,
    secondary: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (maxWidth >= 760.dp) {
            Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Column(modifier = Modifier.weight(0.94f).fillMaxHeight(), verticalArrangement = Arrangement.Center) { primary() }
                Box(modifier = Modifier.weight(1.06f).fillMaxHeight(), contentAlignment = Alignment.Center) { secondary() }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) { primary() }
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { secondary() }
            }
        }
    }
}

@Composable
private fun StepCopy(eyebrow: String, title: String, body: String) {
    GxrText(eyebrow, color = GhosttyTokens.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(10.dp))
    GxrText(title, color = GhosttyTokens.OnSurface, fontSize = 30.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(14.dp))
    GxrText(
        body,
        color = GhosttyTokens.OnSurfaceVariant,
        fontSize = 15.sp,
        style = com.ghosttyxr.app.ui.chrome.LocalGxrTextStyle.current.copy(lineHeight = 23.sp),
    )
}

@Composable
private fun MetricRow(vararg metrics: Pair<String, String>) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        metrics.forEach { (value, label) ->
            GxrPanel(modifier = Modifier.weight(1f), color = GhosttyTokens.SurfaceContainer) {
                Column(Modifier.padding(12.dp)) {
                    GxrText(value, color = GhosttyTokens.Accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    GxrText(label, color = GhosttyTokens.OnSurfaceFaint, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun CockpitConstellation() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier.size(300.dp).clip(CircleShape).background(GhosttyTokens.Accent.copy(alpha = 0.05f))
                .border(1.dp, GhosttyTokens.Accent.copy(alpha = 0.2f), CircleShape),
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MiniAgentPane("CODEX", "refactoring auth", GhosttyTokens.Online)
                MiniAgentPane("CLAUDE", "running tests", GhosttyTokens.Warning)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MiniAgentPane("GEMINI", "reviewing UI", GhosttyTokens.Accent)
                MiniAgentPane("SHELL", "build ready", GhosttyTokens.Online)
            }
        }
    }
}

@Composable
private fun MiniAgentPane(name: String, status: String, color: Color) {
    GxrPanel(modifier = Modifier.width(176.dp).height(112.dp), color = Color(0xFF090D12), borderColor = color.copy(alpha = 0.75f)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(7.dp))
                GxrText(name, fontSize = 11.sp, color = color, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(14.dp))
            GxrText("› $status", fontSize = 11.sp, color = GhosttyTokens.OnSurface)
            GxrText("  ███████░░", fontSize = 10.sp, color = GhosttyTokens.OnSurfaceFaint)
        }
    }
}

@Composable
private fun SetupChecklist(isPaired: Boolean, connected: Boolean) {
    SetupLine("1", "Download and open the Mac companion", isPaired)
    SetupLine("2", "Follow its Remote Login prompt if needed", isPaired)
    SetupLine("3", "Look at the pairing QR", connected)
}

@Composable
private fun SetupLine(number: String, label: String, complete: Boolean) {
    Row(modifier = Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(28.dp).clip(CircleShape)
                .background(if (complete) GhosttyTokens.Online.copy(alpha = 0.16f) else GhosttyTokens.SurfaceContainer)
                .border(1.dp, if (complete) GhosttyTokens.Online else GhosttyTokens.Outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) { GxrText(if (complete) "✓" else number, color = if (complete) GhosttyTokens.Online else GhosttyTokens.OnSurfaceVariant, fontSize = 12.sp) }
        Spacer(Modifier.width(10.dp))
        GxrText(label, color = if (complete) GhosttyTokens.OnSurface else GhosttyTokens.OnSurfaceVariant, fontSize = 13.sp)
    }
}

@Composable
private fun ConnectionStatus(sessionStates: List<SessionState>, isPaired: Boolean) {
    val (label, color) = when {
        sessionStates.any { it is SessionState.Connected } -> "LINK SECURE  /  TERMINAL ONLINE" to GhosttyTokens.Online
        sessionStates.any { it is SessionState.Authenticated } -> "AUTHENTICATED  /  OPENING SHELL" to GhosttyTokens.Accent
        sessionStates.any { it is SessionState.Connecting } -> "CONNECTING TO MAC…" to GhosttyTokens.Warning
        sessionStates.any { it is SessionState.Failed } -> "CONNECTION NEEDS ATTENTION" to GhosttyTokens.Danger
        isPaired -> "MAC SAVED  /  CONNECTING…" to GhosttyTokens.Warning
        else -> "WAITING FOR QR" to GhosttyTokens.OnSurfaceFaint
    }
    GxrText(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun ConnectedMacCard() {
    Box(
        modifier = Modifier.fillMaxSize().background(
            Brush.radialGradient(listOf(GhosttyTokens.Online.copy(alpha = 0.23f), Color.Black)),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(86.dp).clip(CircleShape).background(GhosttyTokens.Online.copy(alpha = 0.14f)).border(2.dp, GhosttyTokens.Online, CircleShape), contentAlignment = Alignment.Center) {
                GxrText("✓", color = GhosttyTokens.Online, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(18.dp))
            GxrText("MAC CONNECTED", color = GhosttyTokens.Online, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            GxrText("tmux -L gxr  /  session alive", color = GhosttyTokens.OnSurfaceVariant, fontSize = 11.sp)
        }
    }
}

@Composable
private fun OnboardingFooter(
    step: OnboardingStep,
    connected: Boolean,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onEnterCockpit: () -> Unit,
    onSkip: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AnimatedVisibility(step != OnboardingStep.WELCOME, enter = fadeIn(), exit = fadeOut()) {
            GxrButton(onClick = onBack, modifier = Modifier.testTag(ONBOARDING_BACK_TEST_TAG)) { GxrText("Back") }
        }
        Spacer(Modifier.weight(1f))
        if (step == OnboardingStep.PAIR && !connected) {
            GxrButton(
                onClick = onSkip,
                modifier = Modifier.testTag(ONBOARDING_CONTINUE_TEST_TAG),
            ) { GxrText("Pair later") }
        } else {
            GxrButton(
                onClick = if (step == OnboardingStep.PAIR) onEnterCockpit else onContinue,
                style = GxrButtonStyle.Primary,
                modifier = Modifier.testTag(ONBOARDING_CONTINUE_TEST_TAG),
            ) {
                GxrText(
                    if (step == OnboardingStep.PAIR) "Enter live cockpit" else "Continue",
                )
            }
        }
    }
}
