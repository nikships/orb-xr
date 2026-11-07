package com.ghosttyxr.app.terminal.ssh

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Generates an RSA SSH keypair using the JDK's bundled `KeyPairGenerator`
 * — no BouncyCastle dependency, no native code. 3072-bit by default; the
 * Mac SSH server accepts down to 2048 but 3072 is the OpenSSH-recommended
 * floor for `ssh-rsa` post-2020.
 *
 * This is the headset's only key generator: pure-JVM keygen works on every
 * API level back to minSdk 24 (the platform `KeyPairGenerator` only gained
 * Ed25519 in API 33, which is why the old native Ed25519 path existed), so
 * provisioning never depends on `libghosttyxr.so` being loadable.
 *
 * The OpenSSH public-key wire format is hand-rolled here because the JDK
 * has no `EVP_PKEY_to_authorized_keys` equivalent. Format reference:
 * RFC 4253 §6.6.
 */
class RsaKeyGenerator {
    fun generate(name: String, bits: Int = 3072): SshKey {
        val normalizedName = name.trim().ifBlank { "android-rsa" }
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(bits)
        val pair = generator.generateKeyPair()
        val privatePem = encodePrivateKeyPem(pair.private.encoded)
        val publicOpenSsh = encodeRsaPublicKeyOpenSsh(
            pair.public as RSAPublicKey,
            "$normalizedName@ghosttyxr",
        )
        return SshKey(
            name = normalizedName,
            algorithm = "RSA",
            privateKeyPem = privatePem,
            publicKeyOpenSsh = publicOpenSsh,
            createdAtEpochMs = System.currentTimeMillis(),
        )
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun encodePrivateKeyPem(privateDer: ByteArray): String {
        // PKCS#8 unencrypted. libssh2 reads this format directly.
        // Kotlin's platform-free encoder works on API 24/25. Wrap its standard
        // padded RFC 4648 output exactly like the previous 64-column MIME encoder.
        val body = Base64.Default.encode(privateDer).chunked(64).joinToString("\n")
        return "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----\n"
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun encodeRsaPublicKeyOpenSsh(publicKey: RSAPublicKey, comment: String): String {
        val blob = ByteArrayOutputStream().use { out ->
            writeSshString(out, "ssh-rsa".toByteArray(StandardCharsets.US_ASCII))
            writeMpInt(out, publicKey.publicExponent.toByteArray())
            writeMpInt(out, publicKey.modulus.toByteArray())
            out.toByteArray()
        }
        val payload = Base64.Default.encode(blob)
        return "ssh-rsa $payload $comment"
    }

    private fun writeSshString(out: ByteArrayOutputStream, bytes: ByteArray) {
        writeInt32(out, bytes.size)
        out.write(bytes)
    }

    // mpint (RFC 4251 §5): leading zeros stripped, then a single 0x00
    // prepended if the high bit of the most significant byte is set —
    // OpenSSH parsers reject negative-looking mpints otherwise.
    private fun writeMpInt(out: ByteArrayOutputStream, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size - 1 && bytes[offset] == 0.toByte()) {
            offset += 1
        }
        var body = bytes.copyOfRange(offset, bytes.size)
        if (body.isEmpty()) {
            body = byteArrayOf(0)
        }
        if (body[0].toInt() and 0x80 != 0) {
            body = byteArrayOf(0) + body
        }
        writeSshString(out, body)
    }

    private fun writeInt32(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xff)
        out.write((value ushr 16) and 0xff)
        out.write((value ushr 8) and 0xff)
        out.write(value and 0xff)
    }
}
