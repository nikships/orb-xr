package com.ghosttyxr.app.terminal.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for the provisioner's shell-command construction and the
 * sentinel parse. The full SSH provisioning flow (handshake → push → persist)
 * is exercised against a fake transport + real `SharedPreferences`-backed
 * fingerprint store / `EncryptedSharedPreferences` in `androidTest`, which
 * need the Android framework these host-JVM tests can't provide.
 */
class MacProvisionerTest {

    private val pubKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAITESTKEY headset@ghosttyxr"

    @Test
    fun `command is idempotent - greps before appending`() {
        val cmd = MacProvisioner.buildAuthorizedKeyCommand(pubKey)
        // grep -qF '<key>' ... || printf ... >> ... : only appends when absent.
        assertTrue(cmd.contains("grep -qF"))
        assertTrue(cmd.contains("|| printf"))
        assertTrue(cmd.contains(">> ~/.ssh/authorized_keys"))
    }

    @Test
    fun `command tightens ssh dir and file permissions`() {
        val cmd = MacProvisioner.buildAuthorizedKeyCommand(pubKey)
        assertTrue(cmd.contains("mkdir -p ~/.ssh"))
        assertTrue(cmd.contains("chmod 700 ~/.ssh"))
        assertTrue(cmd.contains("chmod 600 ~/.ssh/authorized_keys"))
    }

    @Test
    fun `command echoes the sentinel with the exit status and a trailing newline`() {
        val cmd = MacProvisioner.buildAuthorizedKeyCommand(pubKey)
        assertTrue(cmd.contains("echo ${MacProvisioner.SENTINEL}:\$?"))
        assertTrue(cmd.endsWith("\n"))
    }

    @Test
    fun `command single-quotes the key so shell metacharacters are inert`() {
        val cmd = MacProvisioner.buildAuthorizedKeyCommand(pubKey)
        assertTrue(cmd.contains("'$pubKey'"))
    }

    @Test
    fun `parse reads exit zero from sentinel line`() {
        val out = "some banner\n${MacProvisioner.SENTINEL}:0\n"
        assertEquals(0, MacProvisioner.parseSentinelStatus(out))
    }

    @Test
    fun `parse reads non-zero exit status`() {
        val out = "${MacProvisioner.SENTINEL}:1\n"
        assertEquals(1, MacProvisioner.parseSentinelStatus(out))
    }

    @Test
    fun `parse ignores the echoed command and reads the real result line`() {
        // A PTY echoes the typed command (which contains the sentinel inside
        // the echo argument) before the shell runs it and prints the result.
        val out = "echo ${MacProvisioner.SENTINEL}:\$?\n${MacProvisioner.SENTINEL}:0\n"
        assertEquals(0, MacProvisioner.parseSentinelStatus(out))
    }

    @Test
    fun `parse returns null when no sentinel present`() {
        assertNull(MacProvisioner.parseSentinelStatus("just some shell noise\n"))
    }
}
