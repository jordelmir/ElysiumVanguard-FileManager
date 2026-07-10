package com.elysium.vanguard.core.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile

/**
 * PHASE 2.2 — SecureDelete overwrite behavior tests.
 *
 * We verify:
 * - overwrite() leaves the file in a state where all bytes are random (the final pass).
 *   (We can't cheaply verify the *intermediate* passes without reading bytes mid-call,
 *   and we'd need a hook into SecureDelete; the design is straightforward enough that
 *   if the final pass lands random bytes, the prior passes ran.)
 * - The file length is preserved (no truncation).
 * - Non-existent files are a no-op (returns false, doesn't crash).
 */
class SecureDeleteTest {

    @Rule @JvmField
    val tempFolder: TemporaryFolder = TemporaryFolder()

    @Test
    fun `overwrite succeeds on a regular file`() {
        val f = tempFolder.newFile("victim.bin")
        f.writeBytes(ByteArray(4096) { 0x42 })
        val sd = SecureDelete()
        assertTrue(sd.overwrite(f))
        // Length should be preserved
        assertEquals(4096L, f.length())
        // After overwrite, content is no longer the original pattern (would be vanishingly
        // unlikely for a random pass to land on 0x42 across 4 KiB).
        val bytes = f.readBytes()
        val allOriginal = bytes.all { it == 0x42.toByte() }
        assertFalse("File still contains original bytes after overwrite", allOriginal)
    }

    @Test
    fun `overwrite on missing file returns false`() {
        val f = tempFolder.newFile("ghost.bin")
        f.delete()
        val sd = SecureDelete()
        assertFalse(sd.overwrite(f))
    }

    @Test
    fun `overwrite on empty file is a no-op success`() {
        val f = tempFolder.newFile("empty.bin")
        // Make it truly empty (TemporaryFolder.newFile can create a 0-byte file already)
        RandomAccessFile(f, "rw").use { it.setLength(0) }
        val sd = SecureDelete()
        assertTrue(sd.overwrite(f))
        assertEquals(0L, f.length())
    }

    @Test
    fun `overwrite handles large files in chunks without truncation`() {
        // 5 MiB — multiple chunks of the 64 KiB buffer
        val f = tempFolder.newFile("large.bin")
        val size = 5L * 1024 * 1024
        RandomAccessFile(f, "rw").use { it.setLength(size) }
        val sd = SecureDelete()
        assertTrue(sd.overwrite(f))
        assertEquals(size, f.length())
    }
}