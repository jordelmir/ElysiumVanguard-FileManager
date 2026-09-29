package com.elysium.vanguard.core.encryption

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM coverage for the [EncFsVolume] engine (the EncFS-style per-file
 * encrypted directory). Exercises create/open, the password verifier,
 * encryption roundtrips, name obfuscation, name-map persistence, and
 * entry deletion.
 */
class EncFsVolumeTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private fun newVolumeDir(name: String = "vol.encfs"): File = tempFolder.newFolder(name)

    @Test
    fun `create writes config and refuses a second volume at the same path`() {
        val dir = newVolumeDir()
        EncFsVolume.create(dir, "pw".toCharArray())
        assertTrue(File(dir, EncFsVolume.CONFIG_NAME).exists())

        val failure = runCatching { EncFsVolume.create(dir, "pw".toCharArray()) }.exceptionOrNull()
        assertTrue(failure is EncFsVolumeException)
    }

    @Test
    fun `open returns null for a missing config, and for a wrong password`() {
        val plainDir = tempFolder.newFolder("not-a-volume")
        assertNull(EncFsVolume.open(plainDir, "pw".toCharArray()))

        val dir = newVolumeDir()
        EncFsVolume.create(dir, "right".toCharArray())
        assertNull(EncFsVolume.open(dir, "wrong".toCharArray()))
        assertNotNull(EncFsVolume.open(dir, "right".toCharArray()))
    }

    @Test
    fun `roundtrip encrypts under an obfuscated name and decrypts back`() {
        val dir = newVolumeDir()
        val volume = EncFsVolume.create(dir, "pw".toCharArray())

        val plain = File(tempFolder.root, "secret.txt")
        val payload = "TopSecret payload 123".toByteArray(Charsets.UTF_8)
        plain.writeBytes(payload)

        val stored = volume.encryptFile(plain)
        assertTrue(stored.isFile)
        assertFalse("stored name must be obfuscated", stored.name == "secret.txt")
        val onDisk = stored.readBytes()
        assertFalse(
            "plaintext must not appear in the ciphertext",
            String(onDisk, Charsets.UTF_8).contains("TopSecret"),
        )

        assertEquals("secret.txt", volume.getDecryptedName(stored.name))
        assertEquals(1, volume.listFiles().size)

        val out = File(tempFolder.root, "restored.txt")
        assertTrue(volume.decryptFile(stored, out))
        assertArrayEquals(payload, out.readBytes())
    }

    @Test
    fun `name map persists across open - decrypted names survive a reopen`() {
        val dir = newVolumeDir()
        val volume = EncFsVolume.create(dir, "pw".toCharArray())
        val plain = File(tempFolder.root, "photo.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val stored = volume.encryptFile(plain)

        val reopened = EncFsVolume.open(dir, "pw".toCharArray())
        assertNotNull(reopened)
        assertEquals("photo.jpg", reopened!!.getDecryptedName(stored.name))
    }

    @Test
    fun `decryptFile returns false for tampered ciphertext`() {
        val dir = newVolumeDir()
        val volume = EncFsVolume.create(dir, "pw".toCharArray())
        val plain = File(tempFolder.root, "a.txt").apply { writeText("hello world, this is data") }
        val stored = volume.encryptFile(plain)

        val tampered = stored.readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        stored.writeBytes(tampered)

        val out = File(tempFolder.root, "out.txt")
        assertFalse(volume.decryptFile(stored, out))
    }

    @Test
    fun `preserveName keeps the original filename and needs no name map entry`() {
        val dir = newVolumeDir()
        val volume = EncFsVolume.create(dir, "pw".toCharArray())
        val plain = File(tempFolder.root, "readme.md").apply { writeText("plain") }

        val stored = volume.encryptFile(plain, preserveName = true)
        assertEquals("readme.md", stored.name)
        assertEquals("readme.md", volume.getDecryptedName(stored.name))
    }

    @Test
    fun `deleteEntry removes the blob and forgets the name after reopen`() {
        val dir = newVolumeDir()
        val volume = EncFsVolume.create(dir, "pw".toCharArray())
        val plain = File(tempFolder.root, "gone.txt").apply { writeText("bye") }
        val stored = volume.encryptFile(plain)

        assertTrue(volume.deleteEntry(stored.name))
        assertFalse(stored.exists())
        assertFalse(volume.deleteEntry(stored.name))

        val reopened = EncFsVolume.open(dir, "pw".toCharArray())!!
        assertTrue(reopened.listFiles().isEmpty())
        // Unknown entry falls back to the stored (obfuscated) name.
        assertEquals(stored.name, reopened.getDecryptedName(stored.name))
    }

    @Test
    fun `listFiles excludes control files`() {
        val dir = newVolumeDir()
        val volume = EncFsVolume.create(dir, "pw".toCharArray())
        volume.encryptFile(File(tempFolder.root, "x.txt").apply { writeText("x") })

        val names = volume.listFiles().map { it.name }
        assertFalse(names.contains(EncFsVolume.CONFIG_NAME))
        assertFalse(names.contains(".encfs.names"))
        assertEquals(1, names.size)
    }
}
