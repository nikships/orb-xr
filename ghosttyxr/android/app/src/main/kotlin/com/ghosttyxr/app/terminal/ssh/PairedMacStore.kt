package com.ghosttyxr.app.terminal.ssh

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "PairedMacStore"

/**
 * Durable, encrypted-at-rest record of the single Mac the headset is paired
 * with, plus the [SshKey] it uses to log in there without a password.
 *
 * Persisting both is what lets the headset skip the QR scanner on every
 * launch after the first pairing: [load] hands [com.ghosttyxr.app.MainActivity]
 * a [PairedMac] + [SshKey], and the session layer auto-connects via pubkey.
 *
 * Backed by [EncryptedSharedPreferences] (AES-256 via an Android Keystore
 * master key) — the private key grants shell access to the user's Mac, so it
 * must not sit in cleartext.
 *
 * Single-Mac by design: Orb XR pairs with one Mac at a time, so [save]
 * overwrites any previous record. Object + `init(context)` mirrors
 * [com.ghosttyxr.app.terminal.settings.GhosttySettingsStore].
 */
object PairedMacStore {
    private val _isPaired = MutableStateFlow(false)

    /** Whether a paired Mac + key are on disk. Drives auto-connect-vs-scanner. */
    val isPaired: StateFlow<Boolean> = _isPaired.asStateFlow()

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = openPrefs(context.applicationContext)
        prefs = p
        _isPaired.value = hasRecord(p)
    }

    /** Persist (or replace) the paired Mac and the key used to reach it. */
    fun save(mac: PairedMac, key: SshKey) {
        val p = prefs ?: return
        p.edit()
            .putString(KEY_HOST, mac.host)
            .putInt(KEY_PORT, mac.port)
            .putString(KEY_USER, mac.user)
            .putString(KEY_HOST_FP, mac.hostFingerprint)
            .putLong(KEY_PROVISIONED_AT, mac.provisionedAtEpochMs)
            .putString(KEY_KEY_NAME, key.name)
            .putString(KEY_KEY_ALGORITHM, key.algorithm)
            .putString(KEY_KEY_PRIVATE_PEM, key.privateKeyPem)
            .putString(KEY_KEY_PUBLIC_OPENSSH, key.publicKeyOpenSsh)
            .putLong(KEY_KEY_CREATED_AT, key.createdAtEpochMs)
            .apply()
        _isPaired.value = true
    }

    /** The paired Mac + its key, or null if the headset has never paired. */
    fun load(): Pair<PairedMac, SshKey>? {
        val p = prefs ?: return null
        val host = p.getString(KEY_HOST, null) ?: return null
        val user = p.getString(KEY_USER, null) ?: return null
        val hostFp = p.getString(KEY_HOST_FP, null) ?: return null
        val privatePem = p.getString(KEY_KEY_PRIVATE_PEM, null) ?: return null
        val publicOpenSsh = p.getString(KEY_KEY_PUBLIC_OPENSSH, null) ?: return null
        val mac = PairedMac(
            host = host,
            port = p.getInt(KEY_PORT, 22),
            user = user,
            hostFingerprint = hostFp,
            provisionedAtEpochMs = p.getLong(KEY_PROVISIONED_AT, 0L),
        )
        val key = SshKey(
            name = p.getString(KEY_KEY_NAME, null) ?: "ghosttyxr-headset",
            algorithm = p.getString(KEY_KEY_ALGORITHM, null) ?: "RSA",
            privateKeyPem = privatePem,
            publicKeyOpenSsh = publicOpenSsh,
            createdAtEpochMs = p.getLong(KEY_KEY_CREATED_AT, 0L),
        )
        return mac to key
    }

    /** Just the persisted keypair, if any — used to reuse the key across re-pairings. */
    fun loadKey(): SshKey? = load()?.second

    /** Forget the paired Mac and its key. Returns the headset to QR pairing. */
    fun clear() {
        prefs?.edit()?.clear()?.apply()
        _isPaired.value = false
    }

    private fun hasRecord(p: SharedPreferences): Boolean =
        p.getString(KEY_HOST, null) != null && p.getString(KEY_KEY_PRIVATE_PEM, null) != null

    /**
     * Open the encrypted prefs. A corrupt Keystore master key (a known but
     * rare Android failure mode) throws here; recover by dropping BOTH the
     * undecryptable prefs file AND the master-key entry from the Android
     * Keystore, then rebuild — deleting only the file leaves the bad key in
     * place and the retry throws the same way, crash-looping startup. The
     * user just re-pairs. We never silently fall back to cleartext for a key
     * that grants shell access.
     */
    private fun openPrefs(appContext: Context): SharedPreferences {
        return runCatching { buildEncryptedPrefs(appContext) }
            .getOrElse { first ->
                Log.w(TAG, "EncryptedSharedPreferences open failed; resetting paired-Mac store", first)
                appContext.deleteSharedPreferences(PREFS_NAME)
                runCatching {
                    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                    keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                }.onFailure { Log.w(TAG, "could not delete master key during reset", it) }
                buildEncryptedPrefs(appContext)
            }
    }

    private fun buildEncryptedPrefs(appContext: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val PREFS_NAME = "ghosttyxr_paired_mac"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val KEY_USER = "user"
    private const val KEY_HOST_FP = "host_fingerprint"
    private const val KEY_PROVISIONED_AT = "provisioned_at"
    private const val KEY_KEY_NAME = "key_name"
    private const val KEY_KEY_ALGORITHM = "key_algorithm"
    private const val KEY_KEY_PRIVATE_PEM = "key_private_pem"
    private const val KEY_KEY_PUBLIC_OPENSSH = "key_public_openssh"
    private const val KEY_KEY_CREATED_AT = "key_created_at"
}
