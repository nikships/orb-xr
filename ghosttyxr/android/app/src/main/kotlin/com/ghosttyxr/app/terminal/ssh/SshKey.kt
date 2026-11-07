package com.ghosttyxr.app.terminal.ssh

/**
 * Plain-data representation of an SSH keypair stored on the headset.
 *
 * Persistence lands later — chuchu uses Room for this, but ghosttyxr's
 * storage path isn't decided yet (DataStore or Android Keystore +
 * encrypted prefs are both plausible). Keep this class persistence-free
 * so the choice doesn't ripple back into the generators.
 *
 * `privateKeyPem` and `publicKeyOpenSsh` are passed straight to
 * `NativeSshBridge.nativeAuthenticatePublicKeyMemory` — match libssh2's
 * expected formats verbatim.
 */
data class SshKey(
    val name: String,
    val algorithm: String,
    val privateKeyPem: String,
    val publicKeyOpenSsh: String,
    val createdAtEpochMs: Long,
)
