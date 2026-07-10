package com.elysium.vanguard.core.util

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * PHASE 8.9 — CompressionEngine hardening tests.
 *
 * Coverage:
 *   - Round-trip ZIP / UNZIP.
 *   - ZIP bomb protection: refuses a small-compressed / large-declared
 *     archive; refuses a per-entry cap violation; refuses a total-size
 *     cap violation.
 *   - Zip Slip regression: still prevented by the existing check.
 */
class CompressionEngineTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var progress: CompressionEngine.ProgressListener

    @Before fun setUp() {
        progress = object : CompressionEngine.ProgressListener {
            override fun onProgress(percentage: Int, currentFile: String) {
                // no-op for tests
            }
        }
    }

    @Test fun `compress and decompress round trip preserves content`() = runBlocking {
        val source = File(tmp.root, "src.txt").apply { writeText("hello\nworld") }
        val archive = File(tmp.root, "out.zip")
        val compressResult = CompressionEngine.compress(listOf(source), archive, progress)
        assertTrue(compressResult.isSuccess)
        val extractDir = File(tmp.root, "extracted")
        val decompressResult = CompressionEngine.decompress(archive, extractDir, listener = progress)
        assertTrue(decompressResult.isSuccess)
        val extracted = File(extractDir, "src.txt")
        assertTrue(extracted.exists())
        assertEquals("hello\nworld", extracted.readText())
    }

    @Test fun `decompress refuses archive exceeding total size cap`() = runBlocking {
        // Create a zip with a single 10 MB file, but ask for a 1 MB cap.
        val source = File(tmp.root, "big.bin").apply {
            outputStream().use { out ->
                val chunk = ByteArray(64 * 1024) { 0 }
                repeat(160) { out.write(chunk) }  // 10 MB
            }
        }
        val archive = File(tmp.root, "big.zip")
        CompressionEngine.compress(listOf(source), archive, progress)
        val extractDir = File(tmp.root, "extracted")
        val result = CompressionEngine.decompress(
            archive, extractDir,
            maxDecompressedBytes = 1L * 1024 * 1024,
            listener = progress
        )
        assertTrue("decompression should fail with size cap", result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue("failure reason should mention size cap: $ex",
            ex?.message?.contains("exceeds") == true)
    }

    @Test fun `decompress refuses entry exceeding per-entry cap`() = runBlocking {
        // Manually craft a zip with one entry whose declared uncompressed size
        // is huge. We don't actually need to fill it with that much data —
        // we want to trip the entry cap during streaming.
        val archive = File(tmp.root, "single_big.zip")
        ZipOutputStream(archive.outputStream()).use { zos ->
            val entry = ZipEntry("huge.bin")
            // Set the declared size to something > maxEntryBytes but actually
            // stream 1 KB. The entry cap check is by bytes-written, not by
            // declared size, so this test verifies the streaming check.
            entry.size = 1_000_000_000L
            zos.putNextEntry(entry)
            val chunk = ByteArray(1024) { 0x41 }
            // Write enough to trip a 1 MB cap.
            repeat(2048) { zos.write(chunk) }  // 2 MB
            zos.closeEntry()
        }
        val extractDir = File(tmp.root, "extracted")
        val result = CompressionEngine.decompress(
            archive, extractDir,
            maxEntryBytes = 1L * 1024 * 1024,  // 1 MB cap
            listener = progress
        )
        assertTrue("decompression should fail with per-entry cap", result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue("failure reason should mention per-entry cap: $ex",
            ex?.message?.contains("Entry exceeds") == true)
    }

    // Note: ratio check is best-effort defense. Java's ZipEntry.size
    // reflects what was written, not what the file actually contains
    // (the streamer updates it as data is written). The primary defenses
    // against bombs are the per-entry cap and the total-size cap tested
    // above. We don't add a ratio test here because the behavior is
    // platform-dependent and the other caps are sufficient.

    @Test fun `decompress zip slip entry is rejected`() = runBlocking {
        // Create a zip with an entry whose name tries to escape the output dir.
        val archive = File(tmp.root, "slip.zip")
        ZipOutputStream(archive.outputStream()).use { zos ->
            val entry = ZipEntry("../../etc/passwd")
            zos.putNextEntry(entry)
            zos.write("pwned".toByteArray())
            zos.closeEntry()
        }
        val extractDir = File(tmp.root, "extracted")
        val result = CompressionEngine.decompress(archive, extractDir, listener = progress)
        assertTrue("zip slip should be rejected", result.isFailure)
    }
}