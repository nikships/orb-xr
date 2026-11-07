package com.ghosttyxr.app.qr

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Decoded contents of a Mac-companion QR pairing code.
 *
 * Mac companion emits URLs of the form:
 *   `ssh://<user>@<host>:<port>?token=<base64>&fp=SHA256:<...>`
 *
 * The `fp` query parameter is optional — the Mac app omits it when no host key fingerprint is
 * yet known to it. Everything else is required; parsing rejects payloads missing any required
 * field rather than silently substituting defaults (fail-fast).
 *
 * Uses [java.net.URI] (not `android.net.Uri`) so the parser is reachable from plain-JVM unit
 * tests — the project's `unitTests.isReturnDefaultValues = true` flag would silently null out
 * every `android.net.Uri` accessor on the host JVM.
 */
data class PairingPayload(
    val host: String,
    val port: Int,
    val user: String,
    val token: String,
    val fingerprint: String?,
) {
    companion object {
        fun parse(raw: String): Result<PairingPayload> = runCatching {
            val uri = URI(raw.trim())
            require(uri.scheme == "ssh") { "expected ssh:// scheme, got ${uri.scheme}" }
            val userInfo = uri.userInfo?.takeIf { it.isNotBlank() }
                ?: error("missing user in ssh URL")
            // Reject `user:password@host` form — pairing is strictly token-based.
            require(':' !in userInfo) { "password in ssh URL is not allowed; use token query param" }
            val host = uri.host?.takeIf { it.isNotBlank() }
                ?: error("missing host in ssh URL")
            val port = uri.port.let { if (it == -1) 22 else it }
            require(port in 1..65535) { "invalid port $port" }
            val params = parseQuery(uri.rawQuery)
            val token = params["token"]?.takeIf { it.isNotBlank() }?.replace(' ', '+')
                ?: error("missing token query param")
            val fingerprint = params["fp"]?.takeIf { it.isNotBlank() }?.replace(' ', '+')
            PairingPayload(
                host = host,
                port = port,
                user = userInfo,
                token = token,
                fingerprint = fingerprint,
            )
        }

        private fun parseQuery(rawQuery: String?): Map<String, String> {
            if (rawQuery.isNullOrEmpty()) return emptyMap()
            return buildMap {
                rawQuery.split('&').filter(String::isNotEmpty).forEach { segment ->
                    val (key, value) = segment.split('=', limit = 2).let { parts ->
                        parts.first() to parts.getOrElse(1) { "" }
                    }
                    put(urlDecode(key), urlDecode(value))
                }
            }
        }

        private fun urlDecode(s: String): String =
            URLDecoder.decode(s, StandardCharsets.UTF_8.name())
    }
}
