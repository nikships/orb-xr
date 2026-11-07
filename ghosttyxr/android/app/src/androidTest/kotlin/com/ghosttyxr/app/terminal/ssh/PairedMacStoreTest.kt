package com.ghosttyxr.app.terminal.ssh

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Round-trips the encrypted paired-Mac record through real
 * [androidx.security.crypto.EncryptedSharedPreferences]. Runs on the XR AVD
 * (see TESTING.md) because EncryptedSharedPreferences needs the Android
 * Keystore the host JVM doesn't provide.
 */
@RunWith(AndroidJUnit4::class)
class PairedMacStoreTest {

    private val mac = PairedMac(
        host = "192.168.1.42",
        port = 22,
        user = "nik",
        hostFingerprint = "SHA256:deadbeef",
        provisionedAtEpochMs = 1_700_000_000_000L,
    )
    private val key = SshKey(
        name = "ghosttyxr-headset",
        algorithm = "Ed25519",
        privateKeyPem = "-----BEGIN OPENSSH PRIVATE KEY-----\nFAKE\n-----END OPENSSH PRIVATE KEY-----",
        publicKeyOpenSsh = "ssh-ed25519 AAAAC3FAKE headset@ghosttyxr",
        createdAtEpochMs = 1_700_000_000_001L,
    )

    @Before
    fun setUp() {
        PairedMacStore.init(ApplicationProvider.getApplicationContext())
        PairedMacStore.clear()
    }

    @After
    fun tearDown() {
        PairedMacStore.clear()
    }

    @Test
    fun savedRecordRoundTrips() {
        PairedMacStore.save(mac, key)

        val (loadedMac, loadedKey) = requireNotNull(PairedMacStore.load())
        assertEquals(mac, loadedMac)
        assertEquals(key, loadedKey)
    }

    @Test
    fun isPairedReflectsSaveAndClear() {
        assertFalse(PairedMacStore.isPaired.value)
        PairedMacStore.save(mac, key)
        assertTrue(PairedMacStore.isPaired.value)
        PairedMacStore.clear()
        assertFalse(PairedMacStore.isPaired.value)
        assertNull(PairedMacStore.load())
    }

    @Test
    fun loadKeyReturnsPersistedKey() {
        PairedMacStore.save(mac, key)
        assertNotNull(PairedMacStore.loadKey())
        assertEquals(key, PairedMacStore.loadKey())
    }
}
