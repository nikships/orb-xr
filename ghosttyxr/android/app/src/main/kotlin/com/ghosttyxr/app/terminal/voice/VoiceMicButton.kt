package com.ghosttyxr.app.terminal.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.ghosttyxr.app.terminal.theme.GhosttyTokens
import com.ghosttyxr.app.ui.chrome.GxrIconButton
import com.ghosttyxr.app.ui.chrome.GxrText
import kotlinx.coroutines.delay

import com.ghosttyxr.app.terminal.voice.command.LiveDictationState

const val VOICE_MIC_BUTTON_TEST_TAG = "ghosttyxr.voice.mic"

/**
 * Mic affordance shown on the focused pane's status bar. RECORD_AUDIO is batch-requested at app
 * startup; this only soft-re-requests if the user denied it there. Renders the [LiveDictationState]
 * inline: a pulsing accent ring + Stop/Cancel while accumulating, a spinner while inserting, and
 * a brief error/confirmation otherwise. Dictation flows through the shared Gemini Live session
 * (input-audio transcriptions); there is no batch-STT round-trip.
 */
@Composable
fun VoiceMicButton(
    state: LiveDictationState,
    onToggle: () -> Unit,
    onCancel: () -> Unit,
    onAcknowledge: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    // Soft recovery only — primary grant happens in MainActivity's startup batch request.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasMicPermission = granted
        if (granted) onToggle()
    }
    val toggleWithPermission = {
        if (hasMicPermission) onToggle()
        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    LifecycleResumeEffect(Unit) {
        hasMicPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        onPauseOrDispose {}
    }

    // Auto-dismiss the brief "Inserted"/"Error" confirmation back to idle.
    LaunchedEffect(state) {
        if (state is LiveDictationState.Inserted || state is LiveDictationState.Error) {
            delay(2500)
            onAcknowledge()
        }
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (state) {
            is LiveDictationState.Accumulating -> {
                MicDot(level = state.level)
                MicIconButton(
                    glyph = "■",
                    description = "Stop and insert dictation",
                    tint = GhosttyTokens.Accent,
                    onClick = onToggle,
                )
                MicIconButton(
                    glyph = "✕",
                    description = "Cancel voice input",
                    tint = GhosttyTokens.Danger,
                    onClick = onCancel,
                )
            }

            LiveDictationState.Inserting -> {
                MicDot(level = 0f)
                GxrText("Inserting…", fontSize = 11.sp, color = GhosttyTokens.OnSurfaceVariant)
            }

            is LiveDictationState.Inserted -> {
                GxrText("✓ inserted", fontSize = 11.sp, color = GhosttyTokens.Online)
            }

            is LiveDictationState.Error -> {
                GxrText(state.message, fontSize = 11.sp, color = GhosttyTokens.Danger)
                MicIconButton(
                    glyph = "↻",
                    description = "Retry voice input",
                    tint = GhosttyTokens.Accent,
                    onClick = {
                        onAcknowledge()
                        toggleWithPermission()
                    },
                )
            }

            LiveDictationState.Idle -> {
                MicIconButton(
                    glyph = "🎙",
                    description = "Start voice input",
                    tint = GhosttyTokens.OnSurfaceVariant,
                    onClick = toggleWithPermission,
                )
            }
        }
    }
}

@Composable
private fun MicIconButton(
    glyph: String,
    description: String,
    tint: Color,
    onClick: () -> Unit,
) {
    GxrIconButton(
        onClick = onClick,
        size = 28.dp,
        modifier = Modifier
            .testTag(VOICE_MIC_BUTTON_TEST_TAG)
            .semantics { contentDescription = description },
    ) {
        GxrText(text = glyph, fontSize = 15.sp, color = tint)
    }
}

/** Pulsing accent dot whose scale tracks the live mic [level]. */
@Composable
private fun MicDot(level: Float) {
    val transition = rememberInfiniteTransition(label = "mic-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "mic-pulse-scale",
    )
    val scale = pulse + level * 0.6f
    Box(
        modifier = Modifier
            .size(12.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(GhosttyTokens.Accent),
    )
}
