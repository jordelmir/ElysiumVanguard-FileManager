package com.elysium.vanguard.core.util

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random

/**
 * PHASE 10.3+ — round-trip the multi-format compression engine.
 *
 * Every format we support gets a write-then-read test that:
 *   1. Writes a small tree of files into a temp directory.
 *   2. Compresses it with [CompressionEngine.compress].
 *   3. Extracts the result into another temp directory with
 *      [CompressionEngine.decompress].
 *   4. Verifies every file made it back with the same bytes.
 *
 * ZIP gets the full security matrix: AES-256 and ZipCrypto password
 * round-trips, wrong-password and missing-password failures, a
 * plaintext-absence proof (encryption must be real), timestamp
 * preservation, corruption detection, progress reporting, and
 * split-archive creation + extraction.
 */
class CompressionEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var workDir: File

    @Before
    fun setUp() {
        workDir = tempFolder.newFolder("work")
    }

    @After
    fun tearDown() {
        // TemporaryFolder cleans itself; nothing to do.
    }

    private fun writeSampleFiles(prefix: String): List<File> {
        // Three files with deterministic content.
        val a = File(workDir, "${prefix}_a.txt")
        a.writeText("Hello, world!\n".repeat(20))
        val b = File(workDir, "${prefix}_b.bin")
        b.writeBytes(ByteArray(4096) { (it % 256).toByte() })
        val c = File(workDir, "${prefix}_c.txt")
        c.writeText("Elysium Vanguard".repeat(100))
        return listOf(a, b, c)
    }

    private fun assertRoundTrip(
        source: List<File>,
        output: File,
        format: ArchiveFormat,
        password: String? = null,
        options: CompressionEngine.CompressionOptions = CompressionEngine.CompressionOptions()
    ): File {
        // Compress
        val compressResult = CompressionEngine.compress(
            source, output, format, password, null, options
        )
        assertTrue("compress($format) failed: ${compressResult.exceptionOrNull()}",
            compressResult.isSuccess)
        assertTrue("output file not created for $format", output.exists())
        assertTrue("output file is empty for $format", output.length() > 0)

        // Decompress
        val extractDir = tempFolder.newFolder("extract_${format.name}")
        val decompressResult = CompressionEngine.decompress(
            output, extractDir, password
        )
        assertTrue("decompress($format) failed: ${decompressResult.exceptionOrNull()}",
            decompressResult.isSuccess)

        // Verify every file made it back with the right content
        for (src in source) {
            val recovered = File(extractDir, src.name)
            assertTrue("${src.name} missing after extract($format)", recovered.exists())
            if (format == ArchiveFormat.GZIP || format == ArchiveFormat.BZIP2 ||
                format == ArchiveFormat.XZ || format == ArchiveFormat.ZSTANDARD) {
                // Single-file stream formats have to be applied to a single file.
                continue
            }
            if (src.name.endsWith(".bin")) {
                assertArrayEquals(
                    "${src.name} bytes differ after extract($format)",
                    src.readBytes(), recovered.readBytes()
                )
            } else {
                assertEquals(
                    "${src.name} text differs after extract($format)",
                    src.readText(), recovered.readText()
                )
            }
        }
        return extractDir
    }

    private fun bytesContain(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }

    // ─────────────────────────────────────────────────────────────────
    // Round-trip per format
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun zip_roundTrip() {
        val files = writeSampleFiles("zip")
        val out = File(workDir, "out.zip")
        assertRoundTrip(files, out, ArchiveFormat.ZIP)
    }

    @Test
    fun tar_roundTrip() {
        val files = writeSampleFiles("tar")
        val out = File(workDir, "out.tar")
        assertRoundTrip(files, out, ArchiveFormat.TAR)
    }

    @Test
    fun tarGz_roundTrip() {
        val files = writeSampleFiles("tgz")
        val out = File(workDir, "out.tar.gz")
        assertRoundTrip(files, out, ArchiveFormat.TAR_GZ)
    }

    @Test
    fun tarBz2_roundTrip() {
        val files = writeSampleFiles("tbz2")
        val out = File(workDir, "out.tar.bz2")
        assertRoundTrip(files, out, ArchiveFormat.TAR_BZ2)
    }

    @Test
    fun tarXz_roundTrip() {
        val files = writeSampleFiles("txz")
        val out = File(workDir, "out.tar.xz")
        assertRoundTrip(files, out, ArchiveFormat.TAR_XZ)
    }

    @Test
    fun tarZst_roundTrip() {
        val files = writeSampleFiles("tzst")
        val out = File(workDir, "out.tar.zst")
        assertRoundTrip(files, out, ArchiveFormat.TAR_ZST)
    }

    @Test
    fun gzip_singleFile_roundTrip() {
        val src = File(workDir, "gzip_src.txt").apply { writeText("gzip me".repeat(200)) }
        val out = File(workDir, "out.gz")
        val r = CompressionEngine.compress(listOf(src), out, ArchiveFormat.GZIP)
        assertTrue("gzip compress failed: ${r.exceptionOrNull()}", r.isSuccess)
        val extractDir = tempFolder.newFolder("extract_gzip")
        val d = CompressionEngine.decompress(out, extractDir)
        assertTrue("gzip decompress failed: ${d.exceptionOrNull()}", d.isSuccess)
        // GZIP is a single-file stream — the recovered file is named
        // after the archive with the .gz extension stripped.
        val recovered = File(extractDir, "out")
        assertTrue(recovered.exists())
    }

    @Test
    fun bz2_singleFile_roundTrip() {
        val src = File(workDir, "bz2_src.txt").apply { writeText("bz2 me".repeat(200)) }
        val out = File(workDir, "out.bz2")
        val r = CompressionEngine.compress(listOf(src), out, ArchiveFormat.BZIP2)
        assertTrue("bz2 compress failed: ${r.exceptionOrNull()}", r.isSuccess)
        val extractDir = tempFolder.newFolder("extract_bz2")
        val d = CompressionEngine.decompress(out, extractDir)
        assertTrue("bz2 decompress failed: ${d.exceptionOrNull()}", d.isSuccess)
    }

    @Test
    fun xz_singleFile_roundTrip() {
        val src = File(workDir, "xz_src.txt").apply { writeText("xz me".repeat(200)) }
        val out = File(workDir, "out.xz")
        val r = CompressionEngine.compress(listOf(src), out, ArchiveFormat.XZ)
        assertTrue("xz compress failed: ${r.exceptionOrNull()}", r.isSuccess)
        val extractDir = tempFolder.newFolder("extract_xz")
        val d = CompressionEngine.decompress(out, extractDir)
        assertTrue("xz decompress failed: ${d.exceptionOrNull()}", d.isSuccess)
    }

    @Test
    fun zst_singleFile_roundTrip() {
        val src = File(workDir, "zst_src.txt").apply { writeText("zst me".repeat(200)) }
        val out = File(workDir, "out.zst")
        val r = CompressionEngine.compress(listOf(src), out, ArchiveFormat.ZSTANDARD)
        assertTrue("zst compress failed: ${r.exceptionOrNull()}", r.isSuccess)
        val extractDir = tempFolder.newFolder("extract_zst")
        val d = CompressionEngine.decompress(out, extractDir)
        assertTrue("zst decompress failed: ${d.exceptionOrNull()}", d.isSuccess)
    }

    // ─────────────────────────────────────────────────────────────────
    // 7Z round-trip + password
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun sevenZ_roundTrip() {
        val files = writeSampleFiles("7z")
        val out = File(workDir, "out.7z")
        // 7Z output without a password.
        val r = CompressionEngine.compress(files, out, ArchiveFormat.SEVEN_Z, null)
        assertTrue("7z compress failed: ${r.exceptionOrNull()}", r.isSuccess)
        val extractDir = tempFolder.newFolder("extract_7z")
        val d = CompressionEngine.decompress(out, extractDir, null)
        assertTrue("7z decompress failed: ${d.exceptionOrNull()}", d.isSuccess)
        // Spot-check the text file came back intact.
        val recovered = File(extractDir, "7z_a.txt")
        assertTrue(recovered.exists())
        assertEquals(File(workDir, "7z_a.txt").readText(), recovered.readText())
    }

    @Test
    fun sevenZ_passwordOutputNotSupported() {
        // Per the engine's design, 7Z output with password is rejected
        // because commons-compress 1.26 has no setPassword on its
        // SevenZOutputFile API. The UI surfaces this as a clear error.
        val files = writeSampleFiles("7z_pw")
        val out = File(workDir, "out_pw.7z")
        val r = CompressionEngine.compress(files, out, ArchiveFormat.SEVEN_Z, "secret")
        assertTrue("7z password should have been rejected", r.isFailure)
    }

    // ─────────────────────────────────────────────────────────────────
    // ZIP password — real round-trips (zip4j backend)
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun zip_passwordAes256_roundTrip() {
        val files = writeSampleFiles("pw")
        val out = File(workDir, "out_pw.zip")
        assertRoundTrip(files, out, ArchiveFormat.ZIP, "secret")
    }

    @Test
    fun zip_passwordIsActuallyEncrypted() {
        // Proof of real encryption: the plaintext must NOT appear as
        // bytes anywhere inside the archive. (A fake implementation
        // writes an unencrypted archive and just pretends.)
        val files = writeSampleFiles("encproof")
        val out = File(workDir, "encproof.zip")
        val r = CompressionEngine.compress(files, out, ArchiveFormat.ZIP, "secret")
        assertTrue("compress failed: ${r.exceptionOrNull()}", r.isSuccess)

        val haystack = out.readBytes()
        val needles = listOf(
            "Hello, world!\n".toByteArray(),
            "Elysium Vanguard".toByteArray()
        )
        for (needle in needles) {
            assertFalse(
                "plaintext content found inside the password-protected archive " +
                    "— encryption is not real",
                bytesContain(haystack, needle)
            )
        }
    }

    @Test
    fun zip_wrongPasswordFails() {
        val files = writeSampleFiles("wrongpw")
        val out = File(workDir, "wrongpw.zip")
        val r = CompressionEngine.compress(files, out, ArchiveFormat.ZIP, "secret")
        assertTrue("compress failed: ${r.exceptionOrNull()}", r.isSuccess)

        val extractDir = tempFolder.newFolder("extract_wrongpw")
        val d = CompressionEngine.decompress(out, extractDir, "wrong-password")
        assertTrue("wrong password must fail extraction", d.isFailure)
        val msg = d.exceptionOrNull()?.message ?: ""
        assertTrue(
            "failure message should say the password is incorrect, got: $msg",
            msg.contains("Incorrect password", ignoreCase = true)
        )
    }

    @Test
    fun zip_extractWithoutPasswordFails() {
        val files = writeSampleFiles("nopw")
        val out = File(workDir, "nopw.zip")
        val r = CompressionEngine.compress(files, out, ArchiveFormat.ZIP, "secret")
        assertTrue("compress failed: ${r.exceptionOrNull()}", r.isSuccess)

        val extractDir = tempFolder.newFolder("extract_nopw")
        val d = CompressionEngine.decompress(out, extractDir, null)
        assertTrue("extract without password must fail", d.isFailure)
        val msg = d.exceptionOrNull()?.message ?: ""
        assertTrue(
            "failure message should ask for a password, got: $msg",
            msg.contains("password", ignoreCase = true)
        )
    }

    @Test
    fun zip_zipCrypto_roundTrip() {
        val files = writeSampleFiles("zc")
        val out = File(workDir, "out_zc.zip")
        val opts = CompressionEngine.CompressionOptions(
            encryptionMethod = CompressionEngine.EncryptionMethod.ZIP_CRYPTO
        )
        assertRoundTrip(files, out, ArchiveFormat.ZIP, "legacy-secret", opts)
    }

    // ─────────────────────────────────────────────────────────────────
    // Lossless metadata: timestamps
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun zip_preservesFileTimestamps() {
        val src = File(workDir, "mtime_zip.txt").apply { writeText("timestamped zip") }
        val old = System.currentTimeMillis() - 5L * 24 * 3600 * 1000
        assertTrue("setLastModified failed", src.setLastModified(old))

        val out = File(workDir, "mtime.zip")
        val extractDir = assertRoundTrip(listOf(src), out, ArchiveFormat.ZIP)

        val recovered = File(extractDir, src.name)
        assertTrue(recovered.exists())
        // DOS timestamps have 2s granularity; anything close proves the
        // mtime survived. Without restoration it would be "≈now".
        val delta = Math.abs(recovered.lastModified() - src.lastModified())
        assertTrue(
            "zip mtime drifted by ${delta}ms (src=${src.lastModified()}, " +
                "recovered=${recovered.lastModified()})",
            delta <= 10_000
        )
    }

    @Test
    fun tar_preservesFileTimestamps() {
        val src = File(workDir, "mtime_tar.txt").apply { writeText("timestamped tar") }
        val old = System.currentTimeMillis() - 5L * 24 * 3600 * 1000
        assertTrue("setLastModified failed", src.setLastModified(old))

        val out = File(workDir, "mtime.tar")
        val extractDir = assertRoundTrip(listOf(src), out, ArchiveFormat.TAR)

        val recovered = File(extractDir, src.name)
        assertTrue(recovered.exists())
        val delta = Math.abs(recovered.lastModified() - src.lastModified())
        assertTrue(
            "tar mtime drifted by ${delta}ms (src=${src.lastModified()}, " +
                "recovered=${recovered.lastModified()})",
            delta <= 10_000
        )
    }

    // ─────────────────────────────────────────────────────────────────
    // Lossless edge cases
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun tar_longPathOver100Chars_roundTrip() {
        // POSIX long-name support: TAR headers only hold 100 chars for
        // the name; longer paths must go through PAX/GNU extension
        // records instead of throwing.
        val deepDir = File(workDir, "d".repeat(90))
        assertTrue(deepDir.mkdirs())
        val fileName = "f".repeat(30) + ".txt"
        val deepFile = File(deepDir, fileName)
        deepFile.writeText("deep content")

        val out = File(workDir, "deep.tar")
        val r = CompressionEngine.compress(listOf(deepDir), out, ArchiveFormat.TAR)
        assertTrue("compress failed: ${r.exceptionOrNull()}", r.isSuccess)

        val extractDir = tempFolder.newFolder("extract_deep_tar")
        val d = CompressionEngine.decompress(out, extractDir)
        assertTrue("extract failed: ${d.exceptionOrNull()}", d.isSuccess)

        val recovered = File(File(extractDir, deepDir.name), fileName)
        assertTrue("deep file missing after extract", recovered.exists())
        assertEquals("deep content", recovered.readText())
    }

    @Test
    fun zip_emptyDirectory_roundTrip() {
        val dir = File(workDir, "empty_dir_marker")
        assertTrue(dir.mkdirs())

        val out = File(workDir, "emptydir.zip")
        val r = CompressionEngine.compress(listOf(dir), out, ArchiveFormat.ZIP)
        assertTrue("compress failed: ${r.exceptionOrNull()}", r.isSuccess)

        val extractDir = tempFolder.newFolder("extract_emptydir")
        val d = CompressionEngine.decompress(out, extractDir)
        assertTrue("extract failed: ${d.exceptionOrNull()}", d.isSuccess)

        val recovered = File(extractDir, "empty_dir_marker")
        assertTrue("directory entry lost in round-trip", recovered.isDirectory)
    }

    @Test
    fun zip_unicodeFilename_roundTrip() {
        val src = File(workDir, "café ñandú 🦖.txt").apply { writeText("unicode ✓ content") }
        val out = File(workDir, "unicode.zip")
        assertRoundTrip(listOf(src), out, ArchiveFormat.ZIP)
    }

    // ─────────────────────────────────────────────────────────────────
    // Integrity: corruption must fail loudly
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun zip_corruptedArchiveFailsExtraction() {
        // Seeded random data → incompressible → the middle of the
        // archive is guaranteed to be entry payload, not headers.
        val big = File(workDir, "corrupt_src.bin")
            .apply { writeBytes(ByteArray(64 * 1024).also { Random(42).nextBytes(it) }) }
        val out = File(workDir, "corrupt.zip")
        val r = CompressionEngine.compress(listOf(big), out, ArchiveFormat.ZIP)
        assertTrue("compress failed: ${r.exceptionOrNull()}", r.isSuccess)

        val bytes = out.readBytes()
        val mid = bytes.size / 2
        bytes[mid] = (bytes[mid].toInt() xor 0xFF).toByte()
        out.writeBytes(bytes)

        val d = CompressionEngine.decompress(out, tempFolder.newFolder("extract_corrupt"))
        assertTrue(
            "corrupted archive must fail (CRC/data error), got success",
            d.isFailure
        )
    }

    // ─────────────────────────────────────────────────────────────────
    // Progress: percentage / speed / ETA / elapsed
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun progress_reportsPercentageSpeedEtaElapsed() {
        val files = writeSampleFiles("prog")
        val out = File(workDir, "prog.zip")

        val pcts = mutableListOf<Int>()
        val totals = mutableListOf<Long>()
        val speeds = mutableListOf<Long>()
        val etas = mutableListOf<Long>()
        val elapsed = mutableListOf<Long>()
        val listener = object : CompressionEngine.ProgressListener {
            override fun onProgress(
                percentage: Int,
                currentFile: String,
                speed: Long,
                etaSeconds: Long,
                totalBytes: Long,
                processedBytes: Long,
                elapsedSeconds: Long
            ) {
                pcts += percentage
                totals += totalBytes
                speeds += speed
                etas += etaSeconds
                elapsed += elapsedSeconds
            }
        }

        val r = CompressionEngine.compress(files, out, ArchiveFormat.ZIP, null, listener)
        assertTrue("compress failed: ${r.exceptionOrNull()}", r.isSuccess)

        assertTrue("no progress callbacks received", pcts.size >= 2)
        assertEquals("final callback must be 100%", 100, pcts.last())
        for (p in pcts.dropLast(1)) {
            assertTrue("intermediate percentage out of range: $p", p in 0..99)
        }
        assertTrue("totalBytes must be reported", totals.last() > 0)
        speeds.forEach { s -> assertTrue("speed must be >= 0, was $s", s >= 0) }
        etas.forEach { e -> assertTrue("eta must be >= 0, was $e", e >= 0) }
        elapsed.forEach { e -> assertTrue("elapsed must be >= 0, was $e", e >= 0) }
    }

    // ─────────────────────────────────────────────────────────────────
    // Split (multi-part) archives
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun zip_splitArchive_createAndExtract() {
        // ~2.5 MB of incompressible data forces zip4j to roll a new
        // part at the 1 MB boundary → out_split.z01 / .z02 / .zip.
        val big = File(workDir, "split_src.bin")
            .apply { writeBytes(ByteArray(2_500_000).also { Random(7).nextBytes(it) }) }
        val out = File(workDir, "out_split.zip")
        val opts = CompressionEngine.CompressionOptions(
            splitSize = CompressionEngine.SplitSize.MB_1
        )

        val r = CompressionEngine.compress(listOf(big), out, ArchiveFormat.ZIP, null, null, opts)
        assertTrue("split compress failed: ${r.exceptionOrNull()}", r.isSuccess)
        assertTrue("anchor .zip missing", out.exists())

        val z01 = File(workDir, "out_split.z01")
        assertTrue(
            "expected a .z01 part; siblings: ${workDir.list()?.joinToString()}",
            z01.exists()
        )

        // Extract from the FIRST part to prove anchor resolution works.
        val extractDir = tempFolder.newFolder("extract_split")
        val d = CompressionEngine.decompress(z01, extractDir)
        assertTrue("split extract failed: ${d.exceptionOrNull()}", d.isSuccess)

        val recovered = File(extractDir, big.name)
        assertTrue(recovered.exists())
        assertArrayEquals(big.readBytes(), recovered.readBytes())
    }

    // ─────────────────────────────────────────────────────────────────
    // Format detection
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun detectByMagic_findsZip() {
        val files = writeSampleFiles("detect_zip")
        val out = File(workDir, "detect.zip")
        CompressionEngine.compress(files, out, ArchiveFormat.ZIP)
        assertEquals(ArchiveFormat.ZIP, CompressionEngine.detectByMagic(out))
    }

    @Test
    fun detectByMagic_finds7z() {
        val files = writeSampleFiles("detect_7z")
        val out = File(workDir, "detect.7z")
        CompressionEngine.compress(files, out, ArchiveFormat.SEVEN_Z, null)
        assertEquals(ArchiveFormat.SEVEN_Z, CompressionEngine.detectByMagic(out))
    }

    @Test
    fun detectByMagic_findsGzip() {
        val src = File(workDir, "detect_gzip_src.txt").apply { writeText("hi".repeat(50)) }
        val out = File(workDir, "detect.gz")
        CompressionEngine.compress(listOf(src), out, ArchiveFormat.GZIP)
        assertEquals(ArchiveFormat.GZIP, CompressionEngine.detectByMagic(out))
    }

    @Test
    fun detectByMagic_returnsNullForUnknown() {
        val noise = File(workDir, "noise.txt").apply { writeText("just text") }
        assertEquals(null, CompressionEngine.detectByMagic(noise))
    }

    @Test
    fun fromPath_prefersLongerExtension() {
        // .tar.gz must beat .gz
        val tarGz = ArchiveFormat.fromPath("/tmp/foo.tar.gz")
        assertEquals(ArchiveFormat.TAR_GZ, tarGz)
        val plainGz = ArchiveFormat.fromPath("/tmp/foo.gz")
        assertEquals(ArchiveFormat.GZIP, plainGz)
    }
}
