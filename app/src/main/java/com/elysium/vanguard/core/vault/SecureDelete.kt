package com.elysium.vanguard.core.vault

import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom

/**
 * PHASE 2.2 — DoD 5220.22-M inspired 3-pass overwrite before deletion.
 *
 * The classic DoD 5220.22-M spec specifies three passes: a fixed byte (e.g. 0x00),
 * its complement (0xFF), and random bytes. Modern storage (SSD with wear-leveling,
 * flash translation layers, journaling filesystems) means overwriting blocks on a
 * running filesystem does NOT guarantee the underlying bits are physically scrubbed.
 * We still do it because:
 *
 * 1. The default case — a regular file on a plain block device — is exactly the case
 *    DoD was designed for, and the wipe works as advertised there.
 * 2. It's a strong defense-in-depth signal even on flash: it defeats naive disk
 *    forensics tools that look at the visible filesystem, only physical chip-off
 *    can defeat it on SSDs.
 * 3. The cost is bounded (3x file size, sequential IO).
 *
 * For the truly paranoid case (selling the device), users should still factory-reset
 * or physically destroy the storage. We document this in the UI.
 */
class SecureDelete(private val random: SecureRandom = SecureRandom()) {

    /** Run the 3-pass overwrite. Returns true if all passes completed successfully. */
    fun overwrite(file: File): Boolean {
        if (!file.isFile) return false
        val length = file.length()
        if (length == 0L) return true

        return try {
            RandomAccessFile(file, "rw").use { raf ->
                // Pass 1: zeros
                writePattern(raf, length, 0x00.toByte())
                // Pass 2: ones
                writePattern(raf, length, 0xFF.toByte())
                // Pass 3: random
                writeRandom(raf, length)
                raf.fd.sync()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun writePattern(raf: RandomAccessFile, length: Long, byte: Byte) {
        raf.seek(0)
        val chunk = ByteArray(BUFFER_SIZE)
        chunk.fill(byte)
        var remaining = length
        while (remaining > 0) {
            val toWrite = minOf(remaining, chunk.size.toLong()).toInt()
            raf.write(chunk, 0, toWrite)
            remaining -= toWrite
        }
    }

    private fun writeRandom(raf: RandomAccessFile, length: Long) {
        raf.seek(0)
        val chunk = ByteArray(BUFFER_SIZE)
        var remaining = length
        while (remaining > 0) {
            random.nextBytes(chunk)
            val toWrite = minOf(remaining, chunk.size.toLong()).toInt()
            raf.write(chunk, 0, toWrite)
            remaining -= toWrite
        }
    }

    companion object {
        // 64 KiB strikes a good balance: large enough to saturate sequential IO,
        // small enough to avoid bloating the JVM heap for big files.
        private const val BUFFER_SIZE = 64 * 1024
    }
}