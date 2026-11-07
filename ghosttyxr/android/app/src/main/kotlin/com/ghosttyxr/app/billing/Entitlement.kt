package com.ghosttyxr.app.billing

/**
 * The cockpit's two tiers. Free = the full terminal cockpit with no AI features.
 * Pro = all AI voice features (dictation, Gemini voice commands, pet TTS), fully
 * managed server-side — no user API keys anywhere.
 */
enum class Tier { FREE, PRO }

/**
 * Snapshot of the user's entitlement, mirrored from the Firestore
 * `entitlements/{uid}` doc that the Cloud Functions maintain (verifyPurchase /
 * playRtdn). The client never decides entitlement itself — the server does —
 * but it caches the last-known state so voice UI gates work offline.
 */
data class Entitlement(
    val tier: Tier = Tier.FREE,
    val expiryTimeMillis: Long = 0L,
) {
    /** Pro with a not-yet-expired period (one day of slack for clock/RTDN lag). */
    fun isPro(nowMillis: Long = System.currentTimeMillis()): Boolean =
        tier == Tier.PRO && expiryTimeMillis + EXPIRY_SLACK_MS > nowMillis

    companion object {
        /** Mirrors the server's slack in `lib/entitlements.js`. */
        const val EXPIRY_SLACK_MS: Long = 24L * 60 * 60 * 1000

        /** Parse the Firestore doc fields (or prefs cache) into an [Entitlement]. */
        fun from(tier: String?, expiryTimeMillis: Long?): Entitlement = Entitlement(
            tier = if (tier == "pro") Tier.PRO else Tier.FREE,
            expiryTimeMillis = expiryTimeMillis ?: 0L,
        )
    }
}
