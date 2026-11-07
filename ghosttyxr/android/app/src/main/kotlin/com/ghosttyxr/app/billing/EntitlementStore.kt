package com.ghosttyxr.app.billing

import android.content.Context
import android.content.SharedPreferences
import com.ghosttyxr.app.util.GxrLog
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "EntitlementStore"

/**
 * Live mirror of the server-owned `entitlements/{uid}` Firestore doc, following the
 * settings-store pattern (`object` + `MutableStateFlow` + `init(context)`).
 *
 * The doc is written ONLY by Cloud Functions (verifyPurchase / playRtdn); Firestore
 * rules make it read-only for the client, so this store is a cache, not an authority.
 * The heavy paths (stt / petTts) re-check entitlement server-side on every call — a
 * tampered client gets 403s, not free AI. The last-known state is cached in plain
 * SharedPreferences (it is not a secret) so voice gates render correctly offline.
 */
object EntitlementStore {

    private val _entitlement = MutableStateFlow(Entitlement())

    /** Last-known entitlement. Drives Pro gating in the voice UI. */
    val entitlement: StateFlow<Entitlement> = _entitlement.asStateFlow()

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var registration: ListenerRegistration? = null

    val isPro: Boolean get() = _entitlement.value.isPro()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        _entitlement.value = Entitlement.from(
            tier = p.getString(KEY_TIER, null),
            expiryTimeMillis = p.getLong(KEY_EXPIRY, 0L),
        )
    }

    /**
     * Attach the Firestore snapshot listener for [uid]. Called by the wiring layer once
     * anonymous sign-in completes; re-called safely on auth changes (old listener dropped).
     */
    fun startListening(uid: String, firestore: FirebaseFirestore = FirebaseFirestore.getInstance()) {
        registration?.remove()
        registration = firestore.collection("entitlements").document(uid)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    GxrLog.w(TAG, "entitlement listen failed: ${err.message}")
                    return@addSnapshotListener
                }
                val ent = snap?.takeIf { it.exists() }?.let {
                    Entitlement.from(it.getString("tier"), it.getLong("expiryTimeMillis"))
                } ?: Entitlement()
                update(ent)
            }
    }

    /** Apply a fresh entitlement (listener callback or a verifyPurchase response). */
    fun update(ent: Entitlement) {
        if (_entitlement.value == ent) return
        _entitlement.value = ent
        prefs?.edit()
            ?.putString(KEY_TIER, if (ent.tier == Tier.PRO) "pro" else "free")
            ?.putLong(KEY_EXPIRY, ent.expiryTimeMillis)
            ?.apply()
    }

    fun stopListening() {
        registration?.remove()
        registration = null
    }

    /** The current Firebase uid, or null before anonymous sign-in lands. */
    fun currentUid(): String? = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()

    private const val PREFS_NAME = "ghosttyxr_entitlement"
    private const val KEY_TIER = "tier"
    private const val KEY_EXPIRY = "expiry_time_millis"
}
