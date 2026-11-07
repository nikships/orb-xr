package com.ghosttyxr.app.terminal.voice

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User-owned Gemini key, encrypted with the Android Keystore and never bundled in the APK. */
object GeminiKeyStore {
    @Volatile private var prefs: SharedPreferences? = null
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val app = context.applicationContext
        val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        prefs = EncryptedSharedPreferences.create(
            app, "gemini_key", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun get(): String? = prefs?.getString("api_key", null)?.takeIf { it.isNotBlank() }
    fun set(key: String) {
        val editor = checkNotNull(prefs) { "Key store is not initialized" }.edit()
        if (key.isBlank()) editor.remove("api_key") else editor.putString("api_key", key.trim())
        editor.apply()
        _revision.value += 1
    }
}
