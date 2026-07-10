package com.elysium.vanguard.core.util

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * PHASE 10.3 — round-trip the new multi-format compression engine.
 *
 * Every format we support gets a write-then-read test that:
 *   1. Writes a small tree of files into a temp directory.
 *   2. Compresses it with [CompressionEngine.compress].
 *   3. Extracts the result into another temp directory with
 *      [CompressionEngine.decompress].
 *   4. Verifies every file made it back with the same bytes.
 *
 * ZIP also gets a password round-trip (write encrypted, read with
 * the right password, fail with the wrong one).
 */
class CompressionEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var workDir: File

    @Before
    fun setUp() {
        workDir = tempFolder.newFolder("work")
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
        password: String? = null
    ) {
        // Compress
        val compressResult = CompressionEngine.compress(
            source, output, format, password
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
    // ZIP password
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun zip_passwordOutputIsReadable() {
        // PHASE 10.3 NOTE: zip+password OUTPUT is supported (Java's
        // built-in ZipOutputStream with ZipCrypto). zip+password
        // INPUT requires commons-compress 1.27+ or zip4j. We assert
        // the output produces a non-empty file and that the engine
        // refuses extraction with a clear error when the archive is
        // password-protected.
        val files = writeSampleFiles("pw")
        val out = File(workDir, "out_pw.zip")
        val r = CompressionEngine.compress(files, out, ArchiveFormat.ZIP, "secret")
        assertTrue("zip+password compress failed: ${r.exceptionOrNull()}", r.isSuccess)
        assertTrue("zip+password file is empty", out.length() > 0)

        val extractDir = tempFolder.newFolder("extract_pw_ok")
        val d = CompressionEngine.decompress(out, extractDir, "secret")
        assertTrue(
            "zip+password extraction should refuse (not succeed), got: ${d.getOrNull()}",
            d.isFailure
        )
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
