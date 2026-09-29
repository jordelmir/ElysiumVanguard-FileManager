package com.elysium.vanguard.core.encryption

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Roundtrip + failure-mode tests for [EncryptedVault] and [EncFsVolume].
 *
 * These exist because the previous implementation encrypted with a
 * random Tink key while decrypting with the password-derived key —
 * the roundtrip could never succeed. The tests pin the contract:
 * same password decrypts, wrong password does not.
 */
class EncryptedVaultTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `vault encrypt then decrypt roundtrips with the same password`() {
        val plaintext = "Elysium Vanguard — AES-256-GCM roundtrip".toByteArray()
        val container = EncryptedVault.encrypt("s3cret".toCharArray(), plaintext)
        val decrypted = EncryptedVault.decrypt("s3cret".toCharArray(), container)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `vault container starts with the ELYSVLT magic`() {
        val container = EncryptedVault.encrypt("pw".toCharArray(), byteArrayOf(1, 2, 3))
        val magic = String(container, 0, 7, Charsets.US_ASCII)
        assertEquals("ELYSVLT", magic)
    }

    @Test
    fun `vault decrypt with wrong password throws EncryptedVaultException`() {
        val container = EncryptedVault.encrypt("right".toCharArray(), "data".toByteArray())
        try {
            EncryptedVault.decrypt("wrong".toCharArray(), container)
            fail("wrong password should not decrypt")
        } catch (e: EncryptedVaultException) {
            assertTrue(e.message!!.contains("wrong password"))
        }
    }

    @Test
    fun `vault rejects a truncated container`() {
        try {
            EncryptedVault.decrypt("pw".toCharArray(), byteArrayOf(69, 76, 89))
            fail("truncated container should not decrypt")
        } catch (e: EncryptedVaultException) {
            assertTrue(e.message!!.contains("too small"))
        }
    }

    // --- EncFsVolume ---

    @Test
    fun `encfs create then open with same password succeeds`() {
        val volumeDir = tmp.newFolder("vol1")
        EncFsVolume.create(volumeDir, "hunter2".toCharArray())
        val opened = EncFsVolume.open(volumeDir, "hunter2".toCharArray())
        assertNotNull("same password must open the volume", opened)
    }

    @Test
    fun `encfs open with wrong password returns null`() {
        val volumeDir = tmp.newFolder("vol2")
        EncFsVolume.create(volumeDir, "correct".toCharArray())
        val opened = EncFsVolume.open(volumeDir, "incorrect".toCharArray())
        assertNull("wrong password must be rejected by the verifier", opened)
    }

    @Test
    fun `encfs open on a directory without config returns null`() {
        val plainDir = tmp.newFolder("not-a-volume")
        assertNull(EncFsVolume.open(plainDir, "pw".toCharArray()))
    }

    @Test
    fun `encfs file encrypt then decrypt roundtrips`() {
        val volumeDir = tmp.newFolder("vol3")
        val volume = EncFsVolume.create(volumeDir, "pw".toCharArray())

        val source = File(volumeDir.parentFile, "plain.txt")
        source.writeText("secret payload")
        val stored = volume.encryptFile(source)

        val out = File(volumeDir.parentFile, "restored.txt")
        assertTrue("decrypt must succeed", volume.decryptFile(stored, out))
        assertEquals("secret payload", out.readText())
    }

    @Test
    fun `encfs stored name is obfuscated and maps back to the original`() {
        val volumeDir = tmp.newFolder("vol4")
        val volume = EncFsVolume.create(volumeDir, "pw".toCharArray())

        val source = File(volumeDir.parentFile, "bank-statement.pdf")
        source.writeBytes(byteArrayOf(0x25, 0x50, 0x44, 0x46))
        val stored = volume.encryptFile(source)

        assertFalse("stored name must not contain the original name", stored.name.contains("bank-statement"))
        assertTrue("stored name must live inside the volume", stored.parentFile == volumeDir)
        assertEquals("bank-statement.pdf", volume.getDecryptedName(stored.name))
    }

    @Test
    fun `encfs rejects a volume opened after config tampering`() {
        val volumeDir = tmp.newFolder("vol5")
        EncFsVolume.create(volumeDir, "pw".toCharArray())
        val configFile = File(volumeDir, EncFsVolume.CONFIG_NAME)
        configFile.writeText(configFile.readText().replace(Regex("<verifier>[0-9a-f]+</verifier>"), "<verifier>00</verifier>"))
        assertNull(EncFsVolume.open(volumeDir, "pw".toCharArray()))
    }
}
