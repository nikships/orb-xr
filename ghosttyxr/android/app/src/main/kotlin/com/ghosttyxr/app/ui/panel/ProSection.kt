package com.ghosttyxr.app.ui.panel

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghosttyxr.app.R
import com.ghosttyxr.app.billing.BillingUiState
import com.ghosttyxr.app.terminal.theme.GhosttyTokens
import com.ghosttyxr.app.ui.PRO_MANAGE_BUTTON_TEST_TAG
import com.ghosttyxr.app.ui.PRO_UPGRADE_BUTTON_TEST_TAG
import com.ghosttyxr.app.ui.chrome.GxrButton
import com.ghosttyxr.app.ui.chrome.GxrButtonStyle
import com.ghosttyxr.app.ui.chrome.GxrPanel
import com.ghosttyxr.app.ui.chrome.GxrSection
import com.ghosttyxr.app.ui.chrome.GxrText
import com.ghosttyxr.app.ui.chrome.rememberOrbJewelPainter

/**
 * "Orb XR Pro" paywall section in the control panel. Free tier is the full
 * cockpit with no AI; Pro unlocks all voice features, managed by us (no API keys).
 * Purchases run through Play Billing; entitlement truth comes back via the server
 * (verifyPurchase → Firestore → EntitlementStore), not the Play result alone.
 */
@Composable
internal fun ProSection(
    isPro: Boolean,
    billingState: BillingUiState,
    onUpgrade: () -> Unit,
    onManage: () -> Unit,
    onRestore: () -> Unit,
) {
    GxrPanel(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(GhosttyTokens.ChromeCorner),
        color = GhosttyTokens.JewelOverlay,
        backgroundPainter = rememberOrbJewelPainter(),
        borderColor = GhosttyTokens.JewelOutline,
    ) {
        GxrSection(
            title = "Orb XR Pro",
            iconRes = R.drawable.gxr_ic_pro,
            modifier = Modifier.padding(4.dp),
        ) {
            if (isPro) {
                GxrText(
                    text = "✓ Pro active — dictation, voice commands, and the talking orb are unlocked.",
                    fontSize = 12.sp,
                    color = GhosttyTokens.OnSurface,
                )
                GxrButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(PRO_MANAGE_BUTTON_TEST_TAG),
                    onClick = onManage,
                ) {
                    GxrText("Manage subscription", fontSize = 13.sp)
                }
            } else {
                GxrText(
                    text = "Unlock all AI voice features — dictation into any pane, Gemini voice " +
                        "commands, and the talking voice orb. Fully managed; no API keys needed.",
                    fontSize = 12.sp,
                    color = GhosttyTokens.OnSurfaceVariant,
                )
                val (label, enabled) = when (billingState) {
                    is BillingUiState.Ready ->
                        (if (billingState.hasTrialOffer) {
                            "Start 7-day free trial · ${billingState.priceText}"
                        } else {
                            "Upgrade · ${billingState.priceText}"
                        }) to true
                    is BillingUiState.PurchaseInFlight -> "Completing purchase…" to false
                    is BillingUiState.Connecting -> "Loading…" to false
                    is BillingUiState.Blocked -> "Purchases unavailable" to false
                    is BillingUiState.Error -> "Retry upgrade" to true
                    else -> "Upgrade to Pro" to true
                }
                GxrButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(PRO_UPGRADE_BUTTON_TEST_TAG),
                    onClick = onUpgrade,
                    enabled = enabled,
                    style = GxrButtonStyle.Primary,
                ) {
                    GxrText(label, fontSize = 13.sp)
                }
                val notice = when (billingState) {
                    is BillingUiState.Blocked -> billingState.message
                    is BillingUiState.Error -> billingState.message
                    else -> null
                }
                notice?.let {
                    GxrText(
                        text = it,
                        fontSize = 11.sp,
                        color = GhosttyTokens.Warning,
                    )
                }
                GxrButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onRestore,
                    enabled = billingState !is BillingUiState.Blocked,
                ) {
                    GxrText("Restore purchase", fontSize = 13.sp)
                }
                GxrText(
                    text = "Monthly subscription via Google Play. Cancel anytime in the Play Store.",
                    fontSize = 11.sp,
                    color = GhosttyTokens.OnSurfaceVariant,
                )
            }
        }
    }
}
