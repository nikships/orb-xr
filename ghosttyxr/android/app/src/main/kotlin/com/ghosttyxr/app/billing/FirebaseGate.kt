package com.ghosttyxr.app.billing

import com.ghosttyxr.app.BuildConfig
import com.ghosttyxr.app.util.GxrLog
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await

private const val TAG = "FirebaseGate"

/** ID + App Check token pair attached to every Pro-gated Cloud Function call. */
data class GateTokens(val idToken: String, val appCheckToken: String)

/**
 * Thin Firebase attestation layer for the Pro-gated Cloud Functions (stt / petTts /
 * verifyPurchase). Owns anonymous sign-in and token fetch; everything else takes a
 * `suspend () -> GateTokens` lambda so it stays unit-testable without Firebase.
 */
object FirebaseGate {

    /**
     * Ensure a Firebase user exists, returning its uid. Production path is anonymous
     * auth (persisted across launches and `adb install -r`).
     *
     * Dev backdoor: debug builds with `devauth.email`/`devauth.password` in
     * local.properties sign into that fixed account instead, so the uid — and its
     * hand-granted Pro entitlement doc — survives full uninstall/reinstall cycles.
     * Both fields compile to "" in CI and release, making this path unreachable there.
     */
    suspend fun ensureSignedIn(): String {
        val auth = FirebaseAuth.getInstance()
        val devEmail = BuildConfig.DEV_AUTH_EMAIL
        val devPassword = BuildConfig.DEV_AUTH_PASSWORD
        if (BuildConfig.DEBUG && devEmail.isNotBlank() && devPassword.isNotBlank()) {
            auth.currentUser?.takeIf { it.email == devEmail }?.let { return it.uid }
            val result = auth.signInWithEmailAndPassword(devEmail, devPassword).await()
            val uid = result.user?.uid ?: throw IllegalStateException("Dev sign-in returned no user")
            GxrLog.d(TAG) { "dev account sign-in ok" }
            return uid
        }
        auth.currentUser?.let { return it.uid }
        val result = auth.signInAnonymously().await()
        val uid = result.user?.uid ?: throw IllegalStateException("Anonymous sign-in returned no user")
        GxrLog.d(TAG) { "anonymous sign-in ok" }
        return uid
    }

    /**
     * Fetch a fresh ID token + App Check token for a gated function call. Signs in
     * first if needed ([ensureSignedIn] is idempotent and returns immediately when a
     * user is cached), so a voice action racing the startup sign-in still succeeds.
     */
    suspend fun tokens(): GateTokens {
        ensureSignedIn()
        val user = FirebaseAuth.getInstance().currentUser
            ?: throw IllegalStateException("Not signed in")
        val idToken = user.getIdToken(false).await().token
            ?: throw IllegalStateException("No ID token")
        val appCheck = FirebaseAppCheck.getInstance().getAppCheckToken(false).await().token
        return GateTokens(idToken = idToken, appCheckToken = appCheck)
    }
}
