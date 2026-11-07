package com.ghosttyxr.app.billing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Thin client for the `verifyPurchase` Cloud Function: POST the Play purchase token
 * with Firebase ID + App Check headers; the server verifies against the Play
 * Developer API, acknowledges, and flips the caller's entitlement doc. Mirrors
 * [com.ghosttyxr.app.terminal.voice.tts.PetTtsClient]: one POST, pure
 * request-build/response-parse helpers so they unit-test without a network.
 */
class VerifyPurchaseClient(
    private val client: OkHttpClient = defaultClient(),
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val tokens: suspend () -> GateTokens,
) {

    /** Verify [purchaseToken]; returns the server's entitlement verdict or throws. */
    suspend fun verify(purchaseToken: String): Entitlement = withContext(Dispatchers.IO) {
        require(purchaseToken.isNotBlank()) { "purchase token is blank" }
        val gate = tokens()
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer ${gate.idToken}")
            .addHeader("X-Firebase-AppCheck", gate.appCheckToken)
            .post(buildRequestJson(purchaseToken).toRequestBody(JSON))
            .build()
        val response = runCatching { client.newCall(request).execute() }
            .getOrElse { throw VerifyPurchaseException("Network error: ${it.message}", it) }
        response.use {
            val body = it.body.string()
            if (!it.isSuccessful) {
                throw VerifyPurchaseException("Purchase verification failed (${it.code}).")
            }
            parseResponse(body)
        }
    }

    companion object {
        /** HTTPS Cloud Function in the app's Firebase project (see `firebase/functions/index.js`). */
        const val DEFAULT_ENDPOINT =
            "https://us-central1-ghosttyxr-headset.cloudfunctions.net/verifyPurchase"

        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()

        fun buildRequestJson(purchaseToken: String): String =
            JSONObject().put("purchaseToken", purchaseToken).toString()

        /** Parse `{ "tier": "pro"|"free", "expiryTimeMillis": ... }`. */
        fun parseResponse(json: String): Entitlement {
            val obj = runCatching { JSONObject(json) }
                .getOrElse { throw VerifyPurchaseException("Malformed verification response.") }
            return Entitlement.from(
                tier = obj.optString("tier").takeIf { it.isNotBlank() },
                expiryTimeMillis = obj.optLong("expiryTimeMillis", 0L),
            )
        }
    }
}

/** User-facing verification failure surfaced through the paywall UI. */
class VerifyPurchaseException(message: String, cause: Throwable? = null) : Exception(message, cause)
