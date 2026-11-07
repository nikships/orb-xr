package com.ghosttyxr.app

import android.app.Application
import com.ghosttyxr.app.util.GxrLog
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/**
 * App-level init: installs the Firebase App Check provider before anything makes a
 * gated Cloud Function call (stt / petTts / verifyPurchase all require an App Check
 * token). Play Integrity in release; the debug provider in debug builds so the XR
 * AVD works (register its debug token in the Firebase console).
 */
class GhosttyXRApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // google-services.json is checked in, so this succeeds in normal builds; the
        // runCatching keeps a config-less contributor build from crashing at launch
        // (voice/billing features stay off, matching the pre-existing behaviour).
        runCatching {
            val app = FirebaseApp.initializeApp(this)
            if (BuildConfig.DEBUG) seedFixedDebugToken(app)
            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                if (BuildConfig.DEBUG) debugProviderFactory() else PlayIntegrityAppCheckProviderFactory.getInstance(),
            )
        }.onFailure { GxrLog.w("GhosttyXRApp", "Firebase init failed: ${it.message}") }
    }

    /**
     * Dev backdoor half two: the debug App Check provider normally mints a random
     * token per install, each needing manual approval in the Firebase console. With
     * `devappcheck.token` in local.properties (a token pre-registered in the console),
     * we seed the provider's own persistence store before it initializes, so every
     * fresh install reuses the approved token. Debug-only; the field is "" in release.
     */
    private fun seedFixedDebugToken(app: FirebaseApp?) {
        val token = BuildConfig.DEV_APPCHECK_TOKEN
        if (token.isBlank() || app == null) return
        getSharedPreferences(
            "com.google.firebase.appcheck.debug.store.${app.persistenceKey}",
            MODE_PRIVATE,
        ).edit()
            .putString("com.google.firebase.appcheck.debug.DEBUG_SECRET", token)
            .apply()
    }

    /**
     * The debug provider ships only in debug builds (`debugImplementation`), so it is
     * loaded reflectively — a direct import would break the release compile.
     */
    private fun debugProviderFactory(): AppCheckProviderFactory =
        Class.forName("com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory")
            .getMethod("getInstance")
            .invoke(null) as AppCheckProviderFactory
}
