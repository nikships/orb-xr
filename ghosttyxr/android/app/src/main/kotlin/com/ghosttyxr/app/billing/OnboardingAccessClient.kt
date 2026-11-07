package com.ghosttyxr.app.billing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class OnboardingAccessClient(
    private val client: OkHttpClient = defaultClient(),
    private val beginEndpoint: String = BEGIN_ENDPOINT,
    private val completeEndpoint: String = COMPLETE_ENDPOINT,
    private val tokens: suspend () -> GateTokens,
) {
    suspend fun begin(): Entitlement = call(beginEndpoint)

    suspend fun complete(): Entitlement = call(completeEndpoint)

    private suspend fun call(endpoint: String): Entitlement = withContext(Dispatchers.IO) {
        val gate = tokens()
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer ${gate.idToken}")
            .addHeader("X-Firebase-AppCheck", gate.appCheckToken)
            .post("{}".toRequestBody(JSON))
            .build()
        val response = runCatching { client.newCall(request).execute() }
            .getOrElse { throw OnboardingAccessException("Network error: ${it.message}", it) }
        response.use {
            val body = it.body.string()
            if (!it.isSuccessful) {
                throw OnboardingAccessException("Onboarding access failed (${it.code}).")
            }
            parseResponse(body)
        }
    }

    companion object {
        const val BEGIN_ENDPOINT =
            "https://us-central1-ghosttyxr-headset.cloudfunctions.net/beginOnboardingAccess"
        const val COMPLETE_ENDPOINT =
            "https://us-central1-ghosttyxr-headset.cloudfunctions.net/completeOnboardingAccess"

        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()

        fun parseResponse(json: String): Entitlement {
            val obj = runCatching { JSONObject(json) }
                .getOrElse { throw OnboardingAccessException("Malformed onboarding response.") }
            return Entitlement.from(
                tier = obj.optString("tier").takeIf { it.isNotBlank() },
                expiryTimeMillis = obj.optLong("expiryTimeMillis", 0L),
            )
        }
    }
}

class OnboardingAccessException(message: String, cause: Throwable? = null) : Exception(message, cause)
